/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.retention

import com.mongodb.client.result.UpdateResult
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRetention
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.model.TAgentMessage
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.model.TAgentRunEvent
import com.tencent.bkrepo.agent.model.TAgentSession
import com.tencent.bkrepo.agent.model.TAgentToolCall
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Duration

@DisplayName("AgentRetentionBackfill单测")
class AgentRetentionBackfillTest {

    private val mongoTemplate = mock<MongoTemplate>()

    @Test
    fun `应为五张表各补一次戳且只匹配缺expiresAt字段的文档`() {
        stubUpdateResult(modified = 3)

        backfillWith(EffectiveAgentRetention.defaults())

        val queries = argumentCaptor<Query>()
        val updates = argumentCaptor<Update>()
        val types = argumentCaptor<Class<*>>()
        verify(mongoTemplate, times(5)).updateMulti(queries.capture(), updates.capture(), types.capture())

        assertEquals(
            listOf(
                TAgentRunEvent::class.java,
                TAgentRun::class.java,
                TAgentToolCall::class.java,
                TAgentMessage::class.java,
                TAgentSession::class.java,
            ),
            types.allValues,
        )
        queries.allValues.forEach {
            val condition = it.queryObject["expiresAt"] as Document
            assertEquals(false, condition["\$exists"], "backfill 只应匹配缺 expiresAt 字段的历史文档")
        }
        updates.allValues.forEach {
            val set = it.updateObject["\$set"] as Document
            assertTrue(set.containsKey("expiresAt"))
        }
    }

    @Test
    fun `保留期配成永不过期的集合应跳过不补戳`() {
        stubUpdateResult(modified = 0)

        backfillWith(EffectiveAgentRetention.defaults().copy(message = Duration.ZERO, run = Duration.ZERO))

        verify(mongoTemplate, never()).updateMulti(any(), any<Update>(), eq(TAgentMessage::class.java))
        verify(mongoTemplate, never()).updateMulti(any(), any<Update>(), eq(TAgentRun::class.java))
        verify(mongoTemplate).updateMulti(any(), any<Update>(), eq(TAgentSession::class.java))
    }

    @Test
    fun `单个集合补戳失败不应影响其它集合`() {
        stubUpdateResult(modified = 1)
        whenever(mongoTemplate.updateMulti(any(), any<Update>(), eq(TAgentRun::class.java)))
            .thenThrow(IllegalStateException("mongo down"))

        backfillWith(EffectiveAgentRetention.defaults())

        verify(mongoTemplate).updateMulti(any(), any<Update>(), eq(TAgentSession::class.java))
    }

    private fun stubUpdateResult(modified: Long) {
        val result = mock<UpdateResult>()
        whenever(result.modifiedCount).thenReturn(modified)
        whenever(mongoTemplate.updateMulti(any(), any<Update>(), any<Class<*>>())).thenReturn(result)
    }

    private fun backfillWith(retention: EffectiveAgentRetention) {
        val properties = EffectiveAgentRuntimeProperties.defaults().copy(retention = retention)
        AgentRetentionBackfill(mongoTemplate, AgentRetentionPolicy(properties)).backfillMissingExpiry()
    }
}
