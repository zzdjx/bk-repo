/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import org.springframework.stereotype.Component

/**
 * 跟踪 TOOL_CALL 与 RunFinished interrupt，供 pending interrupt 持久化。
 *
 * 本类是单例 Spring bean，同一实例会被所有并发 SSE run 共享，因此状态必须以 [State] 的形式
 * 由调用方每次 run 各自持有，不能存为本类的实例字段（否则并发 run 之间会互相污染 toolCallId 映射）。
 *
 * ## 协调者原生 PERMISSION_ASKING 缺失的 AG-UI 转换补丁
 *
 * `agentscope-extensions-agui` 2.0.1 的 `AgentLifecycleEventConverter` 只在 `AgentResultEvent`
 * 携带 `GenerateReason.TOOL_SUSPENDED` 时才合成 `RunFinished(interrupt)`；`PermissionEngine` 判定
 * ASK 时发出的 [RequireUserConfirmEvent] 没有任何内置策略转换，只会原样降级为
 * `AguiEvent.Raw(source=null, event=RequireUserConfirmEvent)`，随后框架自行发出
 * `Raw(RequestStopEvent)` 并以 `RunFinished(outcome=null)`（等价于"成功"）收尾——不会让客户端看到
 * 确认弹窗。这在拍平前不是问题：`client` 子 Agent 走的是 Custom 事件降级路径，由（已删除的）
 * `SubagentHitlPromoter` 单独促升；子 Agent拍平到协调者自身后，协调者的写工具第一轮 ASK 就直接暴露了
 * 这个此前从未被真正走到的框架空档。这里用与 [ToolCallStart]/[ToolCallArgs] 相同的
 * "跟踪 -> 在终态事件时回填" 结构补上：记录 [RequireUserConfirmEvent.toolCalls] 里的 toolCallId，
 * 终态 `RunFinished(outcome=null)` 时若存在待确认的 toolCallId，合成一个 `reason=permission_confirm`
 * 的 [AguiEvent.RunFinishedInterruptOutcome]，复用已有的 [enrichInterrupt] 补齐 toolName/toolInput。
 */
