/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.impl

import com.tencent.bkrepo.agent.dao.AgentUsageDailyDao
import com.tencent.bkrepo.agent.service.AgentUsageDailyService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class AgentUsageDailyServiceImpl(
    private val agentUsageDailyDao: AgentUsageDailyDao,
) : AgentUsageDailyService {

    /**
     * 尽力而为写入：失败重试一次，仍失败则只记日志——用量统计不应影响模型调用主链路。
     * 参考 [com.tencent.bkrepo.agent.service.impl.AgentMessageArchiveServiceImpl.insertWithRetry] 的容错模式。
     */
    override fun recordModelCall(
        userId: String,
        projectId: String,
        agentId: String,
        modelName: String,
        success: Boolean,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        durationMs: Long,
    ) {
        val usageDate = LocalDate.now()
        try {
            agentUsageDailyDao.incrementUsage(
                usageDate, userId, projectId, agentId, modelName,
                success, inputTokens, outputTokens, cachedTokens, durationMs,
            )
        } catch (first: Exception) {
            logger.warn("failed to record agent usage[user=$userId, agent=$agentId, model=$modelName], retrying", first)
            try {
                agentUsageDailyDao.incrementUsage(
                    usageDate, userId, projectId, agentId, modelName,
                    success, inputTokens, outputTokens, cachedTokens, durationMs,
                )
            } catch (second: Exception) {
                logger.error("failed to record agent usage[user=$userId, agent=$agentId, model=$modelName] after retry", second)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentUsageDailyServiceImpl::class.java)
    }
}
