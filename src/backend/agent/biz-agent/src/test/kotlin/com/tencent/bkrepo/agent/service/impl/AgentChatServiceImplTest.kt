/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2024 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 */

package com.tencent.bkrepo.agent.service.impl

import com.tencent.bkrepo.agent.hitl.DefaultAgentInterruptStateRepository
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import com.tencent.bkrepo.agent.service.AgentSessionService
import com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator
import com.tencent.bkrepo.agent.service.run.AgentRunStreamOrchestrator
import com.tencent.bkrepo.agent.session.AgentResumeIdempotencyStore
import com.tencent.bkrepo.agent.session.InMemoryAgentPendingInterruptStore
import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 状态接口报的 `hasPendingInterrupt` 必须和 resume 校验看到的是同一件事。
 *
 * 这里曾经直接问底层 `AgentPendingInterruptStore`，绕过了会过滤过期卡片的仓储门面：整组卡片过期后
 * 状态接口说"有未决中断"、前端照常渲染确认卡片，用户一点 resume 却被告知没有未决中断。
 */
@DisplayName("AgentChatServiceImpl运行状态单测")
class AgentChatServiceImplTest {

    private val pendingInterruptStore = InMemoryAgentPendingInterruptStore()
    private val interruptStateRepository = DefaultAgentInterruptStateRepository(
        pendingInterruptStore,
        mock<AgentResumeIdempotencyStore>(),
    )

    @Test
    fun `确认卡片整组过期后状态接口不应再报有未决中断`() {
        savePending(Instant.now().minus(1, ChronoUnit.HOURS))

        assertFalse(service().getRunStatus(USER, PROJECT, THREAD).hasPendingInterrupt)
    }

    @Test
    fun `确认卡片未过期时状态接口应报有未决中断`() {
        savePending(Instant.now().plus(1, ChronoUnit.HOURS))

        assertTrue(service().getRunStatus(USER, PROJECT, THREAD).hasPendingInterrupt)
    }

    @Test
    fun `没有未决中断时状态接口应报false`() {
        assertFalse(service().getRunStatus(USER, PROJECT, THREAD).hasPendingInterrupt)
    }

    private fun savePending(expiresAt: Instant) {
        pendingInterruptStore.save(
            THREAD,
            PendingInterruptSession(
                originRunId = "run-1",
                interrupts = listOf(
                    PendingInterruptSnapshot(id = "i-1", reason = "tool_call", expiresAt = expiresAt.toString()),
                ),
            ),
        )
    }

    private fun service() = AgentChatServiceImpl(
        permissionManager = mock<PermissionManager>(),
        agentSessionService = mock<AgentSessionService>(),
        agentRunOrchestrator = mock<AgentRunOrchestrator>(),
        agentRunStreamOrchestrator = mock<AgentRunStreamOrchestrator>(),
        agentRunRecordService = mock<AgentRunRecordService>(),
        activeRunManager = mock<ActiveRunManager>(),
        interruptStateRepository = interruptStateRepository,
    )

    companion object {
        private const val USER = "user-1"
        private const val PROJECT = "project-1"
        private const val THREAD = "thread-1"
    }
}
