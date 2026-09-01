/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config.properties

import com.tencent.bkrepo.agent.config.AgentSystemPrompts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Agent 配置绑定")
class AgentPropertiesBindingTest {

    @Test
    fun `agent llm 绑定应解析为 effective llm`() {
        val llm = AgentLlmProperties(
            baseUrl = "https://gateway.example/v1",
            apiKey = "secret-api-key",
            modelName = "qwen-max",
            reasoningEffort = "high",
        )
        val effective = AgentLlmPropertiesResolver.resolve(llm)

        assertEquals("https://gateway.example/v1", effective.baseUrl)
        assertEquals("qwen-max", effective.modelName)
        assertEquals("high", effective.effectiveReasoningEffort())
        assertEquals(AgentLlmAuthMode.API_KEY, effective.authMode)
    }

    @Test
    fun `bkAppCode 非空时应推导为网关认证`() {
        val llm = AgentLlmProperties(
            baseUrl = "https://gateway.example/v1",
            bkAppCode = "bk-repo",
            bkAppSecret = "secret-value",
            modelName = "qwen-max",
        )
        val effective = AgentLlmPropertiesResolver.resolve(llm)

        assertEquals(AgentLlmAuthMode.BK_GATEWAY, effective.authMode)
    }

    @Test
    fun `effective llm toString 应脱敏密钥`() {
        val effective = EffectiveAgentLlmProperties(
            baseUrl = "https://gateway.example/v1",
            apiKey = "super-secret-api-key",
            bkAppCode = "bk-repo",
            bkAppSecret = "super-secret",
            modelName = "qwen-max",
            reasoningEffort = null,
            stream = true,
            authMode = AgentLlmAuthMode.API_KEY,
        )

        val text = effective.toString()
        assertFalse(text.contains("super-secret-api-key"))
        assertFalse(text.contains("super-secret"))
        assertTrue(text.contains("qwen-max"))
    }

    @Test
    fun `agent memory 绑定应解析为 effective memory`() {
        val memory = AgentMemoryPropertiesResolver.resolve(
            AgentMemoryProperties(
                contextWindowSize = 64_000,
                compactionEnabled = false,
                reserved = 15_000,
                toolResultEvictionEnabled = false,
            ),
        )

        assertFalse(memory.compactionEnabled)
        assertFalse(memory.toolResultEvictionEnabled)
        assertEquals(64_000, memory.contextWindowSize)
        assertEquals(15_000, memory.reserved)
    }

    @Test
    fun `agent runtime 绑定应解析为 effective runtime`() {
        val runtime = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply {
                features = AgentRuntimeProperties.Features(frontendToolsEnabled = false)
                activeRunTtl = java.time.Duration.ofMinutes(15)
                maxMessageLength = 16_000
                state = AgentRuntimeProperties.State(keyPrefix = "bkrepo:agent:custom:")
            },
        )

        assertFalse(runtime.frontendToolsEnabled)
        assertEquals(java.time.Duration.ofMinutes(15), runtime.activeRunTtl)
        assertEquals(16_000, runtime.maxMessageLength)
        assertEquals("bkrepo:agent:custom:", runtime.stateKeyPrefix)
    }

    @Test
    fun `停机超时默认为有限值且可按配置覆盖`() {
        val defaults = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())

        // 有限值是硬要求：null/0 会让 JVM 停机钩子无限等待，且框架的强制中断分支不会触发
        assertTrue(defaults.shutdownTimeout > java.time.Duration.ZERO)

        val custom = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply { shutdownTimeout = java.time.Duration.ofSeconds(8) },
        )

        assertEquals(java.time.Duration.ofSeconds(8), custom.shutdownTimeout)
    }

    @Test
    fun `只读模式与requireRedis默认关闭可按配置开启`() {
        val defaults = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())

        assertFalse(defaults.readOnlyMode, "本地开发默认不进只读模式")
        assertFalse(defaults.requireRedis, "本地开发默认允许退化为进程内存储")

        val hardened = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply {
                features = AgentRuntimeProperties.Features(readOnlyMode = true)
                state = AgentRuntimeProperties.State(requireRedis = true)
            },
        )

        assertTrue(hardened.readOnlyMode)
        assertTrue(hardened.requireRedis)
    }

    @Test
    fun `数据保留期默认都是有限值且可按项覆盖`() {
        val defaults = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties()).retention

        // 这几张表/键都是只写不删的，默认必须有上限，否则会单调增长
        listOf(
            defaults.runEvent,
            defaults.run,
            defaults.toolCall,
            defaults.message,
            defaults.session,
            defaults.task,
            defaults.memory,
        ).forEach { assertTrue(it > java.time.Duration.ZERO, "默认保留期必须是有限正值") }

        // 约束见 AgentRuntimeProperties.Retention：事件流不能比 run 元数据活得久，
        // 会话元数据不能比它的消息先过期
        assertTrue(defaults.runEvent <= defaults.run)
        assertTrue(defaults.session >= defaults.message)

        val custom = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply {
                retention = AgentRuntimeProperties.Retention(
                    run = java.time.Duration.ofDays(30),
                    memory = java.time.Duration.ZERO,
                )
            },
        ).retention

        assertEquals(java.time.Duration.ofDays(30), custom.run)
        assertEquals(java.time.Duration.ZERO, custom.memory, "0 表示永不过期，不应被规整成默认值")
        assertEquals(AgentRuntimeProperties.DEFAULT_MESSAGE_RETENTION, custom.message)
    }

    @Test
    fun `未配置 sys-prompt 时应默认使用 AgentSystemPrompts`() {
        val runtime = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())

        assertEquals(AgentSystemPrompts.DEFAULT, runtime.sysPrompt)
    }

    @Test
    fun `agent runtime sys-prompt 在 Consul 占位为空时应回退代码默认`() {
        val runtime = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply { sysPrompt = "" },
        )

        assertEquals(AgentSystemPrompts.DEFAULT, runtime.sysPrompt)
    }

    @Test
    fun `agent runtime topology 应透传到 effective topology`() {
        val runtime = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply {
                topology = AgentRuntimeProperties.Topology(
                    coordinator = AgentRuntimeProperties.Topology.Coordinator(taskListEnabled = false),
                )
            },
        )

        assertFalse(runtime.topology.coordinator.taskListEnabled)
    }
}
