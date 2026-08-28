/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.permission

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentTopology
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

    private fun defaultRuntimeProperties(): EffectiveAgentRuntimeProperties =
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
            runEventTtl = java.time.Duration.ofDays(7),
            reconnectPollInterval = java.time.Duration.ofMillis(500),
            reconnectTimeout = java.time.Duration.ofMinutes(10),
            stateKeyPrefix = "bkrepo:agent:state:",
            requireRedis = false,
            taskStoreKeyPrefix = "bkrepo:agent:task-store:",
            memoryStoreKeyPrefix = "bkrepo:agent:memory-store:",
            frontendToolsEnabled = true,
            topology = EffectiveAgentTopology.defaults(),
        )
}
