/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.hitl.AguiResumeCoordinatorHydrator
import com.tencent.bkrepo.agent.hitl.AguiResumeDenialAuditor
import com.tencent.bkrepo.agent.hitl.AguiResumeValidator
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolSanitizer
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.processor.AguiRequestProcessor
import org.springframework.stereotype.Component

/** [AgentRunInputPreparer.prepare] 的产物：清洗过、可以直接交给框架执行的输入。 */
data class PreparedRunInput(
    val input: RunAgentInput,
    val threadId: String,
    val runId: String,
)

/**
 * 把客户端传上来的 [RunAgentInput] 变成可以安全执行的输入。
 *
 * 只做"输入本身"的事——字段校验、工具清洗、HITL resume 的前置处理。鉴权（IAM、会话归属、灰度、
 * 并发与幂等）不在这里，那些是准入判断，留在 [AgentRunOrchestrator]。
 *
 * ## 五步的先后顺序是有约束的
 *
 * 1. [RunAgentInputValidator] 先做结构校验，后面几步都假设字段已经合法。
 * 2. [AguiResumeValidator] 校验 resume 条目对得上 thread 上未决的 interrupt，并对可执行的写操作
 *    补一次项目权限检查。必须在清洗之前——清洗会重写 `forwardedProps`，但不动 `resume`，而校验
 *    需要看到原始请求。
 * 3. [FrontendToolSanitizer] 把客户端声明的工具收敛到服务端 allowlist，并剥掉 `forwardedProps`
 *    里不可信的身份键。从这一步起后续全部使用清洗后的副本。
 * 4. [AguiResumeDenialAuditor] 记录用户拒绝的工具调用。必须在交给框架之前——框架消费掉 resume
 *    之后，"哪些 interrupt 被拒了"这个信息就只剩在它自己的内部状态里，我们拿不到了。
 * 5. [AguiResumeCoordinatorHydrator] 把持久化的 pending interrupt 灌回框架的进程内表。必须是
 *    最后一步，也必须在 `process()` 之前。
 *
 * ## resume[] 为什么原样透传
 *
 * 框架 2.0.4 的 `AguiResumeCoordinator` 要求 `resume` 覆盖 thread 上全部未决 interrupt，少一条就
 * 整个请求报 `AGUI_INTERRUPT_CONTRACT_ERROR`。所以这里不能像早期版本那样过滤 resume 条目，只能
 * 原样交出去，再靠第 5 步把它那张进程内表补齐。
 */
@Component
class AgentRunInputPreparer(
    private val inputValidator: RunAgentInputValidator,
    private val aguiResumeValidator: AguiResumeValidator,
    private val frontendToolSanitizer: FrontendToolSanitizer,
    private val aguiResumeDenialAuditor: AguiResumeDenialAuditor,
    private val aguiResumeCoordinatorHydrator: AguiResumeCoordinatorHydrator,
    private val aguiRequestProcessor: AguiRequestProcessor,
) {

    fun prepare(userId: String, projectId: String, input: RunAgentInput): PreparedRunInput {
        inputValidator.validate(input)
        aguiResumeValidator.validateAndPrepare(userId, projectId, input)
        val sanitized = frontendToolSanitizer.sanitize(input)
        aguiResumeDenialAuditor.recordDenials(sanitized)
        aguiResumeCoordinatorHydrator.hydrateIfNeeded(aguiRequestProcessor, sanitized)
        return PreparedRunInput(sanitized, sanitized.threadId, sanitized.runId)
    }
}
