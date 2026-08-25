/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.audit

import com.tencent.bkrepo.agent.pojo.AgentToolCallDecision
import com.tencent.bkrepo.agent.pojo.AgentToolResultState
import com.tencent.bkrepo.agent.service.AgentToolCallRecordService

/** 测试用空实现，供不关心工具审计落库、只需要跑通 HarnessAgent 的测试装配使用。 */
class NoopAgentToolCallRecordService : AgentToolCallRecordService {
    override fun recordCalled(
        runId: String,
        threadId: String,
        userId: String,
        projectId: String,
        toolCallId: String,
        toolName: String,
        argsDigest: String?,
        initialDecision: AgentToolCallDecision,
    ) = Unit
    override fun recordAsking(runId: String, toolCallId: String) = Unit
    override fun recordRuleDenied(runId: String, toolCallId: String) = Unit
    override fun recordUserDenied(originRunId: String, toolCallId: String) = Unit
    override fun recordExecuted(
        runId: String,
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
    ) = Unit
    override fun recordClientReportedResult(
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
    ) = Unit
    override fun removeByThreadId(threadId: String) = Unit
}

/** 测试用录制实现，记录每次生命周期方法调用的入参，供断言用。 */
class RecordingAgentToolCallRecordService : AgentToolCallRecordService {

    data class Called(
        val runId: String,
        val threadId: String,
        val userId: String,
        val projectId: String,
        val toolCallId: String,
        val toolName: String,
        val argsDigest: String?,
        val initialDecision: AgentToolCallDecision,
    )

    data class Executed(
        val runId: String,
        val toolCallId: String,
        val resultState: AgentToolResultState,
        val resultDigest: String?,
    )

    data class ClientReportedResult(
        val toolCallId: String,
        val resultState: AgentToolResultState,
        val resultDigest: String?,
    )

    val calledEvents = mutableListOf<Called>()
    val askingEvents = mutableListOf<Pair<String, String>>()
    val ruleDeniedEvents = mutableListOf<Pair<String, String>>()
    val userDeniedEvents = mutableListOf<Pair<String, String>>()
    val executedEvents = mutableListOf<Executed>()
    val clientReportedResultEvents = mutableListOf<ClientReportedResult>()

    override fun recordCalled(
        runId: String,
        threadId: String,
        userId: String,
        projectId: String,
        toolCallId: String,
        toolName: String,
        argsDigest: String?,
        initialDecision: AgentToolCallDecision,
    ) {
        calledEvents.add(
            Called(runId, threadId, userId, projectId, toolCallId, toolName, argsDigest, initialDecision),
        )
    }

    override fun recordAsking(runId: String, toolCallId: String) {
        askingEvents.add(runId to toolCallId)
    }

    override fun recordRuleDenied(runId: String, toolCallId: String) {
        ruleDeniedEvents.add(runId to toolCallId)
    }

    override fun recordUserDenied(originRunId: String, toolCallId: String) {
        userDeniedEvents.add(originRunId to toolCallId)
    }

    override fun recordExecuted(
        runId: String,
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
    ) {
        executedEvents.add(Executed(runId, toolCallId, resultState, resultDigest))
    }

    override fun recordClientReportedResult(
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
    ) {
        clientReportedResultEvents.add(ClientReportedResult(toolCallId, resultState, resultDigest))
    }

    override fun removeByThreadId(threadId: String) = Unit
}
