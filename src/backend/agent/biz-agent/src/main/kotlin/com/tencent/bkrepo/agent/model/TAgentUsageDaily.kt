/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.model

import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 按 (日期, 用户, 项目, Agent, 模型) 聚合的模型调用用量，对应 Mongo 集合 `agent_usage_daily`。
 *
 * 只统计模型调用（[com.tencent.bkrepo.agent.usage.UsageTrackingMiddleware] 落库），
 * 不含工具调用次数——工具级审计单独排期，届时再为该集合追加字段，不需要迁移脚本。
 */
@Document("agent_usage_daily")
@CompoundIndexes(
    CompoundIndex(
        name = "usageDate_userId_projectId_agentId_modelName_idx",
        def = "{'usageDate': 1, 'userId': 1, 'projectId': 1, 'agentId': 1, 'modelName': 1}",
        unique = true,
        background = true,
    ),
    CompoundIndex(
        name = "projectId_usageDate_idx",
        def = "{'projectId': 1, 'usageDate': -1}",
        background = true,
    ),
)
data class TAgentUsageDaily(
    var id: String? = null,
    var usageDate: LocalDate,
    var userId: String,
    var projectId: String,
    var agentId: String,
    var modelName: String,
    var modelCallCount: Long = 0,
    var successCount: Long = 0,
    var failureCount: Long = 0,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cachedTokens: Long = 0,
    var totalDurationMs: Long = 0,
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
