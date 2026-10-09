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

import com.tencent.bkrepo.agent.dao.AgentRunDao
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.pojo.AgentRunTriggerType
import com.tencent.bkrepo.agent.retention.AgentRetentionPolicy
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Service
class AgentRunRecordServiceImpl(
    private val agentRunDao: AgentRunDao,
    private val retentionPolicy: AgentRetentionPolicy,
) : AgentRunRecordService {

    /**
     * 只为算 `durationMs`：run 记录里的 `startedAt` 是入库时写的，收尾时再查一次库只为了算个时长不划算，
     * 所以在本副本内存一份。[finishRun] 取用后立即 `remove`，不会随服务运行时长单调增长。
     *
     * 这里没有"已收尾 runId"的去重集合：重复收尾由 `AgentRunDao.finishRun` 带的 `status = RUNNING`
     * 条件挡住，第二次更新匹配不到行、返回 0，天然幂等且跨副本有效。额外维护一个进程内集合既管不住跨副本
     * 的重复，又因为成功路径上没人删而只增不减——那正是之前的实现，每服务一个 run 就永久多占一条。
     */
    private val startedAtByRunId = ConcurrentHashMap<String, LocalDateTime>()

    override fun findByRunId(runId: String): TAgentRun? = agentRunDao.findByRunId(runId)

    override fun findLatestByThreadId(threadId: String): TAgentRun? =
        agentRunDao.findLatestByThreadId(threadId)

    override fun startRun(
        runId: String,
        executionId: String,
        threadId: String,
        userId: String,
        projectId: String,
        deviceId: String?,
        entryAgentId: String,
        triggerType: AgentRunTriggerType,
    ) {
        val startedAt = LocalDateTime.now()
        startedAtByRunId[runId] = startedAt
        try {
            agentRunDao.insertRun(
                TAgentRun(
                    runId = runId,
                    executionId = executionId,
                    threadId = threadId,
                    userId = userId,
                    projectId = projectId,
                    deviceId = deviceId,
                    status = AgentRunStatus.RUNNING,
                    entryAgentId = entryAgentId,
                    triggerType = triggerType,
                    startedAt = startedAt,
                    expiresAt = retentionPolicy.runExpiry(projectId, userId),
                ),
            )
        } catch (ex: Exception) {
            startedAtByRunId.remove(runId)
            logger.warn("failed to insert agent run[$runId]", ex)
        }
    }

    override fun finishRun(
        runId: String,
        status: AgentRunStatus,
        cancelReason: String?,
        errorCode: String?,
    ) {
        val startedAt = startedAtByRunId.remove(runId) ?: LocalDateTime.now()
        val finishedAt = LocalDateTime.now()
        try {
            val updated = agentRunDao.finishRun(
                runId = runId,
                status = status,
                finishedAt = finishedAt,
                startedAt = startedAt,
                cancelReason = cancelReason,
                errorCode = errorCode,
            )
            if (!updated) {
                logger.debug("agent run[$runId] already finished or missing")
            }
        } catch (ex: Exception) {
            logger.warn("failed to finish agent run[$runId]", ex)
        }
    }

    override fun removeByThreadId(threadId: String) {
        agentRunDao.removeByThreadId(threadId)
    }

    override fun recordModelCallUsage(
        runId: String,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        durationMs: Long,
    ) {
        try {
            agentRunDao.incrementUsage(runId, inputTokens, outputTokens, cachedTokens, durationMs)
        } catch (ex: Exception) {
            logger.warn("failed to record model call usage for run[$runId]", ex)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentRunRecordServiceImpl::class.java)
    }
}
