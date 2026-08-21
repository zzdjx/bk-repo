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
        val approval = requiresApproval
            ?: requiresApproval(interrupt.reason(), toolName, interrupt.metadata() as? Map<String, Any?>, schema)
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
        val approval = requiresApproval(snapshot.reason, snapshot.toolName, snapshot.metadata, snapshot.responseSchema)
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

    /**
     * 判断一个 interrupt 是否要求客户端弹"是/否"批准框（而不是直接执行本地工具并回传真实结果）。
     *
     * `reason == "tool_call"` 必须被显式排除在"写工具即批准"这条兜底规则之外：`tool_call` 是
     * 协调者原生 `TOOL_SUSPENDED` 产出的、含义单一的形态——"请客户端本地真正执行该工具并回传结果"，
     * `set_download_path` 等写工具也不例外，它们的"是否批准"已经在更早
     * 一轮 `reason=permission_confirm`（或显式带 `agentscope.interruptKind=permission_confirm`
     * 元数据）的 interrupt 里问过一次。如果这里对 `tool_call` 也无条件套用写工具兜底，客户端
     * `agentRunLoop.ts` 的 `isApprovalInterrupt` 判断会把它误当成第二次批准框（只回传
     * `{approved: true}`，从不调用 `executeLocalTools` 真正执行），导致写操作从始至终没有真正落地——
     * 这正是"点击确认后下载目录其实没被修改"的根因（`AguiInterruptNormalizerTest` 里旧的
     * `uses approval schema for write tools` 用例曾把这个 bug 当成期望行为断言下来，随本次修复一并
     * 更正）。写工具兜底规则只应保留给"目前还没有显式 reason/metadata 信号、但风险上必须当批准处理"
     * 的场景（例如协调者未来某天原生产出既非 `permission_confirm` 也非 `tool_call` 的写工具 ASK）。
     */
    fun requiresApproval(
        reason: String?,
        toolName: String?,
        metadata: Map<String, Any?>?,
        responseSchema: Any?,
    ): Boolean {
        if (hasApprovedSchema(responseSchema)) return true
        if (isPermissionConfirmMetadata(metadata)) return true
        if (reason == REASON_TOOL_CALL) return false
        if (!toolName.isNullOrBlank() && frontendToolCatalog.isWriteTool(toolName)) return true
        return false
    }

    private fun isPermissionConfirmMetadata(metadata: Map<String, Any?>?): Boolean =
        metadata?.get("agentscope.interruptKind") == "permission_confirm"

    private fun defaultExpiresAt(ttl: Duration): String = Instant.now().plus(ttl).toString()

    companion object {
        /** "请客户端本地真正执行该工具"。 */
        private const val REASON_TOOL_CALL = "tool_call"

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
