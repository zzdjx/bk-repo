/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.message.ToolCallState
import org.springframework.stereotype.Component

/**
 * 将 AG-UI approval resume 转为 AgentScope [ConfirmResult]，供 Permission HITL 恢复。
 *
 * 标准 [io.agentscope.core.agui.converter.AguiMessageConverter] 会把 resume 落成 ToolResult Msg，
 * 无法解除 PERMISSION_ASKING；需在 [PermissionConfirmResumeMiddleware] 注入
 * [io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS]。
 *
 * 曾经存在一条子代理级确认的特殊路由（写操作实际 ASKING 发生在 `client` 声明式子 Agent 内部，需要
 * 绕开协调者直接重新驱动子代理会话）。`client` 本地工具已拍平到协调者自身（不再是子 Agent，见
 * [com.tencent.bkrepo.agent.config.AgentHarnessConfigurer]），ASKING 现在始终挂在协调者自己身上，
 * 因此这里只保留唯一一条路径：全部走 [confirmResults] + [PermissionConfirmResumeMiddleware]。
 */
@Component
class AguiPermissionResumeAdapter(
    private val interruptStateRepository: AgentInterruptStateRepository,
) {

    data class AdaptedRun(
        val input: RunAgentInput,
        val confirmResults: List<ConfirmResult>,
    )

    fun adapt(input: RunAgentInput): AdaptedRun {
        if (!input.hasResume()) {
            return AdaptedRun(input, emptyList())
        }
        val pending = interruptStateRepository.getPendingInterrupt(input.threadId)
            ?: return AdaptedRun(input, emptyList())

        val approvalById = pending.interrupts
            .filter { it.requiresApproval }
            .associateBy { it.id }
        if (approvalById.isEmpty()) {
            return AdaptedRun(input, emptyList())
        }

        val confirmResults = mutableListOf<ConfirmResult>()
        val remainingResume = mutableListOf<AguiResume>()
        for (entry in input.resume) {
            val snapshot = approvalById[entry.interruptId]
            if (snapshot == null) {
                remainingResume.add(entry)
                continue
            }
            val approved = extractApproved(entry.payload) == true
            confirmResults.add(ConfirmResult(approved, buildToolUseBlock(snapshot)))
        }

        if (confirmResults.isEmpty()) {
            return AdaptedRun(input, emptyList())
        }

        val adaptedInput = if (remainingResume.size == input.resume.size) {
            input
        } else {
            RunAgentInput.builder()
                .threadId(input.threadId)
                .runId(input.runId)
                .messages(input.messages)
                .tools(input.tools)
                .context(input.context)
                .state(input.state)
                .forwardedProps(input.forwardedProps)
                .resume(remainingResume)
                .build()
        }
        return AdaptedRun(adaptedInput, confirmResults)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractApproved(payload: Any?): Boolean? {
        if (payload == null) return null
        if (payload is Boolean) return payload
        if (payload is Map<*, *>) {
            return payload["approved"] as? Boolean
        }
        return null
    }

    private fun buildToolUseBlock(snapshot: com.tencent.bkrepo.agent.session.PendingInterruptSnapshot): ToolUseBlock {
        val toolCallId = snapshot.toolCallId?.takeIf { it.isNotBlank() }
            ?: snapshot.id.substringAfterLast('-', snapshot.id)
        val toolName = snapshot.toolName.orEmpty()
        val toolInput = snapshot.metadata?.get("toolInput") as? Map<String, Any?> ?: emptyMap()
        return ToolUseBlock(
            toolCallId,
            toolName,
            toolInput,
            null,
            null,
            ToolCallState.ASKING,
        )
    }
}
