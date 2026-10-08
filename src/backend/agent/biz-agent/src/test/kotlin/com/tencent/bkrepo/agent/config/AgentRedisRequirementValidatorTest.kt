/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.common.redis.RedisOperation
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider

class AgentRedisRequirementValidatorTest {

    @Test
    fun `要求Redis但没有Redis时应启动失败并提示如何处置`() {
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = true),
            EmptyProvider(RedisOperation::class.java),
        )

        val error = assertThrows<IllegalStateException> { validator.validate() }

        assertTrue(error.message!!.contains("redisOperation"), "should tell which layer is missing")
        assertTrue(
            error.message!!.contains(AgentRedisRequirementValidator.REQUIRE_REDIS_PROPERTY),
            "should tell which property to flip to accept degradation",
        )
    }

    /**
     * 退化清单只应包含真正还靠 Redis 的能力。会话状态、挂起中断、长期记忆、后台任务都已迁到 Mongo，
     * 继续写在这里会让运维以为缺 Redis 会丢持久化数据。
     */
    @Test
    fun `退化清单只列缓存与协调原语`() {
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = true),
            EmptyProvider(RedisOperation::class.java),
        )

        val message = assertThrows<IllegalStateException> { validator.validate() }.message!!

        listOf("sessionOwnership", "activeRun", "resumeIdempotency").forEach {
            assertTrue(message.contains(it), "degraded capabilities should mention $it, actual=$message")
        }
        listOf("agentState", "pendingInterrupt", "longTermMemory", "backgroundTask").forEach {
            assertTrue(!message.contains(it), "$it 已落 Mongo，不应再算作 Redis 退化项，actual=$message")
        }
    }

    @Test
    fun `不要求Redis时缺Redis只告警不阻断启动`() {
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = false),
            EmptyProvider(RedisOperation::class.java),
        )

        assertDoesNotThrow { validator.validate() }
    }

    @Test
    fun `Redis依赖齐备时校验通过`() {
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = true),
            FixedProvider(mock<RedisOperation>()),
        )

        assertDoesNotThrow { validator.validate() }
    }

    private fun propertiesOf(requireRedis: Boolean): EffectiveAgentRuntimeProperties =
        EffectiveAgentRuntimeProperties.defaults().copy(requireRedis = requireRedis)

    /** 模拟 bean 不存在：`getIfAvailable()` 走 [ObjectProvider] 默认实现，捕获异常后返回 null。 */
    private class EmptyProvider<T : Any>(private val type: Class<T>) : ObjectProvider<T> {
        override fun getObject(): T = throw NoSuchBeanDefinitionException(type)
    }

    private class FixedProvider<T : Any>(private val value: T) : ObjectProvider<T> {
        override fun getObject(): T = value
    }
}
