/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.service.AgentToolCallRecordService
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import org.springframework.stereotype.Component

/**
 * 把"用户在确认卡片上点了拒绝"这件事直接记进工具审计。
 *
 * ## 为什么不靠 middleware 观察
 *
 * [com.tencent.bkrepo.agent.audit.ToolAuditMiddleware] 的 `onActing` 钩子不一定能看到被拒绝的工具调用：
 * 取决于框架 `applyConfirmResults` 是否重新驱动 acting 阶段，被拒的那次调用可能根本不再经过 `onActing`。
 * 所以这里在请求入口就按 resume payload 独立落账，不依赖事件重放。
 *
 * ## 这个类的前身
 *
 * 2.0.1 时这里是 `AguiPermissionResumeAdapter`，职责大得多：AG-UI 的 resume[] 在那个版本不会被框架转成
 * Permission HITL 能认的 [io.agentscope.core.event.ConfirmResult]（标准 `AguiMessageConverter` 只会把它
 * 落成 ToolResult Msg，解不开 PERMISSION_ASKING），所以我们自己把审批类条目从 `input.resume` 里摘出来、
 * 转成 ConfirmResult 塞进 RuntimeContext，再由 `PermissionConfirmResumeMiddleware` 拼成带
 * `Msg.METADATA_CONFIRM_RESULTS` 的消息前插到输入里。
 *
 * 2.0.4（#2495 / #2955 / #3100）把这条路补齐了：`AguiResumeCoordinator` 按 thread 跟踪未决 interrupt 并
 * 经 `agui.resume.interrupts` 透给适配层，`AguiMessageConverter.toMsgList(input, resumeInterrupts)` 自己
 * 认出 permission confirm 类型、读 `approved` / `editedArgs` / `reason`、生成同样那条 confirm 消息。
 *
 * 旧做法在新版本上不只是冗余，而是会直接冲突：摘掉 resume 条目之后 `AguiResumeCoordinator.validate` 会判定
 * "thread 还有未决 interrupt 而 resume 为空"，整轮以 `AGUI_INTERRUPT_CONTRACT_ERROR` 失败。因此审批类
 * resume 现在必须原样透传给框架，我们只保留上面那条审计副作用。
 */
@Component
class AguiResumeDenialAuditor(
    private val interruptStateRepository: AgentInterruptStateRepository,
    private val agentToolCallRecordService: AgentToolCallRecordService,
) {

    fun recordDenials(input: RunAgentInput) {
        if (!input.hasResume()) {
            return
        }
        val pending = interruptStateRepository.getPendingInterrupt(input.threadId) ?: return
        val approvalById = pending.interrupts.filter { it.requiresApproval }.associateBy { it.id }
        if (approvalById.isEmpty()) {
            return
        }
        for (entry in input.resume) {
            val snapshot = approvalById[entry.interruptId] ?: continue
            if (isApproved(entry)) {
                continue
            }
            // toolCallId 缺失时退回 id 尾段：合成型 interrupt 的 id 形如 `<reason>-<toolCallId>`
            val toolCallId = snapshot.toolCallId?.takeIf { it.isNotBlank() }
                ?: snapshot.id.substringAfterLast('-', snapshot.id)
            agentToolCallRecordService.recordUserDenied(pending.originRunId, toolCallId)
        }
    }

    private fun isApproved(entry: AguiResume): Boolean = extractApproved(entry.payload) == true

    private fun extractApproved(payload: Any?): Boolean? {
        if (payload == null) return null
        if (payload is Boolean) return payload
        if (payload is Map<*, *>) {
            return payload["approved"] as? Boolean
        }
        return null
    }
}
