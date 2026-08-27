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
            frontendToolsEnabled = true,
            topology = EffectiveAgentTopology.defaults(),
        )
}
