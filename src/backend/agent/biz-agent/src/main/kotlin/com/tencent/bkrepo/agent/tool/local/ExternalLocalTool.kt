/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.tool.local

import com.tencent.bkrepo.agent.permission.ToolRiskLevel
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.permission.PermissionDecision
import io.agentscope.core.tool.ToolBase
import io.agentscope.core.tool.ToolCallParam
import io.agentscope.core.tool.ToolSuspendException
import reactor.core.publisher.Mono

/**
 * 客户端本地执行的 SchemaOnly 工具：`checkPermissions` 按 [LocalToolDefinition.riskLevel] 自行给出
 * ASK/DENY/PASSTHROUGH 决策，执行挂起后由客户端完成。
 *
 * ## 为什么不能像框架自带的 [io.agentscope.core.tool.SchemaOnlyTool] 那样简单 PASSTHROUGH
 *
 * 这些工具直接注册在协调者自己的 [Toolkit] 上（见 [com.tencent.bkrepo.agent.tool.frontend.FrontendToolRegistrar]/
 * [com.tencent.bkrepo.agent.config.HarnessAgentConfiguration]），由协调者自身调用——协调者拥有完整的
 * [PermissionContextState]，[io.agentscope.core.permission.PermissionEngine] 在
 * [com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration] 配置的 ASK 规则表未命中
 * 时会退回到工具自身的 `checkPermissions`。这里仍然按风险等级自行给出 ASK/DENY，而不是恒
 * PASSTHROUGH：一是让风险决策与 [LocalToolDefinition.riskLevel] 保持单一来源、不必在规则表里为每个
 * 工具重复配置；二是防御未来这些工具被以其它方式（例如声明式子 Agent）调用时，规则表不可达也仍有
 * 兜底（历史上 `client` 曾是通过 `HarnessAgent.builder().subagents(...)` 声明的固定子 Agent，
 * materialize 时拿不到协调者的 [PermissionContextState]，工具自检是当时唯一起作用的确认闸门；
 * 现已拍平为协调者直接调用，但自检逻辑本身继续有效、无需改动）。
 */
class ExternalLocalTool(
    private val definition: LocalToolDefinition,
) : ToolBase(
    ToolBase.builder()
        .name(definition.name)
        .description(definition.description)
        .inputSchema(definition.inputSchema)
        .externalTool(true)
        .readOnly(definition.riskLevel.isReadOnly())
        .concurrencySafe(true),
) {

    override fun checkPermissions(
        toolInput: MutableMap<String, Any>,
        context: PermissionContextState,
    ): Mono<PermissionDecision> = Mono.just(
        when (definition.riskLevel) {
            ToolRiskLevel.READ_SAFE, ToolRiskLevel.READ_SENSITIVE ->
                PermissionDecision.passthrough("Local tool '$name' defers to PermissionEngine")

            ToolRiskLevel.WRITE_REVERSIBLE, ToolRiskLevel.WRITE_DESTRUCTIVE ->
                PermissionDecision.ask("Local tool '$name' requires user confirmation before client-side execution")

            ToolRiskLevel.PROHIBITED ->
                PermissionDecision.deny("Local tool '$name' is prohibited")
        },
    )

    override fun callAsync(param: ToolCallParam): Mono<ToolResultBlock> =
        Mono.error(ToolSuspendException())
}

private fun ToolRiskLevel.isReadOnly(): Boolean {
    return this == ToolRiskLevel.READ_SAFE || this == ToolRiskLevel.READ_SENSITIVE
}
