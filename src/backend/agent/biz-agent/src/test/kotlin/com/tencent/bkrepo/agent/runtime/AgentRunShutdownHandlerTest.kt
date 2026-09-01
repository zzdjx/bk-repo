/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.runtime.store.InMemoryActiveRunStateStore
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.shutdown.GracefulShutdownManager
import io.agentscope.core.shutdown.ShutdownState
import io.agentscope.core.state.InMemoryAgentStateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * 用的是真实的框架单例 [GracefulShutdownManager]（构造器私有、无法替换），因此每个用例结束后必须
 * `resetForTesting()` 把状态复位——否则同一 JVM 里后续跑 Agent 的用例都会被判为"正在停机"而拒绝调用。
 */
class AgentRunShutdownHandlerTest {

    private val stateStore = InMemoryActiveRunStateStore()
    private val activeRunManager = ActiveRunManager(
        stateStore = stateStore,
        agentStateStore = InMemoryAgentStateStore(),
        eventPublisher = ApplicationEventPublisher { },
    )
    private val shutdownManager = GracefulShutdownManager.getInstance()
    private val handler = AgentRunShutdownHandler(activeRunManager, shutdownManager)

    @AfterEach
    fun resetShutdownManager() {
        shutdownManager.resetForTesting()
    }

    @Test
    fun `停机时应拒绝新调用并中止在跑run释放会话锁`() {
        val scope = ActiveRunScope("user-1", "project-1", "thread-1")
        val reasons = mutableListOf<AgentRunAbortReason>()
        activeRunManager.tryAcquire(scope)
        activeRunManager.bindActiveRun(scope, "run-1")
        activeRunManager.registerHandle(scope, "run-1", runtimeContext = runtimeContext()) { reason ->
            reasons += reason
            activeRunManager.releaseRun(scope, "run-1")
        }

        handler.shutdown()

        assertEquals(ShutdownState.SHUTTING_DOWN, shutdownManager.state)
        assertFalse(shutdownManager.isAcceptingRequests, "停机开始后不应再接受新的 Agent 调用")
        assertEquals(listOf(AgentRunAbortReason.SERVER_SHUTDOWN), reasons)
        assertFalse(activeRunManager.isRunning(scope))
    }

    @Test
    fun `重复触发停机只生效一次`() {
        val scope = ActiveRunScope("user-1", "project-1", "thread-1")
        var abortCount = 0
        activeRunManager.registerHandle(scope, "run-1", runtimeContext = runtimeContext()) { abortCount++ }

        handler.shutdown()
        handler.shutdown()

        assertEquals(1, abortCount)
    }

    private fun runtimeContext(): RuntimeContext = RuntimeContext.builder()
        .userId("user-1")
        .sessionId("thread-1")
        .build()
}
