/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.runtime.store.ActiveRunStateStore
import com.tencent.bkrepo.agent.runtime.store.InMemoryActiveRunStateStore
import io.agentscope.core.state.InMemoryAgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class ActiveRunManagerTest {

    private val stateStore = InMemoryActiveRunStateStore()
    private val manager = ActiveRunManager(
        stateStore = stateStore,
        agentStateStore = InMemoryAgentStateStore(),
        eventPublisher = ApplicationEventPublisher { },
    )

    @Test
    fun `pending stop applies when handle registers later`() {
        val scope = ActiveRunScope("user-1", "project-1", "thread-1")
        var aborted = false
        manager.requestStop(scope, "run-1")
        manager.registerHandle(scope, "run-1", runtimeContext = stubRuntimeContext()) {
            aborted = true
        }
        assertTrue(aborted)
    }

    @Test
    fun `releaseRun only clears matching active runId`() {
        val scope = ActiveRunScope("user-1", "project-1", "thread-1")
        assertTrue(manager.tryAcquire(scope))
        manager.bindActiveRun(scope, "run-old")
        manager.releaseRun(scope, "run-new")
        assertTrue(manager.isRunning(scope))
        assertTrue(manager.getActiveRunId(scope) == "run-old")
        manager.releaseRun(scope, "run-old")
        assertFalse(manager.isRunning(scope))
    }

    @Test
    fun `abortLocalRuns中止本副本全部在跑run并释放会话锁`() {
        val first = ActiveRunScope("user-1", "project-1", "thread-1")
        val second = ActiveRunScope("user-2", "project-1", "thread-2")
        val reasons = mutableListOf<AgentRunAbortReason>()
        listOf(first to "run-1", second to "run-2").forEach { (scope, runId) ->
            assertTrue(manager.tryAcquire(scope))
            manager.bindActiveRun(scope, runId)
            // 模拟 AgentRunLifecycleManager 的收尾：真实实现里 abort 回调最终会 releaseRun
            manager.registerHandle(scope, runId, runtimeContext = stubRuntimeContext()) { reason ->
                reasons += reason
                manager.releaseRun(scope, runId)
            }
        }

        val aborted = manager.abortLocalRuns(AgentRunAbortReason.SERVER_SHUTDOWN)

        assertEquals(2, aborted)
        assertEquals(listOf(AgentRunAbortReason.SERVER_SHUTDOWN, AgentRunAbortReason.SERVER_SHUTDOWN), reasons)
        assertFalse(manager.isRunning(first), "停机后会话锁必须立刻释放，否则用户要等 TTL 过期才能重发")
        assertFalse(manager.isRunning(second))
    }

    @Test
    fun `abortLocalRuns没有在跑run时返回0`() {
        assertEquals(0, manager.abortLocalRuns(AgentRunAbortReason.SERVER_SHUTDOWN))
    }

    @Test
    fun `abortLocalRuns单个run收尾抛异常不影响其它run`() {
        val failing = ActiveRunScope("user-1", "project-1", "thread-1")
        val healthy = ActiveRunScope("user-2", "project-1", "thread-2")
        manager.registerHandle(failing, "run-1", runtimeContext = stubRuntimeContext()) {
            throw IllegalStateException("boom")
        }
        var healthyAborted = false
        manager.registerHandle(healthy, "run-2", runtimeContext = stubRuntimeContext()) {
            healthyAborted = true
        }

        val aborted = manager.abortLocalRuns(AgentRunAbortReason.SERVER_SHUTDOWN)

        assertEquals(1, aborted)
        assertTrue(healthyAborted)
    }

    @Test
    fun `续期应覆盖本副本全部在跑run`() {
        val first = ActiveRunScope("user-1", "project-1", "thread-1")
        val second = ActiveRunScope("user-2", "project-1", "thread-2")
        listOf(first to "run-1", second to "run-2").forEach { (scope, runId) ->
            assertTrue(manager.tryAcquire(scope))
            manager.bindActiveRun(scope, runId)
            manager.registerHandle(scope, runId, runtimeContext = stubRuntimeContext()) { }
        }

        assertEquals(2, manager.renewLocalRunLocks())
    }

    @Test
    fun `没有在跑run时续期是空操作`() {
        assertEquals(0, manager.renewLocalRunLocks())
    }

    @Test
    fun `会话已被别人接管时续期应中止本地run且不误删接管方的锁`() {
        val scope = ActiveRunScope("user-1", "project-1", "thread-1")
        assertTrue(manager.tryAcquire(scope))
        manager.bindActiveRun(scope, "run-mine")
        val reasons = mutableListOf<AgentRunAbortReason>()
        manager.registerHandle(scope, "run-mine", runtimeContext = stubRuntimeContext()) { reason ->
            reasons += reason
            manager.releaseRun(scope, "run-mine")
        }
        // 锁过期后被另一个 run 接管：活跃 run 绑定已经不是我们的 runId
        manager.bindActiveRun(scope, "run-theirs")

        val renewed = manager.renewLocalRunLocks()

        assertEquals(0, renewed)
        assertEquals(listOf(AgentRunAbortReason.LOCK_LOST), reasons)
        assertTrue(manager.isRunning(scope), "接管方的会话锁不能被我们的收尾流程释放")
        assertEquals("run-theirs", manager.getActiveRunId(scope))
    }

    @Test
    fun `续期抛异常时不应中止run而是等下一轮重试`() {
        val scope = ActiveRunScope("user-1", "project-1", "thread-1")
        val flaky = object : ActiveRunStateStore by InMemoryActiveRunStateStore() {
            override fun renewLock(userId: String, threadId: String, runId: String): Boolean =
                throw IllegalStateException("redis is flaky")
        }
        val flakyManager = ActiveRunManager(flaky, InMemoryAgentStateStore(), ApplicationEventPublisher { })
        var aborted = false
        flakyManager.registerHandle(scope, "run-1", runtimeContext = stubRuntimeContext()) { aborted = true }

        assertEquals(0, flakyManager.renewLocalRunLocks())
        assertFalse(aborted, "Redis 抖动不该让正在跑的对话被杀掉")
    }

    private fun stubRuntimeContext(): io.agentscope.core.agent.RuntimeContext {
        return io.agentscope.core.agent.RuntimeContext.builder()
            .userId("user-1")
            .sessionId("thread-1")
            .build()
    }
}
