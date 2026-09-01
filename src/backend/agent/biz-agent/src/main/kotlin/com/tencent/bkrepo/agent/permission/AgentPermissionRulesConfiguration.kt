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

package com.tencent.bkrepo.agent.permission

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.permission.PermissionBehavior
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.permission.PermissionRule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AgentPermissionRulesConfiguration {

    @Bean
    fun agentPermissionContext(properties: EffectiveAgentRuntimeProperties): PermissionContextState {
        val builder = PermissionContextState.builder()
        val readOnly = properties.readOnlyMode
        HARNESS_ORCHESTRATION_TOOLS.forEach { toolName ->
            builder.addAllowRule(toolName, toolRule(toolName, PermissionBehavior.ALLOW))
        }
        MEMORY_READ_TOOLS.forEach { toolName ->
            builder.addAllowRule(toolName, toolRule(toolName, PermissionBehavior.ALLOW))
        }
        // 记忆写工具由框架的 filesystem(spec) 一次性带进来，没法像客户端工具那样只摘掉写的那几个，
        // 所以只读模式下只能靠规则表把 ASK 收紧成 DENY——这也是必须保留 PermissionEngine 这一层的原因。
        MEMORY_WRITE_TOOLS.forEach { toolName ->
            registerWriteRule(builder, toolName, readOnly)
        }
        if (properties.frontendToolsEnabled) {
            LocalToolDefinitions.allTools().forEach { definition ->
                registerRule(builder, definition.name, definition.riskLevel, readOnly)
            }
        }
        return builder.build()
    }

    private fun registerRule(
        builder: PermissionContextState.Builder,
        toolName: String,
        riskLevel: ToolRiskLevel,
        readOnly: Boolean,
    ) {
        when (riskLevel) {
            ToolRiskLevel.READ_SAFE, ToolRiskLevel.READ_SENSITIVE -> builder.addAllowRule(
                toolName,
                toolRule(toolName, PermissionBehavior.ALLOW),
            )

            ToolRiskLevel.WRITE_REVERSIBLE, ToolRiskLevel.WRITE_DESTRUCTIVE -> registerWriteRule(
                builder,
                toolName,
                readOnly,
            )

            ToolRiskLevel.PROHIBITED -> builder.addDenyRule(
                toolName,
                toolRule(toolName, PermissionBehavior.DENY),
            )
        }
    }

    /**
     * 写工具的默认行为是 ASK（弹确认卡片）；只读模式下一律 DENY，连"用户点确认"这条路都不留——
     * 只读模式是生产应急阀，语义是"这个副本此刻绝对不写"，不是"多问一句"。
     */
    private fun registerWriteRule(
        builder: PermissionContextState.Builder,
        toolName: String,
        readOnly: Boolean,
    ) {
        if (readOnly) {
            builder.addDenyRule(toolName, toolRule(toolName, PermissionBehavior.DENY))
        } else {
            builder.addAskRule(toolName, toolRule(toolName, PermissionBehavior.ASK))
        }
    }

    private fun toolRule(toolName: String, behavior: PermissionBehavior): PermissionRule {
        return PermissionRule(toolName, null, behavior, RULE_SOURCE)
    }

    companion object {
        private const val RULE_SOURCE = "bkrepo-agent"

        /**
         * Harness 编排工具：委派子 Agent、查询任务状态等，不含直接业务写操作。
         * 子 Agent 内工具仍按各自风险等级走 PermissionEngine（含 ASK）。
         */
        val HARNESS_ORCHESTRATION_TOOLS: List<String> = listOf(
            "agent_spawn",
            "agent_send",
            "agent_list",
            "task_output",
            "task_cancel",
            "task_list",
        )

        /**
         * 长期记忆的只读工具（框架内置 `MemorySearchTool`/`MemoryGetTool`），风险等级等同于其它只读
         * 工具，直接 ALLOW，不需要每次都打断用户确认。
         */
        val MEMORY_READ_TOOLS: List<String> = listOf("memory_search", "memory_get")

        /**
         * 长期记忆的唯一写入入口（框架内置 `MemorySaveTool`）。产品要求"记忆写入需用户明确同意"，
         * 这里复用现有 HITL 确认弹窗机制，走 ASK——跟其它写工具同构，不需要新建单独的同意 UI。
         */
        const val MEMORY_SAVE_TOOL = "memory_save"

        /**
         * 长期记忆的删除入口（自建 `com.tencent.bkrepo.agent.tool.memory.MemoryDeleteTool`，框架未提供）。
         * 与 [MEMORY_SAVE_TOOL] 同构，同样走 ASK，删除前需要用户在前端弹窗确认。
         */
        const val MEMORY_DELETE_TOOL = "memory_delete"

        /** 记忆的两个写入口，风险等级等同客户端写工具：默认 ASK，只读模式下 DENY。 */
        val MEMORY_WRITE_TOOLS: List<String> = listOf(MEMORY_SAVE_TOOL, MEMORY_DELETE_TOOL)
    }
}
