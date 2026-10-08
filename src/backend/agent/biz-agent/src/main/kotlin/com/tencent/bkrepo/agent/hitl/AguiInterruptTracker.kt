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
 * ## 协调者原生 PERMISSION_ASKING 的 AG-UI 转换
 *
 * `agentscope-extensions-agui` 2.0.4（#2495）起，`PermissionEngine` 判定 ASK 时会原生产出
 * `RunFinished(interrupt)`，且字段比我们能合成的更全（带 `approved` / `editedArgs` / `reason` 的
 * responseSchema、message、expiresAt），`reason` 为 `tool_call`、权限确认的性质记在
 * `metadata["agentscope.interruptKind"]` 上。[enrichInterrupt] 只需补 toolName/toolInput。
 *
 * 2.0.1 没有这条转换：`AgentLifecycleEventConverter` 只在 `AgentResultEvent` 携带
 * `GenerateReason.TOOL_SUSPENDED` 时才合成 interrupt，而 [RequireUserConfirmEvent] 没有任何内置策略
 * 转换，只会原样降级为 `AguiEvent.Raw`，随后以 `RunFinished(outcome=null)`（等价于"成功"）收尾——
 * 客户端根本看不到确认弹窗。[buildPermissionAskOutcome] 是为此写的补丁，在 2.0.4 上已经走不到了
 * （`outcome` 不再为 null）。保留它是因为失败模式太差：真漏了这条转换，用户看到的是"点了没反应"，
 * 而不是报错。等原生路径在真实会话里验证过一轮再删。
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
