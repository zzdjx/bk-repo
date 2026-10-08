/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.dao

import com.tencent.bkrepo.agent.model.TAgentKvStore
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 框架 `BaseStore` 的 Mongo 访问层，对应集合 `agent_kv_store`。
 *
 * 所有写操作都压成一次 `findAndModify` / `update`，让版本自增与值写入在服务端原子完成——
 * `putIfVersion` 要当跨副本的 CAS 原语用（`WorkspaceTaskRepository` 依赖这个语义），
 * 先读后写的实现是不够的。
 */
@Repository
class AgentKvStoreDao : SimpleMongoDao<TAgentKvStore>() {

    fun findItem(scope: String, namespace: String, key: String): TAgentKvStore? =
        findOne(Query(byItem(scope, namespace, key)))

    /** 无条件写入并把版本 +1，不存在则创建。返回写入后的记录。 */
    fun put(scope: String, namespace: String, key: String, value: String, expiresAt: Instant?): TAgentKvStore? {
        val update = Update()
            .inc(TAgentKvStore::version.name, 1L)
            .set(TAgentKvStore::value.name, value)
            .set(TAgentKvStore::expiresAt.name, expiresAt)
        val options = FindAndModifyOptions.options().upsert(true).returnNew(true)
        return findAndModify(Query(byItem(scope, namespace, key)), update, options, TAgentKvStore::class.java)
    }

    /**
     * CAS 写入：仅当当前版本等于 [expectedVersion] 时才写入。
     *
     * [expectedVersion] 为 0 表示"仅当记录不存在时才创建"——此时用 upsert，由
     * `(scope, namespace, key)` 唯一索引来裁决并发：抢输的那一方会撞 [DuplicateKeyException]，按失败处理。
     * 这里不能复用父类 [upsert]，它为了容忍非原子 upsert 会吞掉重复键异常重试一次，正好把 CAS 要的失败信号抹掉。
     */
    fun putIfVersion(
        scope: String,
        namespace: String,
        key: String,
        value: String,
        expectedVersion: Long,
        expiresAt: Instant?,
    ): Boolean {
        val query = Query(byItem(scope, namespace, key).and(TAgentKvStore::version.name).`is`(expectedVersion))
        val update = Update()
            .inc(TAgentKvStore::version.name, 1L)
            .set(TAgentKvStore::value.name, value)
            .set(TAgentKvStore::expiresAt.name, expiresAt)
        if (expectedVersion > 0) {
            return updateFirst(query, update).modifiedCount > 0
        }
        return try {
            val options = FindAndModifyOptions.options().upsert(true).returnNew(true)
            findAndModify(query, update, options, TAgentKvStore::class.java) != null
        } catch (ignored: DuplicateKeyException) {
            false
        }
    }

    /** 按 key 字典序分页列出一个命名空间下的记录，对齐 Redis 版本 `ZRANGEBYLEX` 的顺序。 */
    fun listByNamespace(scope: String, namespace: String, limit: Int, offset: Int): List<TAgentKvStore> {
        val query = Query(byNamespace(scope, namespace))
            .with(Sort.by(Sort.Direction.ASC, TAgentKvStore::key.name))
            .skip(offset.toLong())
            .limit(limit)
        return find(query)
    }

    fun removeItem(scope: String, namespace: String, key: String) {
        remove(Query(byItem(scope, namespace, key)))
    }

    /** 把一批记录的过期时刻整体往后推，用于"最后一次使用之后再放多久"的续期语义。 */
    fun touch(scope: String, namespace: String, keys: Collection<String>, expiresAt: Instant) {
        if (keys.isEmpty()) {
            return
        }
        val query = Query(byNamespace(scope, namespace).and(TAgentKvStore::key.name).`in`(keys))
        updateMulti(query, Update().set(TAgentKvStore::expiresAt.name, expiresAt))
    }

    /** 反查这个 scope 下出现过的全部命名空间，由调用方再按 glob 过滤。 */
    fun distinctNamespaces(scope: String): List<String> {
        val query = Query(Criteria.where(TAgentKvStore::scope.name).`is`(scope))
        return determineMongoTemplate()
            .findDistinct(query, TAgentKvStore::namespace.name, TAgentKvStore::class.java, String::class.java)
    }

    private fun byItem(scope: String, namespace: String, key: String): Criteria =
        byNamespace(scope, namespace).and(TAgentKvStore::key.name).`is`(key)

    private fun byNamespace(scope: String, namespace: String): Criteria =
        Criteria.where(TAgentKvStore::scope.name).`is`(scope)
            .and(TAgentKvStore::namespace.name).`is`(namespace)
}
