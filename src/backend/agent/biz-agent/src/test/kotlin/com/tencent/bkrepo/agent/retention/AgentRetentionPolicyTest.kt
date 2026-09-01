/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.retention

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRetention
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@DisplayName("AgentRetentionPolicy单测")
class AgentRetentionPolicyTest {

    @Test
    fun `默认配置下各集合的过期时刻应等于现在加上对应保留期`() {
        val retention = EffectiveAgentRetention.defaults()
        val policy = policyOf(retention)
        val before = Instant.now()

        assertWithin(policy.runEventExpiry(), before, retention.runEvent)
        assertWithin(policy.runExpiry(), before, retention.run)
        assertWithin(policy.toolCallExpiry(), before, retention.toolCall)
        assertWithin(policy.messageExpiry(), before, retention.message)
        assertWithin(policy.sessionExpiry(), before, retention.session)
    }

    @Test
    fun `保留期配成0表示永不过期应返回null`() {
        val policy = policyOf(
            EffectiveAgentRetention.defaults().copy(
                runEvent = Duration.ZERO,
                run = Duration.ZERO,
                toolCall = Duration.ZERO,
                message = Duration.ZERO,
                session = Duration.ZERO,
            ),
        )

        assertNull(policy.runEventExpiry())
        assertNull(policy.runExpiry())
        assertNull(policy.toolCallExpiry())
        assertNull(policy.messageExpiry())
        assertNull(policy.sessionExpiry())
    }

    @Test
    fun `保留期配成负值时按永不过期处理而不是立即过期`() {
        val policy = policyOf(EffectiveAgentRetention.defaults().copy(run = Duration.ofDays(-1)))

        assertNull(policy.runExpiry())
    }

    @Test
    fun `单独调整一项不应影响其它集合`() {
        val policy = policyOf(EffectiveAgentRetention.defaults().copy(message = Duration.ZERO))

        assertNull(policy.messageExpiry())
        assertNotNull(policy.sessionExpiry())
        assertNotNull(policy.runExpiry())
    }

    private fun policyOf(retention: EffectiveAgentRetention) =
        AgentRetentionPolicy(EffectiveAgentRuntimeProperties.defaults().copy(retention = retention))

    private fun assertWithin(actual: Instant?, calledAt: Instant, retention: Duration) {
        assertNotNull(actual)
        val expectedFrom = calledAt.plus(retention)
        val expectedTo = expectedFrom.plusSeconds(TOLERANCE_SECONDS)
        assertTrue(
            !actual!!.isBefore(expectedFrom) && !actual.isAfter(expectedTo),
            "expected expiry in [$expectedFrom, $expectedTo] but was $actual",
        )
    }

    companion object {
        /** 只为吸收测试执行本身的耗时，不是业务容差。 */
        private const val TOLERANCE_SECONDS = 60L
    }
}
