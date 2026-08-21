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
import org.springframework.stereotype.Component

/**
 * 跟踪 TOOL_CALL 与 RunFinished interrupt，供 pending interrupt 持久化。
 *
 * 本类是单例 Spring bean，同一实例会被所有并发 SSE run 共享，因此状态必须以 [State] 的形式
 * 由调用方每次 run 各自持有，不能存为本类的实例字段（否则并发 run 之间会互相污染 toolCallId 映射）。
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
            else -> Unit
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
        val outcome = event.outcome()
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
        private val TOOL_INPUT_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
