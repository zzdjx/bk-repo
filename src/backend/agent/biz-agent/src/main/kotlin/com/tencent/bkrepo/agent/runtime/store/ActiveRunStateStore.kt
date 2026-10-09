/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime.store

/**
 * 会话运行态的共享存储：运行权锁、活跃 runId、跨副本停止信号。
 *
 * 三者同属一个会话、同生共死，实现上是一条文档（见
 * [com.tencent.bkrepo.agent.model.TAgentActiveRun]），所以每个方法都以 `(userId, threadId)` 为键。
 *
 * 生产实现只有 [MongoActiveRunStateStore] 一个；接口留着是为了测试能换上内存替身。
 */
interface ActiveRunStateStore {

    /** 抢占会话运行权；已被未过期的 run 占着时返回 false。 */
    fun tryAcquireLock(userId: String, threadId: String): Boolean

    /**
     * 为仍在跑的 [runId] 续上运行权的过期时间。
     *
     * @return 是否仍然持有；false 表示运行权已经不是这个 run 的了（锁过期后被别人接管），调用方应中止它，
     *   而不是继续续期——见 [com.tencent.bkrepo.agent.runtime.AgentRunAbortReason.LOCK_LOST]。
     */
    fun renewLock(userId: String, threadId: String, runId: String): Boolean

    fun isLockHeld(userId: String, threadId: String): Boolean

    /** 把抢到的运行权绑定到具体 run，并清掉上一轮可能残留的停止标志。 */
    fun bindActiveRun(userId: String, threadId: String, runId: String)

    fun getActiveRunId(userId: String, threadId: String): String?

    /** 释放 [runId] 持有的运行权；带 runId 条件，避免误删后一个 run 刚抢到的锁。 */
    fun releaseRun(userId: String, threadId: String, runId: String)

    /** 无条件清除会话运行态，供删除会话时收尾。 */
    fun clear(userId: String, threadId: String)

    fun requestStop(userId: String, threadId: String, runId: String)

    fun isStopRequested(userId: String, threadId: String, runId: String): Boolean
}
