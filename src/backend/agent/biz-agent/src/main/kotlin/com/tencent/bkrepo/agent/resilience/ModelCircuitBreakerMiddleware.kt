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
import reactor.core.publisher.SignalType
import java.util.function.Function

/**
 * 挂在框架的 `onModelCall` 钩子上，是 [ModelCircuitBreaker] 唯一的调用入口。
 *
 * 放在 `onModelCall` 而不是更外层的 `onReasoning`：这里看到的 `next.apply(input)` 就是
 * [com.tencent.bkrepo.agent.config.AgentModelConfig] 装配出来的那条模型调用链路——框架内部的超时
 * 重试与单级 fallback 已经在 `next` 内部跑完了，中断在这一层拿到的错误意味着"重试和 fallback 都
 * 没能救回来"，正是熔断该关心的信号粒度；放在更外层会把工具执行失败也计入熔断统计，语义就错了。
 *
 * 拒绝态直接 `Flux.error`，不调用 `next`：这是熔断最大的价值所在——省掉一整轮重试预算的等待
 * （默认配置下最坏几分钟），让调用方立刻知道"现在打不动，等会再来"而不是干等到超时。
 */
@Component
class ModelCircuitBreakerMiddleware(
    properties: EffectiveAgentLlmProperties,
) : MiddlewareBase {

    private val enabled = properties.circuitBreaker.enabled
    private val breaker = ModelCircuitBreaker(
        failureThreshold = properties.circuitBreaker.failureThreshold,
        cooldown = properties.circuitBreaker.cooldown,
    )

    override fun onModelCall(
        agent: Agent,
        ctx: RuntimeContext,
        input: ModelCallInput,
        next: Function<ModelCallInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        if (!enabled) {
            return next.apply(input)
        }
        return when (val admission = breaker.tryAcquire()) {
            is ModelCircuitBreaker.Admission.Rejected -> {
                logger.warn("model circuit breaker OPEN, rejecting call (retryAfter={})", admission.retryAfter)
                Flux.error(ModelCircuitOpenException(admission.retryAfter))
            }
            ModelCircuitBreaker.Admission.Allowed -> next.apply(input)
                // 用 doFinally 一次性兜住 ON_COMPLETE/ON_ERROR/CANCEL 三种终态，而不是分别挂
                // doOnComplete/doOnError/doOnCancel：探测请求被取消（客户端断开、Agent 被打断）
                // 既不是成功也不是失败，但必须有个结果把 HALF_OPEN 状态释放掉，否则熔断会卡死在
                // 半开、永远等不到下一次探测；三处分别挂钩子一旦漏掉一种就是这个后果，一次性兜住
                // 更不容易出这种偏差。CANCEL 悲观地按失败处理：宁可多等一个冷却周期再探测，好过卡死。
                .doFinally { signal ->
                    if (signal == SignalType.ON_COMPLETE) breaker.recordSuccess() else breaker.recordFailure()
                }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ModelCircuitBreakerMiddleware::class.java)
    }
}

/** 熔断处于 OPEN/HALF_OPEN 且这次调用没抢到探测名额时抛出;[retryAfter] 是给客户端的重试提示，不强制生效。 */
class ModelCircuitOpenException(val retryAfter: java.time.Duration) :
    RuntimeException("Model circuit breaker is open, retry after $retryAfter")
