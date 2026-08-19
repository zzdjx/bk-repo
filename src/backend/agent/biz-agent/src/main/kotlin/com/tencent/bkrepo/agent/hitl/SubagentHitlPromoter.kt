/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.event.AgentResultEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.message.GenerateReason
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 将子 Agent 的 [RequireUserConfirmEvent] / TOOL_SUSPENDED 结果上冒为标准 AG-UI `RUN_FINISHED(interrupt)`。
 *
 * AgentScope 2.0.1 默认（[io.agentscope.core.agui.adapter.strategy.SubagentEventConverter]）会把子 Agent
 * 的事件降级为 `AguiEvent.Custom(subagent.*)`。本项目通过
 * [io.agentscope.core.agui.adapter.AguiAdapterConfig.Builder.emitSubagentEventsAsNative] 显式关闭该降级，
 * 子 Agent 事件改走原生 [AguiEvent.Raw] / [AguiEvent.ToolCallStart]（携带 `source`），因此这里只处理原生事件，
 * 不再兼容已关闭的 Custom 事件降级路径。父 run 在 `agent_spawn` 同步阻塞期间也不会自然发出 interrupt 终态，
 * 需要本类合成一次 `RUN_FINISHED(interrupt)`。
 */
@Component
class SubagentHitlPromoter {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class PendingToolCall(
        val toolCallId: String,
        val toolName: String,
        val source: String,
        val toolInput: Map<String, Any?>? = null,
    )

    class State {
        var promoted = false
    }

    fun onEvent(event: AguiEvent, interruptState: AguiInterruptTracker.State, state: State) {
        if (state.promoted) return
        when (event) {
            is AguiEvent.Raw -> onRawEvent(event, interruptState)
            is AguiEvent.ToolCallStart -> trackNativeToolCall(event, interruptState)
            else -> Unit
        }
    }

    fun buildRunFinishedIfNeeded(
        event: AguiEvent,
        threadId: String,
        runId: String,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ): AguiEvent.RunFinished? {
        if (state.promoted) return null
        return when (event) {
            is AguiEvent.Raw -> buildFromRawEvent(event, threadId, runId, state)
            else -> null
        }
    }

    private fun onRawEvent(
        event: AguiEvent.Raw,
        interruptState: AguiInterruptTracker.State,
    ) {
        when (val agentEvent = event.event()) {
            is RequireUserConfirmEvent -> trackRequireConfirm(event, agentEvent, interruptState)
            is AgentResultEvent -> onAgentResultRaw(event, agentEvent, interruptState)
            else -> Unit
        }
    }

    private fun buildFromRawEvent(
        event: AguiEvent.Raw,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        return when (val agentEvent = event.event()) {
            is RequireUserConfirmEvent -> buildFromRawRequireConfirm(event, agentEvent, threadId, runId, state)
            is AgentResultEvent -> buildFromToolSuspended(event, threadId, runId, state)
            else -> null
        }
    }

    private fun buildFromRawRequireConfirm(
        event: AguiEvent.Raw,
        confirmEvent: RequireUserConfirmEvent,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        val source = event.source()?.takeIf { it.isNotBlank() } ?: return null
        val targets = confirmEvent.toolCalls.mapNotNull { tool ->
            val toolCallId = tool.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toolName = tool.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PendingToolCall(toolCallId, toolName, source, tool.input?.takeIf { it.isNotEmpty() })
        }
        if (targets.isEmpty()) return null

        state.promoted = true
        logger.info(
            "promoted subagent permission HITL from raw RequireUserConfirmEvent: source={} toolCalls={}",
            source,
            targets.map { it.toolName },
        )
        val interrupts = targets.map { buildPermissionInterrupt(it) }
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
        logger.info(
            "promoted subagent TOOL_SUSPENDED HITL: source={} toolCalls={}",
            event.source(),
            interrupts.map { it.metadata()["toolName"] },
        )
        return runFinished(threadId, runId, interrupts)
    }

    private fun trackRequireConfirm(
        event: AguiEvent.Raw,
        confirmEvent: RequireUserConfirmEvent,
        interruptState: AguiInterruptTracker.State,
    ) {
        val source = event.source()?.takeIf { it.isNotBlank() } ?: return
        confirmEvent.toolCalls.forEach { tool ->
            val toolCallId = tool.id?.takeIf { it.isNotBlank() } ?: return@forEach
            val toolName = tool.name?.takeIf { it.isNotBlank() } ?: return@forEach
            trackPending(
                PendingToolCall(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    source = source,
                    toolInput = tool.input?.takeIf { it.isNotEmpty() },
                ),
                interruptState,
            )
        }
    }

    private fun onAgentResultRaw(
        event: AguiEvent.Raw,
        agentEvent: AgentResultEvent,
        interruptState: AguiInterruptTracker.State,
    ) {
        if (event.source().isNullOrBlank()) return
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

    private fun trackNativeToolCall(
        event: AguiEvent.ToolCallStart,
        interruptState: AguiInterruptTracker.State,
    ) {
        val toolCallId = event.toolCallId()?.takeIf { it.isNotBlank() } ?: return
        val toolName = event.toolCallName()?.takeIf { it.isNotBlank() } ?: return
        if (toolName in AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS) return

        val source = subagentSourceFromRawEvent(event.rawEvent())
        trackPending(PendingToolCall(toolCallId, toolName, source), interruptState)
    }

    private fun subagentSourceFromRawEvent(rawEvent: Any?): String {
        if (rawEvent !is io.agentscope.core.event.AgentEvent) return ""
        return rawEvent.source?.takeIf { it.isNotBlank() } ?: ""
    }

    /** 供 [AguiInterruptTracker] 后续按 toolCallId 反查 toolName 用；本类自身不再依赖此映射做 target 解析。 */
    private fun trackPending(pending: PendingToolCall, interruptState: AguiInterruptTracker.State) {
        interruptState.toolNameByCallId[pending.toolCallId] = pending.toolName
    }

    private fun buildPermissionInterrupt(pending: PendingToolCall): AguiEvent.Interrupt {
        val metadata = linkedMapOf<String, Any?>(
            "agentscope.interruptKind" to "permission_confirm",
            "toolName" to pending.toolName,
            "subagentSource" to pending.source,
        )
        pending.toolInput?.let { metadata["toolInput"] = it }
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
        private const val REASON_PERMISSION_CONFIRM = "permission_confirm"
        private const val REASON_TOOL_CALL = "tool_call"
    }
}
