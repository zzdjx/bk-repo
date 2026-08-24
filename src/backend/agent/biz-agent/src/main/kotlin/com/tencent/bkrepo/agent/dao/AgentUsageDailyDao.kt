/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.dao

import com.tencent.bkrepo.agent.model.TAgentUsageDaily
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

@Repository
class AgentUsageDailyDao : SimpleMongoDao<TAgentUsageDaily>() {

    /**
     * 按 (usageDate, userId, projectId, agentId, modelName) 维度累加一次模型调用的用量。
     *
     * 用 upsert + `$inc` 做原子累加，允许同一维度的并发调用安全叠加，不做“先查后写”。
     */
    fun incrementUsage(
        usageDate: LocalDate,
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
        val query = Query(
            Criteria.where(TAgentUsageDaily::usageDate.name).`is`(usageDate)
                .and(TAgentUsageDaily::userId.name).`is`(userId)
                .and(TAgentUsageDaily::projectId.name).`is`(projectId)
                .and(TAgentUsageDaily::agentId.name).`is`(agentId)
                .and(TAgentUsageDaily::modelName.name).`is`(modelName),
        )
        val successField = if (success) {
            TAgentUsageDaily::successCount.name
        } else {
            TAgentUsageDaily::failureCount.name
        }
        val update = Update()
            .inc(TAgentUsageDaily::modelCallCount.name, 1)
            .inc(successField, 1)
            .inc(TAgentUsageDaily::inputTokens.name, inputTokens)
            .inc(TAgentUsageDaily::outputTokens.name, outputTokens)
            .inc(TAgentUsageDaily::cachedTokens.name, cachedTokens)
            .inc(TAgentUsageDaily::totalDurationMs.name, durationMs)
            .set(TAgentUsageDaily::updatedAt.name, LocalDateTime.now())
            .setOnInsert(TAgentUsageDaily::usageDate.name, usageDate)
            .setOnInsert(TAgentUsageDaily::userId.name, userId)
            .setOnInsert(TAgentUsageDaily::projectId.name, projectId)
            .setOnInsert(TAgentUsageDaily::agentId.name, agentId)
            .setOnInsert(TAgentUsageDaily::modelName.name, modelName)
        upsert(query, update)
    }
}
