/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2024 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.session.AgentResumeIdempotencyStore
import com.tencent.bkrepo.agent.session.InMemoryAgentPendingInterruptStore
import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 盯住会话锁死那条路：确认卡片的 `expiresAt` 按 `activeRunTtl`（11 分钟）填，而承载它的 Mongo 文档
 * 按 `sessionTtl`（30 天）回收。过期之后如果这里还报"有未决中断"，用户既 resume 不了也发不了新消息。
 */
@DisplayName("AgentInterruptStateRepository过期清理单测")
class AgentInterruptStateRepositoryTest {

    private val store = InMemoryAgentPendingInterruptStore()
    private val repository = DefaultAgentInterruptStateRepository(store, mock<AgentResumeIdempotencyStore>())

    @Test
    fun `整组卡片都过期时应视同没有未决中断`() {
        store.save(THREAD, session(snapshot("i-1", past()), snapshot("i-2", past())))

        assertNull(repository.getPendingInterrupt(THREAD))
    }

    @Test
    fun `整组卡片都过期时应顺手把记录删掉`() {
        store.save(THREAD, session(snapshot("i-1", past())))

        repository.getPendingInterrupt(THREAD)

        assertNull(store.get(THREAD), "过期的确认卡片不该再占着 thread")
    }

    @Test
    fun `卡片未过期时应原样返回`() {
        store.save(THREAD, session(snapshot("i-1", future())))

        assertEquals(1, repository.getPendingInterrupt(THREAD)?.interrupts?.size)
    }

    @Test
    fun `只有部分卡片过期时仍应要求resume`() {
        // 还有效的那张卡片不能被一起丢掉，过期的那张由 AguiResumeValidator 逐条拦。
        store.save(THREAD, session(snapshot("i-1", past()), snapshot("i-2", future())))

        assertEquals(2, repository.getPendingInterrupt(THREAD)?.interrupts?.size)
    }

    @Test
    fun `expiresAt缺失时按不过期处理`() {
        store.save(THREAD, session(snapshot("i-1", null)))

        assertNotNull(repository.getPendingInterrupt(THREAD))
    }

    @Test
    fun `expiresAt无法解析时按过期处理`() {
        store.save(THREAD, session(snapshot("i-1", "not-a-timestamp")))

        assertNull(repository.getPendingInterrupt(THREAD))
    }

    @Test
    fun `interrupts为空的快照不应被当成整组过期`() {
        store.save(THREAD, PendingInterruptSession(originRunId = "run-1", interrupts = emptyList()))

        assertNotNull(repository.getPendingInterrupt(THREAD))
    }

    private fun session(vararg interrupts: PendingInterruptSnapshot) =
        PendingInterruptSession(originRunId = "run-1", interrupts = interrupts.toList())

    private fun snapshot(id: String, expiresAt: String?) =
        PendingInterruptSnapshot(id = id, reason = "approval", expiresAt = expiresAt)

    private fun past() = Instant.now().minus(1, ChronoUnit.HOURS).toString()

    private fun future() = Instant.now().plus(1, ChronoUnit.HOURS).toString()

    companion object {
        private const val THREAD = "thread-1"
    }
}
