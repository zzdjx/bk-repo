/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.dao

import com.tencent.bkrepo.agent.model.TAgentActiveRun
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

/** 会话运行权访问层，对应 Mongo 集合 `agent_active_run`。 */
@Repository
class AgentActiveRunDao : SimpleMongoDao<TAgentActiveRun>() {

    /**
     * 抢占运行权，抢到返回 true。
     *
     * 一次 `findAndModify(upsert)` 覆盖三种情形：没有文档时插入；有一条已过期的文档时就地改写（相当于
     * 接管死锁）；有一条未过期的文档时查询条件不匹配，Mongo 转而尝试插入，撞上 `scopeKey` 唯一索引抛
     * [DuplicateKeyException]，即"锁被别人占着"。
     *
     * 这里不能用基类的 `upsert`：它会吞掉 [DuplicateKeyException] 并自动重试，而"撞键"恰恰是本方法
     * 判定抢锁失败的唯一信号。
     */
    fun tryAcquire(scopeKey: String, expiresAt: Instant): Boolean {
        val query = Query(
            Criteria.where(TAgentActiveRun::scopeKey.name).`is`(scopeKey)
                .and(TAgentActiveRun::expiresAt.name).lt(Instant.now()),
        )
        val update = Update()
            .set(TAgentActiveRun::scopeKey.name, scopeKey)
            .set(TAgentActiveRun::expiresAt.name, expiresAt)
            .unset(TAgentActiveRun::stopRequestedRunId.name)
            .unset(TAgentActiveRun::runId.name)
        val options = FindAndModifyOptions().upsert(true).returnNew(true)
        return try {
            findAndModify(query, update, options, TAgentActiveRun::class.java) != null
        } catch (ignored: DuplicateKeyException) {
            false
        }
    }

    /** 读取未过期的运行态；过期文档等同于不存在（Mongo TTL 线程有最长约 60s 的延迟）。 */
    fun findLive(scopeKey: String): TAgentActiveRun? {
        return findOne(
            Query(
                Criteria.where(TAgentActiveRun::scopeKey.name).`is`(scopeKey)
                    .and(TAgentActiveRun::expiresAt.name).gte(Instant.now()),
            ),
        )
    }

    /**
     * 把运行权绑定到具体 run。
     *
     * 不碰 `stopRequestedRunId`：抢锁时已经清过一次，而绑定之前到达的停止信号正是冲着这个 runId 来的，
     * 清掉就等于把它吃了。
     */
    fun bindRun(scopeKey: String, runId: String, expiresAt: Instant) {
        updateFirst(
            byScopeKey(scopeKey),
            Update()
                .set(TAgentActiveRun::runId.name, runId)
                .set(TAgentActiveRun::expiresAt.name, expiresAt),
        )
    }

    /** 续期，返回 false 表示运行权已经不属于这个 run（被接管或已释放）。 */
    fun renew(scopeKey: String, runId: String, expiresAt: Instant): Boolean {
        val result = updateFirst(
            byScopeKeyAndRun(scopeKey, runId),
            Update().set(TAgentActiveRun::expiresAt.name, expiresAt),
        )
        return result.modifiedCount > 0
    }

    /** 登记停止信号。按 scopeKey 而非 runId 定位：请求可能早于 runId 绑定到达。 */
    fun requestStop(scopeKey: String, runId: String) {
        updateFirst(
            byScopeKey(scopeKey),
            Update().set(TAgentActiveRun::stopRequestedRunId.name, runId),
        )
    }

    /** 释放运行权；带 [runId] 条件，避免把后一个 run 刚抢到的锁误删。 */
    fun releaseRun(scopeKey: String, runId: String) {
        remove(byScopeKeyAndRun(scopeKey, runId))
    }

    /** 无条件清除运行态，供删除会话时收尾（含回收已过期的残留文档）。 */
    fun clear(scopeKey: String) {
        remove(byScopeKey(scopeKey))
    }

    private fun byScopeKey(scopeKey: String) =
        Query(Criteria.where(TAgentActiveRun::scopeKey.name).`is`(scopeKey))

    private fun byScopeKeyAndRun(scopeKey: String, runId: String) = Query(
        Criteria.where(TAgentActiveRun::scopeKey.name).`is`(scopeKey)
            .and(TAgentActiveRun::runId.name).`is`(runId),
    )
}
