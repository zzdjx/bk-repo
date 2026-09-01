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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.function.Function

@DisplayName("模型并发限制 Middleware")
class ModelConcurrencyLimitMiddlewareTest {

    private val agent: Agent = mock()
    private val input = ModelCallInput(emptyList(), null, null, mock<Model>())

    @Test
    fun `未配置限制时应始终放行`() {
        val middleware = ModelConcurrencyLimitMiddleware(AgentLlmPropertiesResolver.resolve(AgentLlmProperties()))
        val ctx = ctxOf("user-1")
        val next: Function<ModelCallInput, Flux<AgentEvent>> = Function { Flux.empty() }

        repeat(10) { middleware.onModelCall(agent, ctx, input, next).blockLast() }
    }

    @Test
    fun `全局并发打满时新调用应被拒绝且不占用名额泄漏`() {
        val middleware = ModelConcurrencyLimitMiddleware(
            AgentLlmPropertiesResolver.resolve(
                AgentLlmProperties(concurrency = AgentLlmProperties.Concurrency(maxGlobal = 1)),
            ),
        )
        val sink = Sinks.many().unicast().onBackpressureBuffer<AgentEvent>()
        val holdingNext: Function<ModelCallInput, Flux<AgentEvent>> = Function { sink.asFlux() }
        val rejectingNext: Function<ModelCallInput, Flux<AgentEvent>> = Function { Flux.empty() }

        val holding = middleware.onModelCall(agent, ctxOf("user-1"), input, holdingNext).subscribe()

        assertThrows(ModelConcurrencyLimitExceededException::class.java) {
            middleware.onModelCall(agent, ctxOf("user-2"), input, rejectingNext).blockLast()
        }

        sink.tryEmitComplete()
        holding.dispose()

        // 名额释放后应该可以被新调用重新占用
        middleware.onModelCall(agent, ctxOf("user-3"), input, rejectingNext).blockLast()
    }

    @Test
    fun `按用户限流不应影响其他用户`() {
        val middleware = ModelConcurrencyLimitMiddleware(
            AgentLlmPropertiesResolver.resolve(
                AgentLlmProperties(concurrency = AgentLlmProperties.Concurrency(maxPerUser = 1)),
            ),
        )
        val sink = Sinks.many().unicast().onBackpressureBuffer<AgentEvent>()
        val holdingNext: Function<ModelCallInput, Flux<AgentEvent>> = Function { sink.asFlux() }
        val rejectingNext: Function<ModelCallInput, Flux<AgentEvent>> = Function { Flux.empty() }

        middleware.onModelCall(agent, ctxOf("user-a"), input, holdingNext).subscribe()

        assertThrows(ModelConcurrencyLimitExceededException::class.java) {
            middleware.onModelCall(agent, ctxOf("user-a"), input, rejectingNext).blockLast()
        }
        // user-b 不受 user-a 占用的影响
        middleware.onModelCall(agent, ctxOf("user-b"), input, rejectingNext).blockLast()

        sink.tryEmitComplete()
    }

    private fun ctxOf(userId: String): RuntimeContext =
        RuntimeContext.builder().userId(userId).sessionId("thread-1").build()
}
