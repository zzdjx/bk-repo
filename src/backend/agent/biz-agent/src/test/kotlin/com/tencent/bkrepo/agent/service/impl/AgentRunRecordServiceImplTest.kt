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

import com.tencent.bkrepo.agent.dao.AgentRunDao
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.pojo.AgentRunTriggerType
import com.tencent.bkrepo.agent.retention.AgentRetentionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [AgentRunRecordServiceImpl] 里唯一的进程内状态是 `startedAtByRunId`，它只为算 `durationMs` 存在。
 * 这组用例盯的是它不会随服务运行时长单调增长——收尾取用后必须立刻移除，入库失败也要回滚。
 */
@DisplayName("AgentRunRecordServiceImpl进程内状态单测")
class AgentRunRecordServiceImplTest {

    private val agentRunDao = mock<AgentRunDao>()
    private val service = AgentRunRecordServiceImpl(agentRunDao, mock<AgentRetentionPolicy>())

    @Test
    fun `收尾之后不应再残留startedAt`() {
        whenever(agentRunDao.finishRun(any(), any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(true)
        service.startRun(RUN, "exec-1", "thread-1", "user-1", "project-1", null, "coordinator", AgentRunTriggerType.USER_INPUT)

        service.finishRun(RUN, AgentRunStatus.COMPLETED)

        assertEquals(0, startedAtSize(), "startedAtByRunId 必须在收尾时清空")
    }

    @Test
    fun `入库失败时应回滚startedAt`() {
        whenever(agentRunDao.insertRun(any())).thenThrow(RuntimeException("mongo down"))

        service.startRun(RUN, "exec-1", "thread-1", "user-1", "project-1", null, "coordinator", AgentRunTriggerType.USER_INPUT)

        assertEquals(0, startedAtSize(), "insert 失败的 run 不会再有人来收尾，不能留下条目")
    }

    @Test
    fun `重复收尾仍应委派给dao去重_不靠进程内集合`() {
        // 跨副本的重复收尾进程内集合根本看不见，去重只能落在 dao 的 `status = RUNNING` 条件上。
        whenever(agentRunDao.finishRun(any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(true).thenReturn(false)
        service.startRun(RUN, "exec-1", "thread-1", "user-1", "project-1", null, "coordinator", AgentRunTriggerType.USER_INPUT)

        service.finishRun(RUN, AgentRunStatus.COMPLETED)
        service.finishRun(RUN, AgentRunStatus.COMPLETED)

        verify(agentRunDao, times(2)).finishRun(any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertEquals(0, startedAtSize())
    }

    private fun startedAtSize(): Int {
        val field = AgentRunRecordServiceImpl::class.java.getDeclaredField("startedAtByRunId")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(service) as Map<String, *>).size
    }

    companion object {
        private const val RUN = "run-1"
    }
}
