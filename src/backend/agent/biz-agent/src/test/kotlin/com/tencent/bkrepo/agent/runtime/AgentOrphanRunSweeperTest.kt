/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.dao.AgentRunDao
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@DisplayName("AgentOrphanRunSweeper单测")
class AgentOrphanRunSweeperTest {

    private val agentRunDao = mock<AgentRunDao>()
    private val activeRunManager = mock<ActiveRunManager>()

    @Test
    @DisplayName("运行权已经不绑定这个 run 了，说明持有它的副本没了，应收成 FAILED")
    fun `abandons runs whose owner replica is gone`() {
        val run = runningRun("run-dead")
        whenever(agentRunDao.findStaleRunning(any(), any())).thenReturn(listOf(run))
        whenever(activeRunManager.getActiveRunId(any())).thenReturn(null)
        whenever(agentRunDao.finishRun(any(), any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(true)

        sweeper().sweepOnce()

        verify(agentRunDao).finishRun(
            runId = eq("run-dead"),
            status = eq(AgentRunStatus.FAILED),
            finishedAt = any(),
            startedAt = eq(run.startedAt),
            cancelReason = eq(null),
            errorCode = eq(AgentOrphanRunSweeper.ERROR_CODE),
        )
    }

    @Test
    @DisplayName("跑过一个 TTL 但运行权仍然绑定着它的长对话不能被误杀")
    fun `keeps long running runs that still hold the lock`() {
        val run = runningRun("run-alive")
        whenever(agentRunDao.findStaleRunning(any(), any())).thenReturn(listOf(run))
        whenever(activeRunManager.getActiveRunId(any())).thenReturn("run-alive")

        sweeper().sweepOnce()

        verify(agentRunDao, never()).finishRun(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("存储异常不能让调度器静默停摆")
    fun `survives storage failures`() {
        whenever(agentRunDao.findStaleRunning(any(), any())).thenThrow(IllegalStateException("mongo down"))

        sweeper().sweepOnce()
    }

    private fun sweeper() =
        AgentOrphanRunSweeper(agentRunDao, activeRunManager, EffectiveAgentRuntimeProperties.defaults())

    private fun runningRun(runId: String) = TAgentRun(
        runId = runId,
        threadId = "thread-1",
        userId = "user-1",
        projectId = "project-1",
        status = AgentRunStatus.RUNNING,
        startedAt = LocalDateTime.now().minusHours(1),
    )
}
