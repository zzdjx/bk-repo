/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.task

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.dao.AgentKvStoreDao
import com.tencent.bkrepo.agent.model.TAgentKvStore
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import java.time.Duration
import java.time.Instant

/**
 * 基于 MongoDB 的 [BaseStore] 实现，承载长期记忆与分布式后台任务。
 *
 * ## 为什么不用框架自带的实现
 *
 * 框架 2.0.4 提供了 `MongoBaseStore`，但它的文档只有 `key`/`namespace`/`value`/`version` 四个字段，
 * 没有任何时间戳，因此建不出 TTL 索引——而这个 store 承载的两类数据都是"只写不删"的
 * （`WorkspaceTaskRepository` 的孤儿清扫只处理僵死任务，不清历史），没有保留期就会单调增长。
 * 它也没有 [scanNamespaces]，而 [com.tencent.bkrepo.agent.memory.AgentMemoryMaintenanceJob] 需要靠它
 * 反查"所有保存过 daily ledger 的用户"。这两点都不是能在外面补的。
 *
 * ## 保留期语义（[ttl] / [refreshTtlOnRead]）
 *
 * - [refreshTtlOnRead] 关闭（后台任务）：过期时刻只由写入推进，等于"最后一次更新之后再放多久"。
 * - [refreshTtlOnRead] 开启（长期记忆）：[get]/[search] 也会续期，等于"最后一次使用之后再放多久"，
 *   避免用户半年前保存、之后一直在读的偏好被静默清掉。
 *
 * 读路径会自己比一次 `expiresAt`，不完全依赖 TTL 索引：Mongo 的 TTL 线程约 60 秒才扫一轮，
 * 这个窗口里过期文档仍然查得到，不自己判就会把已经该消失的记忆当成有效内容返回。
 *
 * ## 并发语义
 *
 * [put] 与 [putIfVersion] 都落在单条 `findAndModify` / `update` 上，版本自增与值写入在服务端原子完成，
 * 因此 [putIfVersion] 可以安全地当作跨副本的 CAS 原语
 * （[io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository] 依赖这个语义）。
 */
class MongoStore(
    private val dao: AgentKvStoreDao,
    private val scope: String,
    private val objectMapper: ObjectMapper,
    private val ttl: Duration?,
    private val refreshTtlOnRead: Boolean = false,
) : BaseStore {

    override fun get(namespace: MutableList<String>, key: String): StoreItem? {
        validateKey(key)
        val ns = namespacePath(namespace)
        val record = dao.findItem(scope, ns, key)?.takeUnless { it.isExpired() } ?: return null
        touch(ns, listOf(key))
        return record.toItem()
    }

    override fun put(namespace: MutableList<String>, key: String, value: MutableMap<String, Any>) {
        validateKey(key)
        dao.put(scope, namespacePath(namespace), key, serialize(value), nextExpiresAt())
    }

    override fun putIfVersion(
        namespace: MutableList<String>,
        key: String,
        value: MutableMap<String, Any>,
        expectedVersion: Long,
    ): Boolean {
        validateKey(key)
        require(expectedVersion >= 0) { "expectedVersion must be non-negative" }
        return dao.putIfVersion(
            scope = scope,
            namespace = namespacePath(namespace),
            key = key,
            value = serialize(value),
            expectedVersion = expectedVersion,
            expiresAt = nextExpiresAt(),
        )
    }

    override fun search(namespace: MutableList<String>, limit: Int, offset: Int): MutableList<StoreItem> {
        if (limit <= 0) {
            return mutableListOf()
        }
        val ns = namespacePath(namespace)
        val records = dao.listByNamespace(scope, ns, limit, maxOf(offset, 0)).filterNot { it.isExpired() }
        touch(ns, records.map { it.key })
        return records.mapTo(mutableListOf()) { it.toItem() }
    }

    override fun delete(namespace: MutableList<String>, key: String) {
        validateKey(key)
        dao.removeItem(scope, namespacePath(namespace), key)
    }

    /**
     * 反查这个 store 里存在过数据的命名空间，只保留匹配 [namespaceGlobPattern] 的部分。
     *
     * 用途很窄：批处理任务需要"这个 store 里一共有哪些命名空间"（比如长期记忆定期整理任务要知道
     * "所有保存过 daily ledger 的用户"，见 [com.tencent.bkrepo.agent.memory.AgentMemoryMaintenanceJob]），
     * 而常规读写路径永远是调用方已经知道 namespace（由业务侧的 userId/agentId 拼出来），不需要反查，
     * 也不应该依赖这个方法——它的开销是"这个 scope 下全部命名空间"量级，不是 O(1) 的。
     *
     * glob 里 `*` 通配一个或多个 `\u0000` 分隔的段。过滤放在客户端用自己转的正则做，不下推成 Mongo 的
     * `$regex`：这样脱离真实 Mongo 也能测这条过滤逻辑，也不用去对齐两套 glob 语义的差异。
     */
    fun scanNamespaces(namespaceGlobPattern: String): List<String> {
        val matcher = globToRegex(namespaceGlobPattern)
        return dao.distinctNamespaces(scope).filter { matcher.matches(it) }
    }

    private fun nextExpiresAt(): Instant? = ttl?.let { Instant.now().plus(it) }

    /** 把过期时刻往后推一个完整保留期。只有同时配了 TTL 且开启 [refreshTtlOnRead] 才生效。 */
    private fun touch(namespace: String, keys: List<String>) {
        if (!refreshTtlOnRead) {
            return
        }
        val expiresAt = nextExpiresAt() ?: return
        dao.touch(scope, namespace, keys, expiresAt)
    }

    private fun TAgentKvStore.isExpired(): Boolean = expiresAt?.isBefore(Instant.now()) == true

    private fun TAgentKvStore.toItem(): StoreItem = StoreItem(key, deserialize(value), version)

    private fun serialize(value: Map<String, Any?>?): String =
        objectMapper.writeValueAsString(value ?: emptyMap<String, Any?>())

    private fun deserialize(json: String?): MutableMap<String, Any> {
        if (json.isNullOrEmpty()) {
            return mutableMapOf()
        }
        return objectMapper.readValue(json, object : TypeReference<MutableMap<String, Any>>() {})
    }

    /** [TAgentKvStore.scope] 的取值，用来在同一张表里区分两类保留期不同的数据。 */
    object Scope {
        const val MEMORY = "memory"
        const val TASK = "task"
    }

    companion object {
        private const val NS_SEPARATOR = "\u0000"

        private fun namespacePath(namespace: List<String>): String = namespace.joinToString(NS_SEPARATOR)

        private fun validateKey(key: String) {
            require(key.isNotEmpty()) { "key must not be null or empty" }
            require(!key.contains('\u0000')) { "key must not contain the NUL character" }
        }

        /** glob 里除 `*` 之外会被当作正则特殊字符转义的字符集合。 */
        private const val REGEX_SPECIAL_CHARS = "\\.^$|?+()[]{}"

        /** 把只支持 `*` 通配符的 glob 转成等价的完全匹配正则；[glob] 里的正则特殊字符会被转义成字面值。 */
        private fun globToRegex(glob: String): Regex {
            val pattern = StringBuilder()
            glob.forEach { ch ->
                when {
                    ch == '*' -> pattern.append(".*")
                    REGEX_SPECIAL_CHARS.contains(ch) -> pattern.append('\\').append(ch)
                    else -> pattern.append(ch)
                }
            }
            return Regex(pattern.toString())
        }
    }
}
