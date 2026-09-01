/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resilience

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 单个模型调用点的连续失败熔断器：三态机 CLOSED → OPEN → HALF_OPEN → (CLOSED | OPEN)。
 *
 * 这里的"失败"是[com.tencent.bkrepo.agent.config.AgentModelConfig]装配出来的整条模型调用链路——框架
 * 内部的超时重试与单级 fallback 都已经试过、仍然失败——的最终结果，不是某一次 HTTP 尝试。因此熔断只在
 * "主模型和备用模型都不行"时才生效，是一个比单次调用重试更粗、更悲观的兜底信号。
 *
 * 三态语义：
 * - **CLOSED**：放行，统计连续失败次数；达到阈值即转 OPEN。
 * - **OPEN**：直接拒绝，不再打模型；冷却结束后放一个探测请求过去，同时转 HALF_OPEN。
 * - **HALF_OPEN**：只有那一个探测请求被放行，其余请求继续拒绝；探测成功转 CLOSED（清零计数），
 *   探测失败或被取消（客户端断开、Agent 打断等，视为不确定结果）转回 OPEN 并重新计时冷却。
 *
 * 冷却期固定不做指数退避：这是窄范围内的第一版，真出现"熔断-半开探测失败-再熔断"反复抖动时再考虑退避,
 * 现在加只会引入没被真实场景验证过的复杂度。
 *
 * 全部用无锁原子操作实现，不加锁——这是给 reactive 的 [io.agentscope.core.middleware.MiddlewareBase]
 * 钩子用的，钩子本身跑在 Reactor 的调度线程上，不能阻塞。
 */
class ModelCircuitBreaker(
    private val failureThreshold: Int,
    private val cooldown: Duration,
    private val clock: () -> Instant = Instant::now,
) {

    enum class Phase { CLOSED, OPEN, HALF_OPEN }

    sealed interface Admission {
        object Allowed : Admission
        data class Rejected(val retryAfter: Duration) : Admission
    }

    private val phase = AtomicReference(Phase.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicReference(clock())

    fun currentPhase(): Phase = phase.get()

    /** 判断这次调用能不能放行；只有 [Admission.Allowed] 的调用需要在结束后调用 [recordSuccess]/[recordFailure]。 */
    fun tryAcquire(): Admission {
        when (phase.get()) {
            Phase.CLOSED -> return Admission.Allowed
            Phase.HALF_OPEN -> return Admission.Rejected(Duration.ZERO)
            Phase.OPEN -> {
                val elapsed = Duration.between(openedAt.get(), clock())
                val remaining = cooldown.minus(elapsed)
                if (remaining.isNegative || remaining.isZero) {
                    // 冷却已到：谁先把 OPEN -> HALF_OPEN 的 CAS 抢到，谁就是那一个探测请求；
                    // 抢不到说明另一个请求已经在探测了，走拒绝分支。
                    return if (phase.compareAndSet(Phase.OPEN, Phase.HALF_OPEN)) {
                        Admission.Allowed
                    } else {
                        Admission.Rejected(Duration.ZERO)
                    }
                }
                return Admission.Rejected(remaining)
            }
        }
    }

    fun recordSuccess() {
        consecutiveFailures.set(0)
        phase.compareAndSet(Phase.HALF_OPEN, Phase.CLOSED)
    }

    fun recordFailure() {
        if (phase.compareAndSet(Phase.HALF_OPEN, Phase.OPEN)) {
            openedAt.set(clock())
            return
        }
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= failureThreshold && phase.compareAndSet(Phase.CLOSED, Phase.OPEN)) {
            openedAt.set(clock())
        }
    }
}
