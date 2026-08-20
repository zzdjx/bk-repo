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
 * `client`/`discovery`/`transfer-diagnostics` 这类通过 `HarnessAgent.builder().subagents(...)`
 * 声明的固定子 Agent，materialize 时（`HarnessAgentBuilderSupport.buildDeclaredFactory`）**不会**
 * 拿到协调者的 [PermissionContextState]（`SubagentDeclaration` 没有暴露任何字段可以传递
 * ASK/ALLOW 规则表；`inheritParentPermissions` 按其 Javadoc 明确只转发 DENY 规则）。子 Agent 因此
 * 是一个 trivial 的 [PermissionContextState]，会命中 `ReActAgent.evaluatePermissions` 里的
 * legacy 兜底路径（`useEngine = false`）：这条路径完全绕过 `PermissionEngine`（也就绕过了
 * [com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration] 配置的 ASK 规则表），
 * 只信工具自己 `checkPermissions` 返回的 ASK/DENY，其余（包括 PASSTHROUGH）一律静默放行——见
 * `com.tencent.bkrepo.agent.permission.SubagentPermissionInheritanceTest` 的回归测试。
 *
 * 这正是 `set_download_path` 之类写操作在 `client` 子 Agent 里从不弹确认框、直接被"静默允许后再
 * 挂起给客户端执行"的根因。既然 `agentscope-harness` 2.0.1 没有给 declared subagent 传递
 * PermissionContextState 的公开扩展点，这里换一条不依赖它的路径：让工具自身的 `checkPermissions`
 * 按风险等级直接给出 ASK/DENY 决策——不论走的是完整 PermissionEngine（协调者自身）还是 legacy
 * 兜底路径（子 Agent），工具自报的 ASK/DENY 都会被两条路径同等尊重（[PermissionEngine] 在
 * ASK 规则表未命中时会退回到工具自检；legacy 路径则只认工具自检），因此对协调者是无害的冗余，对
 * 子 Agent 则是唯一起作用的确认闸门。
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
