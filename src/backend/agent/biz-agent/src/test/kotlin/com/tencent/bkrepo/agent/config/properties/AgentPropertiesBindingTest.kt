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
import java.time.Duration

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
    fun `默认模型调用预算应明显小于会话锁TTL`() {
        val llm = AgentLlmPropertiesResolver.resolve(AgentLlmProperties())
        val activeRunTtl = AgentRuntimeProperties.DEFAULT_ACTIVE_RUN_TTL

        // 两次尝试各 90s，加一次退避（2s 按 jitter 上界算 3s）
        assertEquals(Duration.ofSeconds(183), llm.worstCaseModelCallBudget())
        assertTrue(llm.worstCaseModelCallBudget() < activeRunTtl)
        assertFalse(llm.fallbackEnabled())
    }

    @Test
    fun `配了备用模型时预算翻倍但仍应留在锁TTL之内`() {
        val llm = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(fallbackModelName = "qwen-plus"),
        )

        assertTrue(llm.fallbackEnabled())
        assertEquals(Duration.ofSeconds(366), llm.worstCaseModelCallBudget())
        assertTrue(llm.worstCaseModelCallBudget() < AgentRuntimeProperties.DEFAULT_ACTIVE_RUN_TTL)
    }

    @Test
    fun `不重试时预算就是单次超时且不含退避`() {
        val llm = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(maxAttempts = 1, requestTimeout = Duration.ofSeconds(30)),
        )

        assertEquals(Duration.ofSeconds(30), llm.worstCaseModelCallBudget())
    }

    @Test
    fun `退避应按maxBackoff截顶而不是无限翻倍`() {
        val llm = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(
                maxAttempts = 4,
                requestTimeout = Duration.ofSeconds(10),
                initialBackoff = Duration.ofSeconds(8),
                maxBackoff = Duration.ofSeconds(10),
            ),
        )

        // 4 次尝试各 10s；三次退避按 jitter 上界为 8s->12s、10s->15s、10s->15s
        assertEquals(Duration.ofSeconds(40 + 12 + 15 + 15), llm.worstCaseModelCallBudget())
    }

    @Test
    fun `maxAttempts 配成非法值时应被兜底为至少一次`() {
        val llm = AgentLlmPropertiesResolver.resolve(AgentLlmProperties(maxAttempts = 0))

        assertEquals(1, llm.maxAttempts)
        assertEquals(AgentLlmProperties.DEFAULT_REQUEST_TIMEOUT, llm.worstCaseModelCallBudget())
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
            fallbackModelName = "",
            requestTimeout = AgentLlmProperties.DEFAULT_REQUEST_TIMEOUT,
            maxAttempts = AgentLlmProperties.DEFAULT_MAX_ATTEMPTS,
            initialBackoff = AgentLlmProperties.DEFAULT_INITIAL_BACKOFF,
            maxBackoff = AgentLlmProperties.DEFAULT_MAX_BACKOFF,
            circuitBreaker = EffectiveAgentModelCircuitBreaker.defaults(),
            concurrency = EffectiveAgentModelConcurrency.defaults(),
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
    fun `熔断默认应开启且并发限制默认不限制`() {
        val defaults = AgentLlmPropertiesResolver.resolve(AgentLlmProperties())

        assertTrue(defaults.circuitBreaker.enabled, "熔断默认必须开启，对健康流量无感、只在真出事时兜底")
        assertTrue(defaults.circuitBreaker.failureThreshold > 0)
        assertTrue(defaults.circuitBreaker.cooldown > Duration.ZERO)
        assertEquals(0, defaults.concurrency.maxGlobal, "并发限制是主动限流开关，默认必须不限制")
        assertEquals(0, defaults.concurrency.maxPerUser)
    }

    @Test
    fun `熔断阈值配成非法值时应被兜底为至少一次失败`() {
        val effective = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(circuitBreaker = AgentLlmProperties.CircuitBreaker(failureThreshold = 0)),
        )

        assertEquals(1, effective.circuitBreaker.failureThreshold)
    }

    @Test
    fun `并发限制可按维度分别配置`() {
        val effective = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(concurrency = AgentLlmProperties.Concurrency(maxGlobal = 50, maxPerUser = 5)),
        )

        assertEquals(50, effective.concurrency.maxGlobal)
        assertEquals(5, effective.concurrency.maxPerUser)
    }

    @Test
    fun `灰度总闸默认关闭且名单为空`() {
        val defaults = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties()).gray

        assertFalse(defaults.enabled, "灰度默认必须关闭，不能影响现有部署")
        assertTrue(defaults.allowedProjectIds.isEmpty())
        assertTrue(defaults.allowedUserIds.isEmpty())
        // 关闭时不管名单内容，任何项目/用户都应该放行
        assertTrue(defaults.isAllowed("any-user", "any-project"))
    }

    @Test
    fun `灰度开启后项目或用户命中任一名单即放行`() {
        val gray = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties(
                gray = AgentRuntimeProperties.Gray(
                    enabled = true,
                    allowedProjectIds = setOf("project-1"),
                    allowedUserIds = setOf("user-1"),
                ),
            ),
        ).gray

        assertTrue(gray.isAllowed("stranger", "project-1"), "项目命中名单应放行")
        assertTrue(gray.isAllowed("user-1", "stranger-project"), "用户命中名单应放行")
        assertFalse(gray.isAllowed("stranger", "stranger-project"), "两者都不命中应拒绝")
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

    @Test
    fun `governance子Agent默认禁用且可通过配置开启`() {
        val defaults = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
        assertFalse(defaults.topology.agents.governance.enabled, "governance 默认必须关闭，避免未评估就上线新 Agent")

        val enabled = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties().apply {
                topology = AgentRuntimeProperties.Topology(
                    agents = AgentRuntimeProperties.Topology.Agents(
                        governance = AgentRuntimeProperties.Topology.AgentBinding(enabled = true, maxSteps = 6),
                    ),
                )
            },
        )
        assertTrue(enabled.topology.agents.governance.enabled)
        assertEquals(6, enabled.topology.agents.governance.maxSteps)
    }
}
