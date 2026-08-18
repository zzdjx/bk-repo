/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.event.AgentResultEvent
import io.agentscope.core.message.GenerateReason
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import org.springframework.stereotype.Component

/**
 * 将子 Agent 的 `subagent.require_confirm` 等 Custom 事件上冒为标准 AG-UI `RUN_FINISHED(interrupt)`。
 *
 * AgentScope 2.0.1 的 [io.agentscope.core.agui.adapter.strategy.SubagentEventConverter]
 * 会把子 Agent 的 [io.agentscope.core.event.RequireUserConfirmEvent] 降级为
 * `AguiEvent.Custom(subagent.require_confirm)`，不会写入 [AguiStreamContext.pendingInterrupts]。
 * 父 run 在 `agent_spawn` 同步阻塞期间也不会自然发出 interrupt 终态。
 */
@Component
class SubagentHitlPromoter {

    data class PendingToolCall(
        val toolCallId: String,
        val toolName: String,
        val source: String,
        val toolInput: Map<String, Any?>? = null,
    )

    class State {
        val pendingBySource = mutableMapOf<String, MutableList<PendingToolCall>>()
        var promoted = false
    }

    fun onEvent(event: AguiEvent, interruptState: AguiInterruptTracker.State, state: State) {
        if (state.promoted) return
        when (event) {
            is AguiEvent.Custom -> onCustomEvent(event, interruptState, state)
            is AguiEvent.Raw -> onRawEvent(event, interruptState, state)
            else -> Unit
        }
    }

    fun buildRunFinishedIfNeeded(
        event: AguiEvent,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        if (state.promoted) return null
        return when (event) {
            is AguiEvent.Custom -> buildFromRequireConfirm(event, threadId, runId, state)
            is AguiEvent.Raw -> buildFromToolSuspended(event, threadId, runId, state)
            else -> null
        }
    }

    private fun onCustomEvent(
        event: AguiEvent.Custom,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        when (event.name()) {
            NAME_TOOL_CALL -> trackToolCall(event, interruptState, state)
        }
    }

    private fun onRawEvent(
        event: AguiEvent.Raw,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        if (event.source().isNullOrBlank()) return
        val agentEvent = event.event() as? AgentResultEvent ?: return
        val msg = agentEvent.result ?: return
        if (msg.generateReason != GenerateReason.TOOL_SUSPENDED) return

        val toolUses = msg.getContentBlocks(ToolUseBlock::class.java).orEmpty()
        val toolResults = msg.getContentBlocks(ToolResultBlock::class.java).orEmpty()
        val useById = toolUses.associateBy { it.id }
        for (result in toolResults) {
            if (!result.isSuspended) continue
            val callId = result.id ?: continue
            val use = useById[callId]
            val toolName = use?.name ?: result.name ?: continue
            interruptState.toolNameByCallId[callId] = toolName
            use?.input?.let { input ->
                interruptState.argsBufferByCallId[callId] = StringBuilder(input.toString())
            }
        }
    }

    private fun buildFromRequireConfirm(
        event: AguiEvent.Custom,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        if (event.name() != NAME_REQUIRE_CONFIRM) return null

        val payload = event.value() as? Map<*, *> ?: return null
        val source = payload["source"] as? String ?: return null
        val toolCallCount = (payload["toolCallCount"] as? Number)?.toInt() ?: 1
        val pending = state.pendingBySource[source].orEmpty()
        if (pending.isEmpty()) return null

        val targets = pending.takeLast(toolCallCount.coerceAtLeast(1))
        val interrupts = targets.map { pendingCall -> buildPermissionInterrupt(pendingCall) }
        state.promoted = true
        return runFinished(threadId, runId, interrupts)
    }

    private fun buildFromToolSuspended(
        event: AguiEvent.Raw,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        if (event.source().isNullOrBlank()) return null
        val agentEvent = event.event() as? AgentResultEvent ?: return null
        val msg = agentEvent.result ?: return null
        if (msg.generateReason != GenerateReason.TOOL_SUSPENDED) return null

        val toolUses = msg.getContentBlocks(ToolUseBlock::class.java).orEmpty()
        val toolResults = msg.getContentBlocks(ToolResultBlock::class.java).orEmpty()
        val useById = toolUses.associateBy { it.id }
        val interrupts = toolResults.mapNotNull { result ->
            if (!result.isSuspended) return@mapNotNull null
            val callId = result.id ?: return@mapNotNull null
            val use = useById[callId]
            buildToolCallInterrupt(callId, use, result, event.source())
        }
        if (interrupts.isEmpty()) return null

        state.promoted = true
        return runFinished(threadId, runId, interrupts)
    }

    private fun trackToolCall(
        event: AguiEvent.Custom,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        val payload = event.value() as? Map<*, *> ?: return
        if (payload["type"] != TYPE_TOOL_CALL_START) return

        val source = payload["source"] as? String ?: return
        val toolCallId = payload["toolCallId"] as? String ?: return
        val toolName = payload["toolName"] as? String ?: return
        if (toolCallId.isBlank() || toolName.isBlank()) return

        interruptState.toolNameByCallId[toolCallId] = toolName
        state.pendingBySource.computeIfAbsent(source) { mutableListOf() }
            .add(PendingToolCall(toolCallId, toolName, source))
    }

    private fun buildPermissionInterrupt(pending: PendingToolCall): AguiEvent.Interrupt {
        val metadata = linkedMapOf<String, Any?>(
            "agentscope.interruptKind" to "permission_confirm",
            "toolName" to pending.toolName,
            "subagentSource" to pending.source,
        )
        return AguiEvent.Interrupt(
            interruptId(pending.toolCallId, REASON_PERMISSION_CONFIRM),
            REASON_PERMISSION_CONFIRM,
            confirmMessage(pending.toolName),
            pending.toolCallId,
            null,
            null,
            metadata,
        )
    }

    private fun buildToolCallInterrupt(
        toolCallId: String,
        use: ToolUseBlock?,
        result: ToolResultBlock,
        source: String?,
    ): AguiEvent.Interrupt {
        val toolName = use?.name?.takeIf { it.isNotBlank() } ?: result.name.orEmpty()
        val metadata = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "subagentSource" to source,
        )
        use?.input?.takeIf { it.isNotEmpty() }?.let { metadata["toolInput"] = it }
        return AguiEvent.Interrupt(
            interruptId(toolCallId, REASON_TOOL_CALL),
            REASON_TOOL_CALL,
            result.output.joinToString("") { block -> block.toString() },
            toolCallId,
            null,
            null,
            metadata,
        )
    }

    private fun runFinished(
        threadId: String,
        runId: String,
        interrupts: List<AguiEvent.Interrupt>,
    ): AguiEvent.RunFinished = AguiEvent.RunFinished(
        threadId,
        runId,
        null,
        AguiEvent.RunFinishedInterruptOutcome(interrupts),
    )

    private fun interruptId(toolCallId: String, reason: String): String = "$reason-$toolCallId"

    private fun confirmMessage(toolName: String): String = "确认执行 $toolName？"

    companion object {
        private const val NAME_TOOL_CALL = "subagent.tool_call"
        private const val NAME_REQUIRE_CONFIRM = "subagent.require_confirm"
        private const val TYPE_TOOL_CALL_START = "TOOL_CALL_START"
        private const val REASON_PERMISSION_CONFIRM = "permission_confirm"
        private const val REASON_TOOL_CALL = "tool_call"
    }
}
