/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.usage

import com.tencent.bkrepo.agent.service.AgentUsageDailyService

/** 测试用空实现，供不关心用量落库、只需要跑通 HarnessAgent 的测试装配使用。 */
class NoopAgentUsageDailyService : AgentUsageDailyService {
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
    ) = Unit
}

/** 测试用录制实现，记录每次 [recordModelCall] 调用的入参，供断言用。 */
class RecordingAgentUsageDailyService : AgentUsageDailyService {

    data class Recorded(
        val userId: String,
        val projectId: String,
        val agentId: String,
        val modelName: String,
        val success: Boolean,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedTokens: Long,
        val durationMs: Long,
    )

    val calls = mutableListOf<Recorded>()

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
        calls.add(
            Recorded(
                userId, projectId, agentId, modelName, success,
                inputTokens, outputTokens, cachedTokens, durationMs,
            ),
        )
    }
}
