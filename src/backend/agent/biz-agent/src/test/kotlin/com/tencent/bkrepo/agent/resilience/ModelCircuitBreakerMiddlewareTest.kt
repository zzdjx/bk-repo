/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resilience

import com.tencent.bkrepo.agent.config.properties.AgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.AgentLlmPropertiesResolver
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.ModelCallInput
import io.agentscope.core.model.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.function.Function

@DisplayName("模型熔断 Middleware")
class ModelCircuitBreakerMiddlewareTest {

    private val agent: Agent = mock()
    private val ctx: RuntimeContext = RuntimeContext.builder().userId("user-1").sessionId("thread-1").build()
    private val input = ModelCallInput(emptyList(), null, null, mock<Model>())

    @Test
    fun `关闭时不应拦截next的失败`() {
        val middleware = ModelCircuitBreakerMiddleware(
            AgentLlmPropertiesResolver.resolve(
                AgentLlmProperties(circuitBreaker = AgentLlmProperties.CircuitBreaker(enabled = false)),
            ),
        )
        val next: Function<ModelCallInput, Flux<AgentEvent>> = Function { Flux.error(RuntimeException("boom")) }

        assertThrows(RuntimeException::class.java) {
            middleware.onModelCall(agent, ctx, input, next).blockLast()
        }
    }

    @Test
    fun `连续失败达到阈值后应快速拒绝而不再调用next`() {
        val middleware = ModelCircuitBreakerMiddleware(
            AgentLlmPropertiesResolver.resolve(
                AgentLlmProperties(
                    circuitBreaker = AgentLlmProperties.CircuitBreaker(
                        enabled = true,
                        failureThreshold = 2,
                        cooldown = Duration.ofMinutes(1),
                    ),
                ),
            ),
        )
        var nextCallCount = 0
        val failingNext: Function<ModelCallInput, Flux<AgentEvent>> = Function {
            nextCallCount++
            Flux.error(RuntimeException("boom"))
        }

        repeat(2) {
            assertThrows(RuntimeException::class.java) {
                middleware.onModelCall(agent, ctx, input, failingNext).blockLast()
            }
        }
        assertEquals(2, nextCallCount)

        assertThrows(ModelCircuitOpenException::class.java) {
            middleware.onModelCall(agent, ctx, input, failingNext).blockLast()
        }
        assertEquals(2, nextCallCount, "熔断已打开，第三次不应再调用 next")
    }

    @Test
    fun `成功调用不应触发熔断`() {
        val middleware = ModelCircuitBreakerMiddleware(
            AgentLlmPropertiesResolver.resolve(
                AgentLlmProperties(
                    circuitBreaker = AgentLlmProperties.CircuitBreaker(enabled = true, failureThreshold = 1),
                ),
            ),
        )
        val succeedingNext: Function<ModelCallInput, Flux<AgentEvent>> = Function { Flux.empty() }

        repeat(5) {
            middleware.onModelCall(agent, ctx, input, succeedingNext).blockLast()
        }
    }
}
