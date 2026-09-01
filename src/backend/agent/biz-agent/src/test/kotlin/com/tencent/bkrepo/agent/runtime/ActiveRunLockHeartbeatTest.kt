/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

@DisplayName("会话锁续期间隔推导")
class ActiveRunLockHeartbeatTest {

    @Test
    fun `默认锁TTL下应取三分之一`() {
        assertEquals(
            Duration.ofMinutes(11).dividedBy(3),
            ActiveRunLockHeartbeat.renewInterval(Duration.ofMinutes(11)),
        )
    }

    @Test
    fun `极短锁TTL也应保住最小间隔避免续期把Redis打满`() {
        assertEquals(Duration.ofSeconds(5), ActiveRunLockHeartbeat.renewInterval(Duration.ofSeconds(3)))
    }

    @Test
    fun `间隔必须留出连续两次失败的余量`() {
        listOf(Duration.ofSeconds(30), Duration.ofMinutes(11), Duration.ofHours(1)).forEach { ttl ->
            val interval = ActiveRunLockHeartbeat.renewInterval(ttl)
            assertTrue(
                interval.multipliedBy(2) < ttl,
                "间隔[$interval]必须小于锁TTL[$ttl]的一半，否则一次续期失败就可能丢锁",
            )
        }
    }
}
