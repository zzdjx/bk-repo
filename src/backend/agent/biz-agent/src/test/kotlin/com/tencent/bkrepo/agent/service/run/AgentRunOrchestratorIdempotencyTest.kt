/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2024 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.config.properties.AgentRuntimeProperties
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import com.tencent.bkrepo.agent.service.AgentSessionService
import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import com.tencent.bkrepo.common.api.exception.TooManyRequestsException
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.shutdown.GracefulShutdownManager
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDateTime

/**
 * 守住 POST /run 入口上两件容易一起退化的事：幂等回放必须校验 runId 归属（否则是越权读），
 * 以及幂等判定和抢运行权都必须排在 [AgentRunInputPreparer.prepare] 之前（否则没能开始执行的请求
 * 会白白吃掉一个 resume 幂等名额）。
 */
@DisplayName("AgentRunOrchestrator幂等与越权单测")
class AgentRunOrchestratorIdempotencyTest {

    private val inputPreparer = mock<AgentRunInputPreparer>()
    private val activeRunManager = mock<ActiveRunManager>()
    private val runRecordService = mock<AgentRunRecordService>()
    private val streamOrchestrator = mock<AgentRunStreamOrchestrator>()

    @Test
    fun `runId属于别的thread时应拒绝且不回放事件`() {
        whenever(runRecordService.findByRunId("run-1")).thenReturn(run(threadId = "someone-else-thread"))

        assertThrows(ParameterInvalidException::class.java) {
            orchestrator().run(USER, PROJECT, input())
        }

        verify(streamOrchestrator, never()).replayTerminalForRun(any(), any())
    }

    @Test
    fun `runId属于别的thread且仍在运行时也应拒绝_不能泄漏运行状态`() {
        whenever(runRecordService.findByRunId("run-1"))
            .thenReturn(run(threadId = "someone-else-thread", status = AgentRunStatus.RUNNING))

        // 若先判状态再判归属，这里会变成 TooManyRequestsException，等于告诉调用方"这个 runId 存在且在跑"。
        assertThrows(ParameterInvalidException::class.java) {
            orchestrator().run(USER, PROJECT, input())
        }
    }

    @Test
    fun `runId属于同一个thread且已终态时应回放`() {
        val existing = run(threadId = THREAD, status = AgentRunStatus.COMPLETED)
        val replayed = SseEmitter()
        whenever(runRecordService.findByRunId("run-1")).thenReturn(existing)
        whenever(streamOrchestrator.replayTerminalForRun(any(), eq(existing))).thenReturn(replayed)

        assertSame(replayed, orchestrator().run(USER, PROJECT, input()))
    }

    @Test
    fun `幂等回放命中时不应调用prepare_不消耗resume幂等名额`() {
        val existing = run(threadId = THREAD, status = AgentRunStatus.COMPLETED)
        whenever(runRecordService.findByRunId("run-1")).thenReturn(existing)
        whenever(streamOrchestrator.replayTerminalForRun(any(), eq(existing))).thenReturn(SseEmitter())

        orchestrator().run(USER, PROJECT, input())

        verify(inputPreparer, never()).prepare(any())
        verify(activeRunManager, never()).tryAcquire(any())
    }

    @Test
    fun `抢运行权失败时不应调用prepare_不消耗resume幂等名额`() {
        whenever(activeRunManager.tryAcquire(any())).thenReturn(false)

        assertThrows(TooManyRequestsException::class.java) {
            orchestrator().run(USER, PROJECT, input())
        }

        verify(inputPreparer, never()).prepare(any())
    }

    private fun run(
        threadId: String,
        status: AgentRunStatus = AgentRunStatus.COMPLETED,
    ) = TAgentRun(
        runId = "run-1",
        threadId = threadId,
        userId = USER,
        projectId = PROJECT,
        status = status,
        startedAt = LocalDateTime.now(),
    )

    private fun input(): RunAgentInput = RunAgentInput.builder()
        .threadId(THREAD)
        .runId("run-1")
        .build()

    private fun orchestrator() = AgentRunOrchestrator(
        runtimeProperties = AgentRuntimeProperties(),
        shutdownManager = mock<GracefulShutdownManager>().apply {
            whenever(isAcceptingRequests).thenReturn(true)
        },
        permissionManager = mock<PermissionManager>(),
        agentSessionService = mock<AgentSessionService>(),
        agentRunRecordService = runRecordService,
        activeRunManager = activeRunManager,
        inputValidator = mock<RunAgentInputValidator>(),
        inputPreparer = inputPreparer,
        runStarter = mock<AgentRunStarter>(),
        agentRunStreamOrchestrator = streamOrchestrator,
        lifecycleManager = mock<AgentRunLifecycleManager>(),
        eventPipeline = mock<AgentRunEventPipeline>(),
    )

    companion object {
        private const val USER = "user-1"
        private const val PROJECT = "project-1"
        private const val THREAD = "thread-1"
    }
}
