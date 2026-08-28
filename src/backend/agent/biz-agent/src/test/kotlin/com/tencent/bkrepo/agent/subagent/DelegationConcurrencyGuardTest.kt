/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.subagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DelegationConcurrencyGuardTest {

    @Test
    fun `未记录过的session初始inFlight为0`() {
        val guard = DelegationConcurrencyGuard()
        assertEquals(0, guard.inFlight("session-unknown"))
    }

    @Test
    fun `acquire和release按session独立计数`() {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 2)
        guard.acquire("session-2", 1)
        assertEquals(2, guard.inFlight("session-1"))
        assertEquals(1, guard.inFlight("session-2"))

        guard.release("session-1", 1)
        assertEquals(1, guard.inFlight("session-1"))
        assertEquals(1, guard.inFlight("session-2"))
    }

    @Test
    fun `count小于等于0时acquire和release都是no-op`() {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 0)
        guard.acquire("session-1", -1)
        assertEquals(0, guard.inFlight("session-1"))

        guard.acquire("session-1", 3)
        guard.release("session-1", 0)
        guard.release("session-1", -5)
        assertEquals(3, guard.inFlight("session-1"))
    }

    @Test
    fun `release不会让计数变成负数以下溢出`() {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 1)
        guard.release("session-1", 5)
        assertEquals(0, guard.inFlight("session-1"))
    }

    @Test
    fun `归零后的entry仍可继续正确累加`() {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 1)
        guard.release("session-1", 1)
        assertEquals(0, guard.inFlight("session-1"))

        guard.acquire("session-1", 2)
        assertEquals(2, guard.inFlight("session-1"))
    }

    @Test
    fun `并发acquire和release最终计数保持一致`() {
        val guard = DelegationConcurrencyGuard()
        val sessionId = "session-concurrent"
        val threads = 8
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                start.await()
                repeat(perThread) {
                    guard.acquire(sessionId, 1)
                    guard.release(sessionId, 1)
                }
                done.countDown()
            }
        }
        ready.await()
        start.countDown()
        assertEquals(true, done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, guard.inFlight(sessionId))
    }
}
