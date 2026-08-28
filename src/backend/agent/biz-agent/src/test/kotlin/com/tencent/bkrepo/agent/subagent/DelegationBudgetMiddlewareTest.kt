/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.subagent

import com.fasterxml.jackson.databind.JsonNode
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.Event
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.ReasoningInput
import io.agentscope.harness.agent.subagent.task.BackgroundTask
import io.agentscope.harness.agent.subagent.task.TaskRepository
import io.agentscope.harness.agent.subagent.task.TaskRunSpec
import io.agentscope.harness.agent.subagent.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/** 不关心具体实现，仅用来满足 [io.agentscope.core.middleware.MiddlewareBase] 钩子签名里的 `Agent` 形参。 */
private val STUB_AGENT: Agent = object : Agent {
    override fun getAgentId(): String = "stub-agent"
    override fun getName(): String = "stub-agent"
    override fun interrupt() = Unit
    override fun interrupt(msg: Msg?) = Unit
    override fun call(msgs: MutableList<Msg>): Mono<Msg> = Mono.empty()
    override fun call(msgs: MutableList<Msg>, structuredModel: Class<*>): Mono<Msg> = Mono.empty()
    override fun call(msgs: MutableList<Msg>, schema: JsonNode): Mono<Msg> = Mono.empty()
    override fun stream(msgs: MutableList<Msg>, options: StreamOptions): Flux<Event> = Flux.empty()
    override fun stream(msgs: MutableList<Msg>, options: StreamOptions, structuredModel: Class<*>): Flux<Event> =
        Flux.empty()
    override fun stream(msgs: MutableList<Msg>, options: StreamOptions, schema: JsonNode): Flux<Event> = Flux.empty()
    override fun observe(msg: Msg): Mono<Void> = Mono.empty()
    override fun observe(msgs: MutableList<Msg>): Mono<Void> = Mono.empty()
}

class DelegationBudgetMiddlewareTest {

    private fun runtimeProperties(coordinatorEnabled: Boolean = true, maxParallelDelegations: Int = 2) =
        EffectiveAgentRuntimeProperties.defaults().let { defaults ->
            defaults.copy(
                topology = defaults.topology.copy(
                    coordinator = defaults.topology.coordinator.copy(
                        enabled = coordinatorEnabled,
                        maxParallelDelegations = maxParallelDelegations,
                    ),
                ),
            )
        }

    private fun ctx(sessionId: String?) = RuntimeContext.builder().sessionId(sessionId).build()

    private fun toolCall(name: String) = ToolUseBlock.builder().id("id-$name").name(name).build()

    /** 空的 [ObjectProvider]：`getIfAvailable()` 直接返回给定值（可为 `null`）。 */
    private class FixedTaskRepositoryProvider(private val value: TaskRepository?) : ObjectProvider<TaskRepository> {
        override fun getObject(): TaskRepository = value ?: throw IllegalStateException("no TaskRepository")
        override fun getIfAvailable(): TaskRepository? = value
    }

    /** 只实现 [listTasks]，其余方法在测试中不会被调用到。 */
    private class FakeTaskRepository(private val tasks: List<BackgroundTask>) : TaskRepository {
        override fun getTask(rc: RuntimeContext, sessionId: String, taskId: String) =
            tasks.find { it.taskId == taskId }

        override fun putTask(
            rc: RuntimeContext,
            taskId: String,
            subAgentId: String,
            sessionId: String,
            spec: TaskRunSpec,
        ): BackgroundTask = throw UnsupportedOperationException()

        override fun removeTask(rc: RuntimeContext, sessionId: String, taskId: String) {}
        override fun clear() {}
        override fun listTasks(rc: RuntimeContext, sessionId: String, filter: TaskStatus?) = tasks
        override fun cancelTask(rc: RuntimeContext, sessionId: String, taskId: String) = false
    }

    private fun nonTerminalTask(taskId: String) =
        BackgroundTask(taskId, "discovery", CompletableFuture())

    private val noopNext = Function<ActingInput, Flux<AgentEvent>> { Flux.empty() }

    @Test
    fun `批次中没有委派工具调用时onActing直接透传不改变计数`() {
        val guard = DelegationConcurrencyGuard()
        val middleware = DelegationBudgetMiddleware(runtimeProperties(), guard, FixedTaskRepositoryProvider(null))
        val input = ActingInput(listOf(toolCall("list_repositories")))

        middleware.onActing(STUB_AGENT, ctx("session-1"), input, noopNext).blockLast()

        assertEquals(0, guard.inFlight("session-1"))
    }

    @Test
    fun `onActing在执行期间累加委派计数结束后释放`() {
        val guard = DelegationConcurrencyGuard()
        val middleware = DelegationBudgetMiddleware(runtimeProperties(), guard, FixedTaskRepositoryProvider(null))
        val input = ActingInput(listOf(toolCall("agent_spawn"), toolCall("agent_send"), toolCall("list_repositories")))

        var inFlightDuringExecution = -1
        val captureNext = Function<ActingInput, Flux<AgentEvent>> {
            inFlightDuringExecution = guard.inFlight("session-1")
            Flux.empty()
        }

        middleware.onActing(STUB_AGENT, ctx("session-1"), input, captureNext).blockLast()

        assertEquals(2, inFlightDuringExecution)
        assertEquals(0, guard.inFlight("session-1")) { "批次结束后应该释放计数" }
    }

