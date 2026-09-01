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
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

class AgentRedisRequirementValidatorTest {

    @Test
    fun `要求Redis但没有Redis时应启动失败并提示如何处置`() {
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = true),
            EmptyProvider(RedisConnectionFactory::class.java),
            EmptyProvider(RedisOperation::class.java),
        )

        val error = assertThrows<IllegalStateException> { validator.validate() }

        assertTrue(error.message!!.contains("lettuceRedisClient"), "should tell which layer is missing")
        assertTrue(error.message!!.contains("redisOperation"), "should tell which layer is missing")
        assertTrue(
            error.message!!.contains(AgentRedisRequirementValidator.REQUIRE_REDIS_PROPERTY),
            "should tell which property to flip to accept degradation",
        )
    }

    @Test
    fun `不要求Redis时缺Redis只告警不阻断启动`() {
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = false),
            EmptyProvider(RedisConnectionFactory::class.java),
            EmptyProvider(RedisOperation::class.java),
        )

        assertDoesNotThrow { validator.validate() }
    }

    @Test
    fun `只有RedisOperation但拿不到原生Lettuce客户端时仍视为缺依赖`() {
        // common-redis 的 RedisOperation 在、但连接工厂不是 Lettuce（或还没初始化）：
        // RedisAgentStateStore 与 LettuceStore 都要原生 client，这种半可用状态必须也拦住。
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = true),
            EmptyProvider(RedisConnectionFactory::class.java),
            FixedProvider(mock<RedisOperation>()),
        )

        val error = assertThrows<IllegalStateException> { validator.validate() }

        assertTrue(error.message!!.contains("lettuceRedisClient"))
        assertTrue(!error.message!!.contains("redisOperation"))
    }

    @Test
    fun `Redis依赖齐备时校验通过`() {
        val connectionFactory = mock<LettuceConnectionFactory>()
        whenever(connectionFactory.nativeClient).thenReturn(mock<RedisClient>())
        val validator = AgentRedisRequirementValidator(
            propertiesOf(requireRedis = true),
            FixedProvider<RedisConnectionFactory>(connectionFactory),
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
