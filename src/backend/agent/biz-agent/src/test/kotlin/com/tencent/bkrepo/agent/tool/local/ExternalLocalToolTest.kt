/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.local

import com.tencent.bkrepo.agent.permission.ToolRiskLevel
import io.agentscope.core.permission.PermissionBehavior
import io.agentscope.core.permission.PermissionContextState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [ExternalLocalTool.checkPermissions] 是 `client` 等 declared subagent 场景下唯一起作用的确认闸门
 * （见类注释里对 `agentscope-harness` 2.0.1 declared subagent 权限上下文继承缺口的说明），因此这里直接
 * 钉住"风险等级 -> 自检决策"的映射，不依赖完整的 HarnessAgent/PermissionEngine 装配。
 */
@DisplayName("ExternalLocalTool 按风险等级自检权限")
class ExternalLocalToolTest {

    private val trivialContext = PermissionContextState.builder().build()

    private fun toolOf(riskLevel: ToolRiskLevel) = ExternalLocalTool(
        LocalToolDefinition(
            name = "stub_tool",
            description = "stub",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            riskLevel = riskLevel,
        ),
    )

    @Test
    fun `写操作风险等级应自检为ASK`() {
        listOf(ToolRiskLevel.WRITE_REVERSIBLE, ToolRiskLevel.WRITE_DESTRUCTIVE).forEach { riskLevel ->
            val decision = toolOf(riskLevel).checkPermissions(mutableMapOf(), trivialContext).block()
            assertEquals(PermissionBehavior.ASK, decision?.behavior, "riskLevel=$riskLevel 应产出 ASK 决策")
        }
    }

    @Test
    fun `只读风险等级应自检为PASSTHROUGH`() {
        listOf(ToolRiskLevel.READ_SAFE, ToolRiskLevel.READ_SENSITIVE).forEach { riskLevel ->
            val decision = toolOf(riskLevel).checkPermissions(mutableMapOf(), trivialContext).block()
            assertEquals(PermissionBehavior.PASSTHROUGH, decision?.behavior, "riskLevel=$riskLevel 应产出 PASSTHROUGH 决策")
        }
    }

    @Test
    fun `禁止调用风险等级应自检为DENY`() {
        val decision = toolOf(ToolRiskLevel.PROHIBITED).checkPermissions(mutableMapOf(), trivialContext).block()
        assertEquals(PermissionBehavior.DENY, decision?.behavior)
    }
}
