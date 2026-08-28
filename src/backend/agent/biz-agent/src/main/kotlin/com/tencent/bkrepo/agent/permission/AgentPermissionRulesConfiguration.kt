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
        HARNESS_ORCHESTRATION_TOOLS.forEach { toolName ->
            builder.addAllowRule(toolName, toolRule(toolName, PermissionBehavior.ALLOW))
        }
        MEMORY_READ_TOOLS.forEach { toolName ->
            builder.addAllowRule(toolName, toolRule(toolName, PermissionBehavior.ALLOW))
        }
        builder.addAskRule(MEMORY_SAVE_TOOL, toolRule(MEMORY_SAVE_TOOL, PermissionBehavior.ASK))
        if (properties.frontendToolsEnabled) {
            LocalToolDefinitions.allTools().forEach { definition ->
                registerRule(builder, definition.name, definition.riskLevel)
            }
        }
        return builder.build()
    }

    private fun registerRule(
        builder: PermissionContextState.Builder,
        toolName: String,
        riskLevel: ToolRiskLevel,
    ) {
        when (riskLevel) {
            ToolRiskLevel.READ_SAFE, ToolRiskLevel.READ_SENSITIVE -> builder.addAllowRule(
                toolName,
                toolRule(toolName, PermissionBehavior.ALLOW),
            )

            ToolRiskLevel.WRITE_REVERSIBLE, ToolRiskLevel.WRITE_DESTRUCTIVE -> builder.addAskRule(
                toolName,
                toolRule(toolName, PermissionBehavior.ASK),
            )

            ToolRiskLevel.PROHIBITED -> builder.addDenyRule(
                toolName,
                toolRule(toolName, PermissionBehavior.DENY),
            )
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
    }
}