@Component
class AguiInterruptTracker(
    private val interruptNormalizer: AguiInterruptNormalizer,
    private val runtimeProperties: EffectiveAgentRuntimeProperties,
    private val objectMapper: ObjectMapper,
) {

    /** 单次 run 的 toolCallId -> toolName 映射，随 run 生命周期由调用方创建与持有。 */
    class State {
        val toolNameByCallId = mutableMapOf<String, String>()
        val argsBufferByCallId = mutableMapOf<String, StringBuilder>()
        val pendingPermissionAskToolCallIds = linkedSetOf<String>()
    }

    fun onEvent(event: AguiEvent, state: State) {
        when (event) {
            is AguiEvent.ToolCallStart -> {
                state.toolNameByCallId[event.toolCallId()] = event.toolCallName()
            }
            is AguiEvent.ToolCallArgs -> {
                val callId = event.toolCallId()?.takeIf { it.isNotBlank() } ?: return
                val delta = event.delta()?.takeIf { it.isNotBlank() } ?: return
                state.argsBufferByCallId.computeIfAbsent(callId) { StringBuilder() }.append(delta)
            }
            is AguiEvent.Raw -> trackPermissionAsk(event, state)
            else -> Unit
        }
    }

    private fun trackPermissionAsk(event: AguiEvent.Raw, state: State) {
        val confirmEvent = event.event() as? RequireUserConfirmEvent ?: return
        confirmEvent.toolCalls.forEach { toolCall ->
            toolCall.id?.takeIf { it.isNotBlank() }?.let { state.pendingPermissionAskToolCallIds.add(it) }
        }
    }

    fun enrichEvent(event: AguiEvent, state: State): AguiEvent {
        if (event !is AguiEvent.RunFinished) {
            return event
        }
        return enrichRunFinished(event, state)
    }

    fun captureSuspendedSession(runId: String, event: AguiEvent.RunFinished, state: State): PendingInterruptSession? {
        val enriched = enrichRunFinished(event, state)
        val outcome = enriched.outcome()
        if (outcome !is AguiEvent.RunFinishedInterruptOutcome) {
            return null
        }
        val interrupts = outcome.interrupts().mapNotNull { interrupt -> toSnapshot(interrupt, state) }
        if (interrupts.isEmpty()) {
            return null
        }
        return PendingInterruptSession(originRunId = runId, interrupts = interrupts)
    }

    private fun enrichRunFinished(event: AguiEvent.RunFinished, state: State): AguiEvent.RunFinished {
        val outcome = event.outcome() ?: buildPermissionAskOutcome(state)
        if (outcome !is AguiEvent.RunFinishedInterruptOutcome) {
            return event
        }
        val enriched = outcome.interrupts().map { interrupt -> enrichInterrupt(interrupt, state) }
        return AguiEvent.RunFinished(
            event.threadId(),
            event.runId(),
            event.result(),
            AguiEvent.RunFinishedInterruptOutcome(enriched),
        )
    }

    /**
     * 框架原生 `outcome=null`（等价于"成功"）在存在待确认 toolCallId 时是误判：
     * `RequireUserConfirmEvent` 从未被内置策略转换为 interrupt，见类注释。这里补一个骨架
     * [AguiEvent.Interrupt]（仅 id/reason/toolCallId + `permission_confirm` 元数据标记），
     * 剩余字段（toolName/toolInput/responseSchema/expiresAt）交由后续 [enrichInterrupt] 与
     * [AguiInterruptNormalizer] 按既有链路统一补齐，不在这里重复实现。
     */
    private fun buildPermissionAskOutcome(state: State): AguiEvent.RunFinishedOutcome? {
        if (state.pendingPermissionAskToolCallIds.isEmpty()) return null
        val interrupts = state.pendingPermissionAskToolCallIds.map { toolCallId ->
            AguiEvent.Interrupt(
                "$REASON_PERMISSION_CONFIRM-$toolCallId",
                REASON_PERMISSION_CONFIRM,
                null,
                toolCallId,
                null,
                null,
                mapOf("agentscope.interruptKind" to REASON_PERMISSION_CONFIRM),
            )
        }
        return AguiEvent.RunFinishedInterruptOutcome(interrupts)
    }

    @Suppress("UNCHECKED_CAST")
    private fun enrichInterrupt(interrupt: AguiEvent.Interrupt, state: State): AguiEvent.Interrupt {
        val toolCallId = interrupt.toolCallId()?.takeIf { it.isNotBlank() }
        val toolName = resolveToolName(interrupt, state, toolCallId)
        val toolInput = toolInput(state, toolCallId)
        val metadata = mergeToolMetadata(interrupt.metadata() as? Map<String, Any?>, toolName, toolInput)
        val requiresApproval =
            interruptNormalizer.requiresApproval(interrupt.reason(), toolName, metadata, interrupt.responseSchema())
        return interruptNormalizer.normalizeInterrupt(
            AguiEvent.Interrupt(
                interrupt.id(),
                interrupt.reason(),
                interrupt.message(),
                toolCallId,
                interrupt.responseSchema(),
                interrupt.expiresAt(),
                metadata,
            ),
            runtimeProperties.activeRunTtl,
            toolName,
            requiresApproval,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toSnapshot(interrupt: AguiEvent.Interrupt, state: State): PendingInterruptSnapshot? {
        val id = interrupt.id()?.takeIf { it.isNotBlank() } ?: return null
        val toolCallId = interrupt.toolCallId()?.takeIf { it.isNotBlank() }
        val toolName = resolveToolName(interrupt, state, toolCallId)
        if (toolName in AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS) {
            return null
        }
        val toolInput = toolInput(state, toolCallId)
        val metadata = mergeToolMetadata(interrupt.metadata() as? Map<String, Any?>, toolName, toolInput)
        val responseSchema = interrupt.responseSchema() as? Map<String, Any?>
        val requiresApproval =
            interruptNormalizer.requiresApproval(interrupt.reason(), toolName, metadata, responseSchema)
        val snapshot = PendingInterruptSnapshot(
            id = id,
            reason = interrupt.reason().orEmpty(),
            toolCallId = toolCallId,
            toolName = toolName,
            requiresApproval = requiresApproval,
            message = interrupt.message(),
            responseSchema = responseSchema,
            expiresAt = interrupt.expiresAt(),
            metadata = metadata,
        )
        return interruptNormalizer.normalizeSnapshot(snapshot, runtimeProperties.activeRunTtl)
    }

    private fun resolveToolName(
        interrupt: AguiEvent.Interrupt,
        state: State,
        toolCallId: String?,
    ): String? {
        toolCallId?.let { state.toolNameByCallId[it] }?.takeIf { it.isNotBlank() }?.let { return it }
        val fromMeta = (interrupt.metadata() as? Map<*, *>)?.get("toolName")
        return fromMeta as? String
    }

    private fun toolInput(state: State, toolCallId: String?): Map<String, Any?>? {
        if (toolCallId.isNullOrBlank()) return null
        val raw = state.argsBufferByCallId[toolCallId]?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        return try {
            objectMapper.readValue(raw, TOOL_INPUT_TYPE)
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeToolMetadata(
        metadata: Map<String, Any?>?,
        toolName: String?,
        toolInput: Map<String, Any?>?,
    ): Map<String, Any?>? {
        if (toolName.isNullOrBlank() && toolInput == null) {
            return metadata
        }
        val merged = LinkedHashMap<String, Any?>()
        metadata?.let { merged.putAll(it) }
        if (!toolName.isNullOrBlank()) {
            merged["toolName"] = toolName
        }
        if (toolInput != null) {
            merged["toolInput"] = toolInput
        }
        return merged
    }

    companion object {
        private const val REASON_PERMISSION_CONFIRM = "permission_confirm"
        private val TOOL_INPUT_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
