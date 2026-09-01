/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resilience

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentLlmProperties
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.middleware.ModelCallInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.function.Function

/**
 * 挂在框架的 `onModelCall` 钩子上，用 [ModelConcurrencyGuard] 限制同时打进模型的调用数。
 *
 * 名额占用的起止跟 [next] 严格对齐：拿到许可才调用 `next.apply(input)`，调用结束（成功/失败/取消，
 * 三种终态用 `doFinally` 一次性兜住，避免 `doOnComplete`+`doOnError`+`doOnCancel` 三处分别调用
 * `release()` 时一旦漏掉一种就永久少一个名额）才释放。这样名额度量的是"框架内部重试与 fallback 期间
 * 一直占着的那段时间"，与"当前有多少个请求正在跟模型对话"这个直觉定义完全一致。
 *
 * 与 [ModelCircuitBreakerMiddleware] 的编排关系：两者都注册在同一个 `onModelCall` 钩子上，框架按
 * [io.agentscope.harness.agent.HarnessAgent.Builder] 里 `middleware(...)` 的调用顺序从外到内嵌套。
 * 本项目在 [com.tencent.bkrepo.agent.config.AgentHarnessConfigurer] 里把熔断放在并发限制外层：
 * 熔断已经 OPEN 时应该直接拒绝，不该再去抢并发名额、也不该让并发限制的拒绝统计里混进"模型本来就在
 * 熔断"的噪音。
 */
@Component
class ModelConcurrencyLimitMiddleware(
    properties: EffectiveAgentLlmProperties,
) : MiddlewareBase {

    private val guard = ModelConcurrencyGuard(
        maxGlobal = properties.concurrency.maxGlobal,
        maxPerUser = properties.concurrency.maxPerUser,
    )

    override fun onModelCall(
        agent: Agent,
        ctx: RuntimeContext,
        input: ModelCallInput,
        next: Function<ModelCallInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val permit = guard.tryAcquire(ctx.userId)
            ?: run {
                logger.warn("model concurrency limit reached, rejecting call (userId={})", ctx.userId)
                return Flux.error(ModelConcurrencyLimitExceededException())
            }
        return next.apply(input).doFinally { permit.release() }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ModelConcurrencyLimitMiddleware::class.java)
    }
}

class ModelConcurrencyLimitExceededException :
    RuntimeException("Model call concurrency limit reached, try again shortly")
