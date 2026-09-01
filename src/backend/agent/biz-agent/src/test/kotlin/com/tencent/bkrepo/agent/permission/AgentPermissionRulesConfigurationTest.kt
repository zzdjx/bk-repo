/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.permission

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRetention
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentTopology
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.permission.PermissionBehavior
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentPermissionRulesConfigurationTest {

    private val configuration = AgentPermissionRulesConfiguration()

    @Test
    fun `harness orchestration tools are allowed without user confirmation`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties())

        AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS.forEach { toolName ->
            val rules = context.allowRules[toolName].orEmpty()
            assertTrue(rules.isNotEmpty(), "$toolName should have ALLOW rule")
            assertEquals(PermissionBehavior.ALLOW, rules.first().behavior)
        }

        AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS.forEach { toolName ->
            assertTrue(
                context.askRules[toolName].orEmpty().isEmpty(),
                "$toolName should not require ASK",
            )
        }
    }

    @Test
    fun `memory_search和memory_get是只读工具应直接ALLOW`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties())

        AgentPermissionRulesConfiguration.MEMORY_READ_TOOLS.forEach { toolName ->
            val rules = context.allowRules[toolName].orEmpty()
            assertTrue(rules.isNotEmpty(), "$toolName should have ALLOW rule")
            assertEquals(PermissionBehavior.ALLOW, rules.first().behavior)
            assertTrue(
                context.askRules[toolName].orEmpty().isEmpty(),
                "$toolName should not require ASK",
            )
        }
    }

    @Test
    fun `memory_save是唯一写入入口必须走ASK确认`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties())

        val askRules = context.askRules[AgentPermissionRulesConfiguration.MEMORY_SAVE_TOOL].orEmpty()
        assertTrue(askRules.isNotEmpty(), "memory_save should have ASK rule")
        assertEquals(PermissionBehavior.ASK, askRules.first().behavior)
        assertTrue(
            context.allowRules[AgentPermissionRulesConfiguration.MEMORY_SAVE_TOOL].orEmpty().isEmpty(),
            "memory_save should not be auto-allowed",
        )
    }

    @Test
    fun `memory_delete是唯一删除入口必须走ASK确认`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties())

        val askRules = context.askRules[AgentPermissionRulesConfiguration.MEMORY_DELETE_TOOL].orEmpty()
        assertTrue(askRules.isNotEmpty(), "memory_delete should have ASK rule")
        assertEquals(PermissionBehavior.ASK, askRules.first().behavior)
        assertTrue(
            context.allowRules[AgentPermissionRulesConfiguration.MEMORY_DELETE_TOOL].orEmpty().isEmpty(),
            "memory_delete should not be auto-allowed",
        )
    }

    @Test
    fun `只读模式下客户端写工具由ASK收紧为DENY只读工具不受影响`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties(readOnly = true))

        val writeTools = LocalToolDefinitions.allTools().filter {
            it.riskLevel == ToolRiskLevel.WRITE_REVERSIBLE || it.riskLevel == ToolRiskLevel.WRITE_DESTRUCTIVE
        }
        assertTrue(writeTools.isNotEmpty(), "fixture broken: no write tool in catalog")
        writeTools.forEach { definition ->
            val denyRules = context.denyRules[definition.name].orEmpty()
            assertTrue(denyRules.isNotEmpty(), "${definition.name} should have DENY rule in read-only mode")
            assertEquals(PermissionBehavior.DENY, denyRules.first().behavior)
            assertTrue(
                context.askRules[definition.name].orEmpty().isEmpty(),
                "${definition.name} should not fall back to ASK in read-only mode",
            )
        }

        val readTools = LocalToolDefinitions.allTools().filter {
            it.riskLevel == ToolRiskLevel.READ_SAFE || it.riskLevel == ToolRiskLevel.READ_SENSITIVE
        }
        readTools.forEach { definition ->
            assertEquals(
                PermissionBehavior.ALLOW,
                context.allowRules[definition.name].orEmpty().firstOrNull()?.behavior,
                "${definition.name} should stay ALLOW in read-only mode",
            )
        }
    }

    @Test
    fun `只读模式下记忆写入与删除都是DENY`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties(readOnly = true))

        AgentPermissionRulesConfiguration.MEMORY_WRITE_TOOLS.forEach { toolName ->
            val denyRules = context.denyRules[toolName].orEmpty()
            assertTrue(denyRules.isNotEmpty(), "$toolName should have DENY rule in read-only mode")
            assertEquals(PermissionBehavior.DENY, denyRules.first().behavior)
            assertTrue(
                context.askRules[toolName].orEmpty().isEmpty(),
                "$toolName should not be confirmable in read-only mode",
            )
        }
    }

    @Test
    fun `只读模式不影响委派编排工具`() {
        val context = configuration.agentPermissionContext(defaultRuntimeProperties(readOnly = true))

        AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS.forEach { toolName ->
            assertEquals(
                PermissionBehavior.ALLOW,
                context.allowRules[toolName].orEmpty().firstOrNull()?.behavior,
                "$toolName should stay ALLOW in read-only mode",
            )
        }
    }

    private fun defaultRuntimeProperties(readOnly: Boolean = false): EffectiveAgentRuntimeProperties =
        EffectiveAgentRuntimeProperties(
            name = "bkrepo-assistant",
            sysPrompt = "test",
            maxIters = 8,
            workspace = ".",
            sseTimeout = java.time.Duration.ofMinutes(5),
            maxMessageLength = 32 * 1024,
            maxThreadIdLength = 128,
            sessionTtl = java.time.Duration.ofDays(30),
            activeRunTtl = java.time.Duration.ofMinutes(11),
            reconnectPollInterval = java.time.Duration.ofMillis(500),
            reconnectTimeout = java.time.Duration.ofMinutes(10),
            shutdownTimeout = java.time.Duration.ofSeconds(15),
            stateKeyPrefix = "bkrepo:agent:state:",
            requireRedis = false,
            taskStoreKeyPrefix = "bkrepo:agent:task-store:",
            memoryStoreKeyPrefix = "bkrepo:agent:memory-store:",
            frontendToolsEnabled = true,
            readOnlyMode = readOnly,
            retention = EffectiveAgentRetention.defaults(),
            topology = EffectiveAgentTopology.defaults(),
        )
}
