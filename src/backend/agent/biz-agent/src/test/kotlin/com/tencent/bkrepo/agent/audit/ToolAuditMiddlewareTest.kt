/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.audit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_PROJECT_ID
import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_RUN_ID
import com.tencent.bkrepo.agent.pojo.AgentToolResultState
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.Event
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.AgentInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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

/**
 * 直接单测 [ToolAuditMiddleware] 的 [io.agentscope.core.middleware.MiddlewareBase.onActing] /
 * [io.agentscope.core.middleware.MiddlewareBase.onAgent] 钩子逻辑，不经由完整的 HarnessAgent + 桩模型
 * 服务器（见 [com.tencent.bkrepo.agent.usage.UsageTrackingMiddlewareTest] 的装配式冒烟测试，工具审计
 * 已在 [com.tencent.bkrepo.agent.hitl.FlattenedWriteToolSuspensionEndToEndTest] 里有对应的接入验证）。
 */
@DisplayName("ToolAuditMiddleware单测")
class ToolAuditMiddlewareTest {

    private val runId = "run-1"
    private val threadId = "thread-1"
    private val userId = "user-1"
    private val projectId = "project-1"

    private fun runtimeContext(): RuntimeContext = RuntimeContext.builder()
        .userId(userId)
        .sessionId(threadId)
        .put(RUNTIME_CONTEXT_RUN_ID, runId)
        .put(RUNTIME_CONTEXT_PROJECT_ID, projectId)
        .build()

    @Test
    fun `放行执行的工具调用应记录PRE与SUCCESS终态`() {
        val recording = RecordingAgentToolCallRecordService()
        val middleware = ToolAuditMiddleware(recording, ObjectMapper())
        val toolCall = ToolUseBlock("call-1", "list_repositories", mapOf("projectId" to projectId))

        val events = middleware.onActing(
            agent = STUB_AGENT,
            ctx = runtimeContext(),
            input = ActingInput(listOf(toolCall)),
            next = { _ ->
                Flux.just<AgentEvent>(
                    ToolResultTextDeltaEvent("reply-1", "call-1", "list_repositories", "ok"),
                    ToolResultEndEvent("reply-1", "call-1", "list_repositories", ToolResultState.SUCCESS),
                )
            },
        )
        events.collectList().block()

        assertEquals(1, recording.calledEvents.size)
        val called = recording.calledEvents.single()
        assertEquals(runId, called.runId)
        assertEquals("call-1", called.toolCallId)
        assertEquals("list_repositories", called.toolName)
        assertTrue(called.argsDigest!!.contains("projectId"))

        assertEquals(1, recording.executedEvents.size)
        val executed = recording.executedEvents.single()
        assertEquals(AgentToolResultState.SUCCESS, executed.resultState)
        assertEquals("ok", executed.resultDigest)
        assertTrue(recording.askingEvents.isEmpty())
        assertTrue(recording.ruleDeniedEvents.isEmpty())
    }

    @Test
    fun `权限引擎判定需要询问用户时应记录ASKING`() {
        val recording = RecordingAgentToolCallRecordService()
        val middleware = ToolAuditMiddleware(recording, ObjectMapper())
        val toolCall = ToolUseBlock("call-2", "set_download_path", mapOf("path" to "/tmp"))

        val events = middleware.onActing(
            agent = STUB_AGENT,
            ctx = runtimeContext(),
            input = ActingInput(listOf(toolCall)),
            next = { _ -> Flux.just<AgentEvent>(RequireUserConfirmEvent("reply-1", listOf(toolCall))) },
        )
        events.collectList().block()

        assertEquals(1, recording.askingEvents.size)
        assertEquals(runId to "call-2", recording.askingEvents.single())
        assertTrue(recording.executedEvents.isEmpty())
    }

    @Test
    fun `命中DENY规则时应记录RULE_DENIED而不是ALLOWED`() {
        val recording = RecordingAgentToolCallRecordService()
        val middleware = ToolAuditMiddleware(recording, ObjectMapper())
        val toolCall = ToolUseBlock("call-3", "dangerous_tool", emptyMap())

        val events = middleware.onActing(
            agent = STUB_AGENT,
            ctx = runtimeContext(),
            input = ActingInput(listOf(toolCall)),
            next = { _ ->
                Flux.just<AgentEvent>(
                    ToolResultEndEvent("reply-1", "call-3", "dangerous_tool", ToolResultState.DENIED),
                )
            },
        )
        events.collectList().block()

        assertEquals(1, recording.ruleDeniedEvents.size)
        assertEquals(runId to "call-3", recording.ruleDeniedEvents.single())
        assertTrue(recording.executedEvents.isEmpty()) {
            "规则拒绝不应被当成放行执行记下来，实际=${recording.executedEvents}"
        }
    }

    @Test
    fun `没有runId的内部调用不应落审计记录`() {
        val recording = RecordingAgentToolCallRecordService()
        val middleware = ToolAuditMiddleware(recording, ObjectMapper())
        val toolCall = ToolUseBlock("call-4", "some_tool", emptyMap())
        val ctxWithoutRunId = RuntimeContext.builder().userId(userId).sessionId(threadId).build()

        val events = middleware.onActing(
            agent = STUB_AGENT,
            ctx = ctxWithoutRunId,
            input = ActingInput(listOf(toolCall)),
            next = { _ ->
                Flux.just<AgentEvent>(ToolResultEndEvent("reply-1", "call-4", "some_tool", ToolResultState.SUCCESS))
            },
        )
        events.collectList().block()

        assertTrue(recording.calledEvents.isEmpty())
        assertTrue(recording.executedEvents.isEmpty())
    }

    @Test
    fun `resume消息里携带客户端真实工具结果时应补齐终态`() {
        val recording = RecordingAgentToolCallRecordService()
        val middleware = ToolAuditMiddleware(recording, ObjectMapper())
        val toolResult = ToolResultBlock(
            "call-5",
            "set_download_path",
            listOf(),
            null,
            ToolResultState.SUCCESS,
        )
        val msg = Msg.builder().role(MsgRole.TOOL).content(toolResult).build()

        val events = middleware.onAgent(
            agent = STUB_AGENT,
            ctx = runtimeContext(),
            input = AgentInput(listOf(msg)),
            next = { _ -> Flux.empty<AgentEvent>() },
        )
        events.collectList().block()

        assertEquals(1, recording.clientReportedResultEvents.size)
        val recorded = recording.clientReportedResultEvents.single()
        assertEquals("call-5", recorded.toolCallId)
        assertEquals(AgentToolResultState.SUCCESS, recorded.resultState)
    }
}
