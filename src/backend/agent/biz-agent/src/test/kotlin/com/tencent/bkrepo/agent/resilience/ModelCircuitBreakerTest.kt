/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resilience

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@DisplayName("模型调用熔断器")
class ModelCircuitBreakerTest {

    @Test
    fun `连续失败达到阈值前应始终放行`() {
        val breaker = ModelCircuitBreaker(failureThreshold = 3, cooldown = Duration.ofSeconds(30))

        breaker.recordFailure()
        breaker.recordFailure()

        assertEquals(ModelCircuitBreaker.Admission.Allowed, breaker.tryAcquire())
        assertEquals(ModelCircuitBreaker.Phase.CLOSED, breaker.currentPhase())
    }

    @Test
    fun `达到阈值后应转为OPEN并拒绝后续调用`() {
        val breaker = ModelCircuitBreaker(failureThreshold = 3, cooldown = Duration.ofSeconds(30))

        repeat(3) { breaker.recordFailure() }

        assertEquals(ModelCircuitBreaker.Phase.OPEN, breaker.currentPhase())
        val admission = breaker.tryAcquire()
        assertTrue(admission is ModelCircuitBreaker.Admission.Rejected)
    }

    @Test
    fun `成功应清零连续失败计数`() {
        val breaker = ModelCircuitBreaker(failureThreshold = 3, cooldown = Duration.ofSeconds(30))

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordSuccess()
        breaker.recordFailure()
        breaker.recordFailure()

        assertEquals(ModelCircuitBreaker.Phase.CLOSED, breaker.currentPhase(), "两次失败被成功清零，不应达到阈值3")
    }

    @Test
    fun `冷却结束后应放行恰好一个探测请求`() {
        val clock = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
        val breaker = ModelCircuitBreaker(
            failureThreshold = 1,
            cooldown = Duration.ofSeconds(30),
            clock = { clock.get() },
        )
        breaker.recordFailure()
        assertEquals(ModelCircuitBreaker.Phase.OPEN, breaker.currentPhase())

        clock.set(clock.get().plusSeconds(31))

        assertEquals(ModelCircuitBreaker.Admission.Allowed, breaker.tryAcquire(), "冷却结束，第一个请求应作为探测放行")
        assertEquals(ModelCircuitBreaker.Phase.HALF_OPEN, breaker.currentPhase())

        val second = breaker.tryAcquire()
        assertTrue(second is ModelCircuitBreaker.Admission.Rejected, "探测名额已被占用，第二个请求应被拒绝")
    }

    @Test
    fun `探测成功应关闭熔断`() {
        val clock = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
        val breaker = ModelCircuitBreaker(
            failureThreshold = 1,
            cooldown = Duration.ofSeconds(30),
            clock = { clock.get() },
        )
        breaker.recordFailure()
        clock.set(clock.get().plusSeconds(31))
        breaker.tryAcquire()

        breaker.recordSuccess()

        assertEquals(ModelCircuitBreaker.Phase.CLOSED, breaker.currentPhase())
        assertEquals(ModelCircuitBreaker.Admission.Allowed, breaker.tryAcquire())
    }

    @Test
    fun `探测失败应重新回到OPEN并重置冷却计时`() {
        val clock = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
        val breaker = ModelCircuitBreaker(
            failureThreshold = 1,
            cooldown = Duration.ofSeconds(30),
            clock = { clock.get() },
        )
        breaker.recordFailure()
        clock.set(clock.get().plusSeconds(31))
        breaker.tryAcquire()

        breaker.recordFailure()

        assertEquals(ModelCircuitBreaker.Phase.OPEN, breaker.currentPhase())
        assertTrue(breaker.tryAcquire() is ModelCircuitBreaker.Admission.Rejected, "刚重新打开，还没到新的冷却时间")

        clock.set(clock.get().plusSeconds(31))
        assertEquals(ModelCircuitBreaker.Admission.Allowed, breaker.tryAcquire(), "新一轮冷却结束后应再次放行探测")
    }

    @Test
    fun `未到冷却时间时OPEN应持续拒绝`() {
        val clock = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
        val breaker = ModelCircuitBreaker(
            failureThreshold = 1,
            cooldown = Duration.ofSeconds(30),
            clock = { clock.get() },
        )
        breaker.recordFailure()

        clock.set(clock.get().plusSeconds(10))

        val admission = breaker.tryAcquire()
        assertTrue(admission is ModelCircuitBreaker.Admission.Rejected)
        val remaining = (admission as ModelCircuitBreaker.Admission.Rejected).retryAfter
        assertEquals(Duration.ofSeconds(20), remaining)
    }
}
