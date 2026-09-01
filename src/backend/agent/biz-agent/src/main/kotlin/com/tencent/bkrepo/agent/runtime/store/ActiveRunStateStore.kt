/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime.store

/** Active run 分布式状态：run 锁、active runId、跨副本 stop 信号。 */
interface ActiveRunStateStore {

    fun tryAcquireLock(userId: String, threadId: String): Boolean

    fun releaseLock(userId: String, threadId: String)

    /**
     * 为仍在跑的 [runId] 续上会话锁与活跃 run 绑定的过期时间。
     *
     * @return 是否仍然持有；false 表示这个 run 已经不是会话的活跃 run（锁被别人接管），调用方应中止它，
     *   而不是继续续期——见 [com.tencent.bkrepo.agent.runtime.AgentRunAbortReason.LOCK_LOST]。
     */
    fun renewLock(userId: String, threadId: String, runId: String): Boolean

    fun isLockHeld(userId: String, threadId: String): Boolean

    fun bindActiveRun(userId: String, threadId: String, runId: String)

    fun getActiveRunId(userId: String, threadId: String): String?

    fun clearActiveRun(userId: String, threadId: String)

    fun requestStop(runId: String)

    fun isStopRequested(runId: String): Boolean

    fun clearStop(runId: String)
}
