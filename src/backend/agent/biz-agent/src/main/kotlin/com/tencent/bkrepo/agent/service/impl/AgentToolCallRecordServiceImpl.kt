/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.service.impl

import com.tencent.bkrepo.agent.dao.AgentToolCallDao
import com.tencent.bkrepo.agent.model.TAgentToolCall
import com.tencent.bkrepo.agent.pojo.AgentToolCallDecision
import com.tencent.bkrepo.agent.pojo.AgentToolResultState
import com.tencent.bkrepo.agent.retention.AgentRetentionPolicy
import com.tencent.bkrepo.agent.service.AgentToolCallRecordService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class AgentToolCallRecordServiceImpl(
    private val agentToolCallDao: AgentToolCallDao,
    private val retentionPolicy: AgentRetentionPolicy,
) : AgentToolCallRecordService {

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
        try {
            agentToolCallDao.insertCalled(
                TAgentToolCall(
                    runId = runId,
                    threadId = threadId,
                    userId = userId,
                    projectId = projectId,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argsDigest = argsDigest,
                    decision = initialDecision,
                    calledAt = LocalDateTime.now(),
                    expiresAt = retentionPolicy.toolCallExpiry(),
                ),
            )
        } catch (ex: Exception) {
            logger.warn("failed to record tool call[run=$runId, toolCallId=$toolCallId, tool=$toolName]", ex)
        }
    }

    override fun recordAsking(runId: String, toolCallId: String) {
        runCatching { agentToolCallDao.markAsking(runId, toolCallId, LocalDateTime.now()) }
            .onFailure { logger.warn("failed to record tool call asking[run=$runId, toolCallId=$toolCallId]", it) }
    }

    override fun recordRuleDenied(runId: String, toolCallId: String) {
        runCatching {
            agentToolCallDao.markDenied(runId, toolCallId, AgentToolCallDecision.RULE_DENIED, LocalDateTime.now())
        }.onFailure { logger.warn("failed to record tool call rule-denied[run=$runId, toolCallId=$toolCallId]", it) }
    }

    override fun recordUserDenied(originRunId: String, toolCallId: String) {
        runCatching {
            agentToolCallDao.markDenied(
                originRunId,
                toolCallId,
                AgentToolCallDecision.ASK_DENIED,
                LocalDateTime.now(),
            )
        }.onFailure {
            logger.warn("failed to record tool call user-denied[run=$originRunId, toolCallId=$toolCallId]", it)
        }
    }

    override fun recordExecuted(
        runId: String,
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
    ) {
        runCatching {
            agentToolCallDao.markExecuted(runId, toolCallId, resultState, resultDigest, LocalDateTime.now())
        }.onFailure { logger.warn("failed to record tool call executed[run=$runId, toolCallId=$toolCallId]", it) }
    }

    override fun recordClientReportedResult(
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
    ) {
        runCatching {
            agentToolCallDao.updateClientReportedResult(toolCallId, resultState, resultDigest, LocalDateTime.now())
        }.onFailure { logger.warn("failed to record client-reported tool result[toolCallId=$toolCallId]", it) }
    }

    override fun removeByThreadId(threadId: String) {
        runCatching { agentToolCallDao.removeByThreadId(threadId) }
            .onFailure { logger.warn("failed to remove tool call records[threadId=$threadId]", it) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentToolCallRecordServiceImpl::class.java)
    }
}
