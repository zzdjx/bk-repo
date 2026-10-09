/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime.store

import com.tencent.bkrepo.agent.dao.AgentActiveRunDao
import java.time.Duration
import java.time.Instant

/**
 * 会话运行态落 MongoDB，每个会话一条 [com.tencent.bkrepo.agent.model.TAgentActiveRun]。
 *
 * 放 Mongo 而不是 Redis：agent 模块本来就硬依赖 Mongo（会话、消息、run 记录、中断现场全在里面），而
 * 这份状态的写入频率只和并发 run 数挂钩——每个 run 抢一次锁、每几秒续一次期、结束删一次，不是热路径。
 * 唯一曾经的热点是停止标志的轮询，已经在
 * [com.tencent.bkrepo.agent.service.run.AgentRunScope.shouldPollStopFlag] 节流到每秒一次。
 */
class MongoActiveRunStateStore(
    private val dao: AgentActiveRunDao,
    private val lockTtl: Duration,
) : ActiveRunStateStore {

    override fun tryAcquireLock(userId: String, threadId: String): Boolean {
        return dao.tryAcquire(scopeKey(userId, threadId), expiry())
    }

    override fun renewLock(userId: String, threadId: String, runId: String): Boolean {
        return dao.renew(scopeKey(userId, threadId), runId, expiry())
    }

    override fun isLockHeld(userId: String, threadId: String): Boolean {
        return dao.findLive(scopeKey(userId, threadId)) != null
    }

    override fun bindActiveRun(userId: String, threadId: String, runId: String) {
        dao.bindRun(scopeKey(userId, threadId), runId, expiry())
    }

    override fun getActiveRunId(userId: String, threadId: String): String? {
        return dao.findLive(scopeKey(userId, threadId))?.runId
    }

    override fun releaseRun(userId: String, threadId: String, runId: String) {
        dao.releaseRun(scopeKey(userId, threadId), runId)
    }

    override fun clear(userId: String, threadId: String) {
        dao.clear(scopeKey(userId, threadId))
    }

    override fun requestStop(userId: String, threadId: String, runId: String) {
        dao.requestStop(scopeKey(userId, threadId), runId)
    }

    override fun isStopRequested(userId: String, threadId: String, runId: String): Boolean {
        return dao.findLive(scopeKey(userId, threadId))?.stopRequestedRunId == runId
    }

    private fun expiry(): Instant = Instant.now().plus(lockTtl)

    private fun scopeKey(userId: String, threadId: String): String = "$userId:$threadId"
}
