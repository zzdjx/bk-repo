/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service

/**
 * 模型调用用量落库；实现须保证任何异常都不向上抛出，用量记录失败不能影响 Agent 主链路。
 */
interface AgentUsageDailyService {

    fun recordModelCall(
        userId: String,
        projectId: String,
        agentId: String,
        modelName: String,
        success: Boolean,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        cachedTokens: Long = 0,
        durationMs: Long = 0,
    )
}
