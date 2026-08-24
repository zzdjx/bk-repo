/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.usage

import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.pojo.AgentRunTriggerType
import com.tencent.bkrepo.agent.service.AgentRunRecordService

/** 测试用空实现，供不关心用量落库、只需要跑通 HarnessAgent 的测试装配使用。 */
class NoopAgentRunRecordService : AgentRunRecordService {
    override fun findByRunId(runId: String): TAgentRun? = null
    override fun findLatestByThreadId(threadId: String): TAgentRun? = null
    override fun startRun(
        runId: String,
        executionId: String,
        threadId: String,
        userId: String,
        projectId: String,
        deviceId: String?,
        entryAgentId: String,
        triggerType: AgentRunTriggerType,
    ) = Unit
    override fun finishRun(
        runId: String,
        status: AgentRunStatus,
        cancelReason: String?,
        errorCode: String?,
    ) = Unit
    override fun removeByThreadId(threadId: String) = Unit
    override fun recordModelCallUsage(
        runId: String,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        durationMs: Long,
    ) = Unit
}

/** 测试用录制实现，记录每次 [recordModelCallUsage] 调用的入参，供断言用。 */
class RecordingAgentRunRecordService : AgentRunRecordService {

    data class Recorded(
        val runId: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedTokens: Long,
        val durationMs: Long,
    )

    val calls = mutableListOf<Recorded>()

    override fun findByRunId(runId: String): TAgentRun? = null
    override fun findLatestByThreadId(threadId: String): TAgentRun? = null
    override fun startRun(
        runId: String,
        executionId: String,
        threadId: String,
        userId: String,
        projectId: String,
        deviceId: String?,
        entryAgentId: String,
        triggerType: AgentRunTriggerType,
    ) = Unit
    override fun finishRun(
        runId: String,
        status: AgentRunStatus,
        cancelReason: String?,
        errorCode: String?,
    ) = Unit
    override fun removeByThreadId(threadId: String) = Unit

    override fun recordModelCallUsage(
        runId: String,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        durationMs: Long,
    ) {
        calls.add(Recorded(runId, inputTokens, outputTokens, cachedTokens, durationMs))
    }
}
