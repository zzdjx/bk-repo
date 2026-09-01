/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.AgentLlmAuthMode
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentLlmProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

@DisplayName("模型装配与韧性配置")
class AgentModelConfigTest {

    @Test
    fun `未配备用模型时只装配主模型`() {
        val resilience = AgentModelConfig().agentModelResilience(propertiesOf())

        assertNull(resilience.fallbackModel)
    }

    @Test
    fun `配了备用模型时应装配出一个换了模型名的独立实例`() {
        val properties = propertiesOf(fallbackModelName = "qwen-plus")

        val resilience = AgentModelConfig().agentModelResilience(properties)
        val primary = AgentModelConfig().agentChatModel(properties)

        assertNotNull(resilience.fallbackModel)
        assertEquals("qwen-max", primary.modelName)
        assertEquals("qwen-plus", resilience.fallbackModel!!.modelName)
    }

    @Test
    fun `超时与重试应按配置写进ExecutionConfig而不是沿用框架默认的5分钟`() {
        val resilience = AgentModelConfig().agentModelResilience(
            propertiesOf(requestTimeout = Duration.ofSeconds(45), maxAttempts = 3),
        )

        val config = resilience.executionConfig
        assertEquals(Duration.ofSeconds(45), config.timeout)
        assertEquals(3, config.maxAttempts)
        assertNotNull(config.retryOn)
    }

    private fun propertiesOf(
        fallbackModelName: String = "",
        requestTimeout: Duration = Duration.ofSeconds(90),
        maxAttempts: Int = 2,
    ): EffectiveAgentLlmProperties = EffectiveAgentLlmProperties.defaults().copy(
        baseUrl = "https://gateway.example/v1",
        apiKey = "test-api-key",
        authMode = AgentLlmAuthMode.API_KEY,
        modelName = "qwen-max",
        fallbackModelName = fallbackModelName,
        requestTimeout = requestTimeout,
        maxAttempts = maxAttempts,
    )
}