    @Test
    fun `onActing在next抛异常时仍会释放计数`() {
        val guard = DelegationConcurrencyGuard()
        val middleware = DelegationBudgetMiddleware(runtimeProperties(), guard, FixedTaskRepositoryProvider(null))
        val input = ActingInput(listOf(toolCall("agent_spawn")))
        val failingNext = Function<ActingInput, Flux<AgentEvent>> { Flux.error(IllegalStateException("boom")) }

        val flux = middleware.onActing(STUB_AGENT, ctx("session-1"), input, failingNext)
        assertTrue(
            runCatching { flux.blockLast() }.isFailure,
        )
        assertEquals(0, guard.inFlight("session-1"))
    }

    @Test
    fun `sessionId为空时onActing直接透传`() {
        val guard = DelegationConcurrencyGuard()
        val middleware = DelegationBudgetMiddleware(runtimeProperties(), guard, FixedTaskRepositoryProvider(null))
        val input = ActingInput(listOf(toolCall("agent_spawn")))

        middleware.onActing(STUB_AGENT, ctx(null), input, noopNext).blockLast()

        assertEquals(0, guard.inFlight(""))
    }

    @Test
    fun `coordinator未开启时onReasoning直接透传`() {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 5)
        val middleware = DelegationBudgetMiddleware(
            runtimeProperties(coordinatorEnabled = false, maxParallelDelegations = 1),
            guard,
            FixedTaskRepositoryProvider(null),
        )
        val input = ReasoningInput(listOf(userMsg("hi")), emptyList(), null)

        var received: ReasoningInput? = null
        val captureNext = Function<ReasoningInput, Flux<AgentEvent>> {
            received = it
            Flux.empty()
        }

        middleware.onReasoning(STUB_AGENT, ctx("session-1"), input, captureNext).blockLast()

        assertSame(input, received)
    }

    @Test
    fun `活跃委派数未达预算时onReasoning透传不注入提醒`() {
        val guard = DelegationConcurrencyGuard()
        val middleware = DelegationBudgetMiddleware(
            runtimeProperties(maxParallelDelegations = 3),
            guard,
            FixedTaskRepositoryProvider(FakeTaskRepository(listOf(nonTerminalTask("t1")))),
        )
        val input = ReasoningInput(listOf(userMsg("hi")), emptyList(), null)

        var received: ReasoningInput? = null
        val captureNext = Function<ReasoningInput, Flux<AgentEvent>> {
            received = it
            Flux.empty()
        }

        middleware.onReasoning(STUB_AGENT, ctx("session-1"), input, captureNext).blockLast()

        assertSame(input, received)
        assertEquals(1, received?.messages()?.size)
    }

    @Test
    fun `活跃委派数达到预算时onReasoning注入系统提醒`() {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 1)
        val middleware = DelegationBudgetMiddleware(
            runtimeProperties(maxParallelDelegations = 2),
            guard,
            FixedTaskRepositoryProvider(FakeTaskRepository(listOf(nonTerminalTask("t1")))),
        )
        val input = ReasoningInput(listOf(userMsg("hi")), emptyList(), null)

        var received: ReasoningInput? = null
        val captureNext = Function<ReasoningInput, Flux<AgentEvent>> {
            received = it
            Flux.empty()
        }

        middleware.onReasoning(STUB_AGENT, ctx("session-1"), input, captureNext).blockLast()

        val messages = received?.messages()
        assertEquals(2, messages?.size)
        val reminder = messages?.last()
        assertTrue(reminder?.getTextContent()?.contains("system-reminder") == true)
        assertTrue(reminder?.getTextContent()?.contains("active delegation") == true)
        assertEquals(true, reminder?.metadata?.get(Msg.METADATA_SYNTHETIC))
        assertEquals("delegation_budget", reminder?.metadata?.get(Msg.METADATA_REMINDER_KIND))
    }

    @Test
    fun `已终态的后台任务不计入活跃预算`() {
        val guard = DelegationConcurrencyGuard()
        val completed = BackgroundTask("t1", "discovery", CompletableFuture.completedFuture("done"))
        val middleware = DelegationBudgetMiddleware(
            runtimeProperties(maxParallelDelegations = 1),
            guard,
            FixedTaskRepositoryProvider(FakeTaskRepository(listOf(completed))),
        )
        val input = ReasoningInput(listOf(userMsg("hi")), emptyList(), null)

        var received: ReasoningInput? = null
        val captureNext = Function<ReasoningInput, Flux<AgentEvent>> {
            received = it
            Flux.empty()
        }

        middleware.onReasoning(STUB_AGENT, ctx("session-1"), input, captureNext).blockLast()

        assertEquals(1, received?.messages()?.size)
        assertTrue(completed.taskStatus.isTerminal)
    }

    private fun userMsg(text: String): Msg = Msg.builder()
        .role(MsgRole.USER)
        .name("user")
        .content(TextBlock.builder().text(text).build())
        .build()
}
