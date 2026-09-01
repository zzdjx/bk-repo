/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime.store

import com.tencent.bkrepo.agent.constant.AGENT_ACTIVE_RUN_KEY_PREFIX
import com.tencent.bkrepo.agent.constant.AGENT_RUN_LOCK_KEY_PREFIX
import com.tencent.bkrepo.common.redis.RedisOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 只覆盖续期路径：加锁走的是 [com.tencent.bkrepo.common.redis.RedisLock] 的原生 `SET NX EX`，
 * 需要真实 Lettuce 连接，不适合在单测里造。
 */
@DisplayName("Redis 会话锁续期")
class RedisActiveRunStateStoreTest {

    private val redisOperation: RedisOperation = mock()
    private val store = RedisActiveRunStateStore(
        redisOperation = redisOperation,
        lockTtlSeconds = 660,
        activeTtlSeconds = 660,
    )

    @Test
    fun `活跃run绑定仍是自己时应同时给锁与绑定续期`() {
        whenever(redisOperation.get(any())) doReturn "run-1"

        assertTrue(store.renewLock("user-1", "thread-1", "run-1"))

        verify(redisOperation).expire("${AGENT_RUN_LOCK_KEY_PREFIX}user-1:thread-1", 660)
        verify(redisOperation).expire("${AGENT_ACTIVE_RUN_KEY_PREFIX}user-1:thread-1", 660)
    }

    @Test
    fun `活跃run绑定已被别人接管时不应续期`() {
        whenever(redisOperation.get(any())) doReturn "run-theirs"

        assertFalse(store.renewLock("user-1", "thread-1", "run-mine"))

        verify(redisOperation, never()).expire(any(), any())
    }

    @Test
    fun `活跃run绑定已经消失时不应续期`() {
        whenever(redisOperation.get(any())) doReturn null

        assertFalse(store.renewLock("user-1", "thread-1", "run-1"))

        verify(redisOperation, never()).expire(any(), any())
    }

    @Test
    fun `锁与绑定的key前缀不应互相串用`() {
        whenever(redisOperation.get(any())) doReturn "run-1"

        store.renewLock("user-1", "thread-1", "run-1")

        // 两个 key 必须是不同的，否则续期只会作用在其中一个上
        val keys = mutableListOf<String>()
        verify(redisOperation, org.mockito.kotlin.times(2)).expire(
            org.mockito.kotlin.argThat { keys.add(this); true },
            any(),
        )
        assertEquals(2, keys.toSet().size)
    }
}
