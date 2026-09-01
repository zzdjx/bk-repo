/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

@DisplayName("模型调用预算与锁TTL、停机窗口的自洽性")
class AgentModelBudgetValidatorTest {

    @Test
    fun `默认配置应没有任何不自洽提示`() {
        assertEquals(emptyList<String>(), validatorOf().findings())
    }

    @Test
    fun `模型调用预算不短于会话锁TTL时应提示且指明可调的配置项`() {
        // 单次尝试 5 分钟 * 3 次尝试 = 15 分钟，正是框架默认值撞上 11 分钟锁 TTL 的场景
        val findings = validatorOf(requestTimeout = Duration.ofMinutes(5), maxAttempts = 3).findings()

        assertEquals(1, findings.size)
        assertTrue(findings.single().contains(AgentModelBudgetValidator.REQUEST_TIMEOUT_PROPERTY))
        assertTrue(findings.single().contains(AgentModelBudgetValidator.MAX_ATTEMPTS_PROPERTY))
        assertTrue(findings.single().contains(AgentModelBudgetValidator.ACTIVE_RUN_TTL_PROPERTY))
    }

    @Test
    fun `预算刚好等于锁TTL时也应提示`() {
        val findings = validatorOf(
            requestTimeout = Duration.ofMinutes(11),
            maxAttempts = 1,
            activeRunTtl = Duration.ofMinutes(11),
        ).findings()

        assertEquals(1, findings.size)
    }

    @Test
    fun `放大锁TTL后同一份模型配置应不再提示`() {
        val findings = validatorOf(
            requestTimeout = Duration.ofMinutes(5),
            maxAttempts = 3,
            activeRunTtl = Duration.ofMinutes(20),
        ).findings()

        assertEquals(emptyList<String>(), findings)
    }

    @Test
    fun `配了备用模型使预算翻倍后应提示`() {
        // 单独看主模型是 2 * 4min + 3s = 8min3s，在 11 分钟之内；加上备用模型的同等预算就超了
        val findings = validatorOf(
            requestTimeout = Duration.ofMinutes(4),
            maxAttempts = 2,
            fallbackModelName = "qwen-plus",
        ).findings()

        assertEquals(1, findings.size)
    }

    @Test
    fun `单次超时长于停机窗口是刻意的默认取舍不应报成不自洽`() {
        val findings = validatorOf(
            requestTimeout = Duration.ofMinutes(2),
            maxAttempts = 1,
            shutdownTimeout = Duration.ofSeconds(15),
        ).findings()

        assertEquals(emptyList<String>(), findings)
    }

    @Test
    fun `不自洽也不应阻断启动`() {
        val validator = validatorOf(requestTimeout = Duration.ofMinutes(30), maxAttempts = 3)

        assertDoesNotThrow { validator.validate() }
    }

    private fun validatorOf(
        requestTimeout: Duration = Duration.ofSeconds(90),
        maxAttempts: Int = 2,
        fallbackModelName: String = "",
        activeRunTtl: Duration = Duration.ofMinutes(11),
        shutdownTimeout: Duration = Duration.ofSeconds(15),
    ): AgentModelBudgetValidator {
        val llm = EffectiveAgentLlmProperties.defaults().copy(
            requestTimeout = requestTimeout,
            maxAttempts = maxAttempts,
            fallbackModelName = fallbackModelName,
        )
        val runtime = EffectiveAgentRuntimeProperties.defaults().copy(
            activeRunTtl = activeRunTtl,
            shutdownTimeout = shutdownTimeout,
        )
        return AgentModelBudgetValidator(llm, runtime)
    }
}
