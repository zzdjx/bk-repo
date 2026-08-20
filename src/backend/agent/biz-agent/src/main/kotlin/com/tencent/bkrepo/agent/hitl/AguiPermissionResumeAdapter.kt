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
 * ## 子代理级确认需要额外路由，不能走 [PermissionConfirmResumeMiddleware]
 *
 * [PermissionConfirmResumeMiddleware] 把 confirmMsg 注入的是**协调者自己**这次调用的输入——只有当
 * ASKING 的工具调用本身挂在协调者自己身上时才有效。而 `set_download_path` 这类写操作实际 ASKING 在
 * `client` 等声明式子代理内部（[com.tencent.bkrepo.agent.tool.local.ExternalLocalTool] 的
 * `checkPermissions` 自检），协调者自己并没有对应的 pending 工具调用，往协调者输入里塞 confirmMsg 只会
 * 变成一条对大模型不可见的空文本消息，导致大模型把这一轮当成空输入随意作答、会话也无法正常收尾。
 *
 * 因此这里对 [SubagentHitlPromoter] 促升出的、metadata 带 `agentscope.interruptKind=permission_confirm`
 * 与 `subagentSource=<parentSessionId>/<agentId>` 的 pending interrupt 做特殊识别：解析出目标
 * `agentId`，通过 [subagentResumeTargets] 返回，交由
 * [com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator] 路由给
 * [SubagentConfirmResumeExecutor] 直接重新驱动该子代理会话续跑，完全跳过协调者的大模型推理——
 * 不依赖大模型"猜到"要重新调用 `agent_spawn`。协调者自身的 ASK（若未来出现）仍走 [confirmResults] +
 * [PermissionConfirmResumeMiddleware] 的原有路径。
 */
@Component
class AguiPermissionResumeAdapter(
    private val interruptStateRepository: AgentInterruptStateRepository,
) {

    /**
     * 委派给子代理（如 `client`）的写操作确认恢复目标：由哪个子代理续跑、带哪个确认结果。
     *
     * [spawnLabel] 是协调者当初调用 `agent_spawn` 时（如果有）大模型自主填写的可选 `label` 参数，
     * 由 [SubagentHitlPromoter] 在促升时从协调者自己的原始工具调用参数里提取并写入 pending interrupt
     * 快照的 `subagentSpawnLabel` 元数据——[SubagentConfirmResumeExecutor] 必须用同一个 label 重算
     * sessionId，否则在带 label 的委派场景下会打到一个全新、空上下文的子代理会话。
     */
    data class SubagentResumeTarget(
        val agentId: String,
        val confirmResult: ConfirmResult,
        val spawnLabel: String? = null,
    )

    data class AdaptedRun(
        val input: RunAgentInput,
        val confirmResults: List<ConfirmResult>,
        val subagentResumeTargets: List<SubagentResumeTarget> = emptyList(),
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
        val subagentResumeTargets = mutableListOf<SubagentResumeTarget>()
        val remainingResume = mutableListOf<AguiResume>()
        for (entry in input.resume) {
            val snapshot = approvalById[entry.interruptId]
            if (snapshot == null) {
                remainingResume.add(entry)
                continue
            }
            val approved = extractApproved(entry.payload) == true
            val confirmResult = ConfirmResult(
                approved,
                buildToolUseBlock(snapshot),
            )
            val subagentId = subagentAgentIdOf(snapshot)
            if (subagentId != null) {
                val spawnLabel = snapshot.metadata?.get("subagentSpawnLabel") as? String
                subagentResumeTargets.add(SubagentResumeTarget(subagentId, confirmResult, spawnLabel))
            } else {
                confirmResults.add(confirmResult)
            }
        }

        if (confirmResults.isEmpty() && subagentResumeTargets.isEmpty()) {
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
        return AdaptedRun(adaptedInput, confirmResults, subagentResumeTargets)
    }

    /**
     * 若该 pending interrupt 来自 [SubagentHitlPromoter] 促升的子代理级 `permission_confirm`
     * （metadata 携带 `agentscope.interruptKind=permission_confirm` 与 `subagentSource=<parentSessionId>/<agentId>`），
     * 解析出目标 `agentId`；否则返回 null（视为协调者自身的 ASK，走原有 [PermissionConfirmResumeMiddleware] 路径）。
     */
    private fun subagentAgentIdOf(snapshot: com.tencent.bkrepo.agent.session.PendingInterruptSnapshot): String? {
        val metadata = snapshot.metadata ?: return null
        if (metadata["agentscope.interruptKind"] != "permission_confirm") return null
        val source = metadata["subagentSource"] as? String ?: return null
        return source.substringAfterLast('/').takeIf { it.isNotBlank() }
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
