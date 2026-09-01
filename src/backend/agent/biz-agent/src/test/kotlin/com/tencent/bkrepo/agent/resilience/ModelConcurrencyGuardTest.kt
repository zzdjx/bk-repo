/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resilience

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("模型调用并发限制")
class ModelConcurrencyGuardTest {

    @Test
    fun `两个维度都不限制时应始终放行`() {
        val guard = ModelConcurrencyGuard(maxGlobal = 0, maxPerUser = 0)

        repeat(100) { assertNotNull(guard.tryAcquire("user-1")) }
    }

    @Test
    fun `全局并发打满后应拒绝新调用`() {
        val guard = ModelConcurrencyGuard(maxGlobal = 2, maxPerUser = 0)

        val first = guard.tryAcquire("user-1")
        val second = guard.tryAcquire("user-2")
        val third = guard.tryAcquire("user-3")

        assertNotNull(first)
        assertNotNull(second)
        assertNull(third, "全局上限是2，第三个应被拒绝")
    }

    @Test
    fun `释放许可后应能重新获取`() {
        val guard = ModelConcurrencyGuard(maxGlobal = 1, maxPerUser = 0)

        val permit = guard.tryAcquire("user-1")
        assertNotNull(permit)
        assertNull(guard.tryAcquire("user-2"), "名额已被占满")

        permit!!.release()

        assertNotNull(guard.tryAcquire("user-2"), "释放后应能重新获取")
    }

    @Test
    fun `按用户限流不应影响其他用户`() {
        val guard = ModelConcurrencyGuard(maxGlobal = 0, maxPerUser = 1)

        val userAFirst = guard.tryAcquire("user-a")
        val userASecond = guard.tryAcquire("user-a")
        val userBFirst = guard.tryAcquire("user-b")

        assertNotNull(userAFirst)
        assertNull(userASecond, "user-a 已经占满自己的名额")
        assertNotNull(userBFirst, "user-b 的名额与 user-a 无关")
    }

    @Test
    fun `用户维度拒绝时应把已占用的全局名额还回去`() {
        val guard = ModelConcurrencyGuard(maxGlobal = 5, maxPerUser = 1)

        guard.tryAcquire("user-a")
        val rejected = guard.tryAcquire("user-a")
        assertNull(rejected, "user-a 的每用户名额已满")

        // 全局名额应该没有因为上面被拒绝的那次尝试而泄漏：user-b 仍然可以正常拿到属于自己的名额。
        assertNotNull(guard.tryAcquire("user-b"))
        assertNotNull(guard.tryAcquire("user-c"))
        assertNotNull(guard.tryAcquire("user-d"))
        assertNotNull(guard.tryAcquire("user-e"))
        // 此时全局名额（5个：user-a成功的1个 + b/c/d/e共4个）应该刚好用满。
        assertNull(guard.tryAcquire("user-f"), "全局上限5个已经用满，不应该因为泄漏而多出名额")
    }

    @Test
    fun `未传userId且按用户限流时应跳过用户维度检查`() {
        val guard = ModelConcurrencyGuard(maxGlobal = 0, maxPerUser = 1)

        assertNotNull(guard.tryAcquire(null))
        assertNotNull(guard.tryAcquire(null))
        assertNotNull(guard.tryAcquire(""))
    }
}
