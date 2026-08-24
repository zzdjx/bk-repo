/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.usage

import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_PROJECT_ID
import com.tencent.bkrepo.agent.service.AgentUsageDailyService
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.ModelCallEndEvent
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.middleware.ModelCallInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.function.Function

/**
 * 落库 §11.3 用量统计：监听框架原生 [ModelCallEndEvent] 拿到 `ChatUsage`（input/output/cached token），
 * 按 (日期, userId, projectId, agentId, modelName) 维度旁路累加进 `agent_usage_daily`，不改变原始事件流。
 *
 * 只有携带 [RUNTIME_CONTEXT_PROJECT_ID] 的调用才计入——标题生成、摘要等内部隐藏 Agent 不经过
 * [com.tencent.bkrepo.agent.context.AgentChatContext.toRuntimeContext] 装配，跳过而不是用占位值污染聚合维度。
 */
@Component
class UsageTrackingMiddleware(
    private val agentUsageDailyService: AgentUsageDailyService,
) : MiddlewareBase {

    override fun onModelCall(
        agent: Agent,
        ctx: RuntimeContext,
        input: ModelCallInput,
        next: Function<ModelCallInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val projectId = ctx.get<String>(RUNTIME_CONTEXT_PROJECT_ID)
        if (projectId.isNullOrBlank()) {
            return next.apply(input)
        }
        val userId = ctx.userId.orEmpty()
        val agentId = agent.name
        val modelName = input.model()?.modelName ?: "unknown"

        return next.apply(input)
            .doOnNext { event ->
                if (event is ModelCallEndEvent) {
                    runCatching {
                        val usage = event.usage
                        agentUsageDailyService.recordModelCall(
                            userId = userId,
                            projectId = projectId,
                            agentId = agentId,
                            modelName = modelName,
                            success = true,
                            inputTokens = usage?.inputTokens?.toLong() ?: 0,
                            outputTokens = usage?.outputTokens?.toLong() ?: 0,
                            cachedTokens = usage?.cachedTokens?.toLong() ?: 0,
                            durationMs = usage?.time?.let { (it * 1000).toLong() } ?: 0,
                        )
                    }.onFailure { logger.warn("failed to observe model call usage for agent[$agentId]", it) }
                }
            }
            .doOnError {
                runCatching {
                    agentUsageDailyService.recordModelCall(
                        userId = userId,
                        projectId = projectId,
                        agentId = agentId,
                        modelName = modelName,
                        success = false,
                    )
                }.onFailure { ex -> logger.warn("failed to observe model call failure for agent[$agentId]", ex) }
            }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UsageTrackingMiddleware::class.java)
    }
}
