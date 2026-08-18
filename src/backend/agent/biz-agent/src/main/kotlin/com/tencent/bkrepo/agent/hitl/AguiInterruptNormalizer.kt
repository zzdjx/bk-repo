/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import io.agentscope.core.agui.event.AguiEvent
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 补齐 AG-UI interrupt 必填字段，避免 @ag-ui/client 对 null responseSchema/expiresAt 校验失败。
 *
 * AG-UI 规范中二者可选，但客户端 Zod schema 在 outcome.interrupts[] 下要求 object/string。
 */
@Component
class AguiInterruptNormalizer(
    private val frontendToolCatalog: FrontendToolCatalog,
) {

    fun normalizeEvent(event: AguiEvent, interruptTtl: Duration): AguiEvent {
        if (event !is AguiEvent.RunFinished) {
            return event
        }
        return normalizeRunFinished(event, interruptTtl)
    }

    fun normalizeRunFinished(event: AguiEvent.RunFinished, interruptTtl: Duration): AguiEvent.RunFinished {
        val outcome = event.outcome()
        if (outcome !is AguiEvent.RunFinishedInterruptOutcome) {
            return event
        }
        val normalized = outcome.interrupts().map { normalizeInterrupt(it, interruptTtl) }
        return AguiEvent.RunFinished(
            event.threadId(),
            event.runId(),
            event.result(),
            AguiEvent.RunFinishedInterruptOutcome(normalized),
        )
    }

    fun normalizeInterrupt(
        interrupt: AguiEvent.Interrupt,
        interruptTtl: Duration,
        toolName: String? = null,
        requiresApproval: Boolean? = null,
    ): AguiEvent.Interrupt {
        val schema = interrupt.responseSchema() as? Map<String, Any?>
        val approval = requiresApproval ?: requiresApproval(toolName, interrupt, schema)
        val responseSchema = when {
            !schema.isNullOrEmpty() -> schema
            approval -> APPROVAL_RESPONSE_SCHEMA
            else -> GENERIC_OBJECT_SCHEMA
        }
        val expiresAt = interrupt.expiresAt()?.takeIf { it.isNotBlank() } ?: defaultExpiresAt(interruptTtl)
        return AguiEvent.Interrupt(
            interrupt.id(),
            interrupt.reason(),
            interrupt.message(),
            interrupt.toolCallId(),
            responseSchema,
            expiresAt,
            interrupt.metadata(),
        )
    }

    fun normalizeSnapshot(snapshot: PendingInterruptSnapshot, interruptTtl: Duration): PendingInterruptSnapshot {
        val approval = requiresApproval(snapshot.toolName, snapshot.metadata, snapshot.responseSchema)
            || snapshot.requiresApproval
        val responseSchema = when {
            !snapshot.responseSchema.isNullOrEmpty() -> snapshot.responseSchema
            approval -> APPROVAL_RESPONSE_SCHEMA
            else -> GENERIC_OBJECT_SCHEMA
        }
        val expiresAt = snapshot.expiresAt?.takeIf { it.isNotBlank() } ?: defaultExpiresAt(interruptTtl)
        return snapshot.copy(
            responseSchema = responseSchema,
            expiresAt = expiresAt,
            requiresApproval = approval || hasApprovedSchema(responseSchema),
        )
    }

    fun hasApprovedSchema(responseSchema: Any?): Boolean {
        if (responseSchema !is Map<*, *>) return false
        val properties = responseSchema["properties"] as? Map<*, *> ?: return false
        return properties.containsKey("approved")
    }

    private fun requiresApproval(
        toolName: String?,
        interrupt: AguiEvent.Interrupt,
        responseSchema: Map<String, Any?>?,
    ): Boolean = requiresApproval(toolName, interrupt.metadata() as? Map<String, Any?>, responseSchema)

    private fun requiresApproval(
        toolName: String?,
        metadata: Map<String, Any?>?,
        responseSchema: Any?,
    ): Boolean {
        if (hasApprovedSchema(responseSchema)) return true
        if (isPermissionConfirmMetadata(metadata)) return true
        if (!toolName.isNullOrBlank() && frontendToolCatalog.isWriteTool(toolName)) return true
        return false
    }

    private fun isPermissionConfirmMetadata(metadata: Map<String, Any?>?): Boolean =
        metadata?.get("agentscope.interruptKind") == "permission_confirm"

    private fun defaultExpiresAt(ttl: Duration): String = Instant.now().plus(ttl).toString()

    companion object {
        private val GENERIC_OBJECT_SCHEMA: Map<String, Any?> = mapOf("type" to "object")

        private val APPROVAL_RESPONSE_SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "approved" to mapOf("type" to "boolean"),
            ),
            "required" to listOf("approved"),
        )
    }
}
