/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.usage

import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_RUN_ID
import com.tencent.bkrepo.agent.service.AgentRunRecordService
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
 * 落库用量统计：监听框架原生 [ModelCallEndEvent] 拿到 `ChatUsage`（input/output/cached token），
 * 按 runId 旁路累加进对应的 `agent_run` 记录（[com.tencent.bkrepo.agent.model.TAgentRun]），不改变原始事件流。
 *
 * 用量挂在"一次 HTTP run"而不是按天聚合，是因为一次 run 内本来就可能发生多次模型调用（ReAct 工具调用循环、
 * 历史压缩摘要调用等），需要 [io.agentscope.core.model.ChatUsage] 逐次累加；同时避免"新会话被合并进同一条
 * 按天记录"的问题——每个 runId 天然是独立的一条记录。
 *
 * 只有携带 [RUNTIME_CONTEXT_RUN_ID] 的调用才计入——标题生成、摘要等内部隐藏 Agent 不经过
 * [com.tencent.bkrepo.agent.context.AgentChatContext.toRuntimeContext] 装配，跳过而不是硬凑一个不存在的 runId。
 */
@Component
class UsageTrackingMiddleware(
    private val agentRunRecordService: AgentRunRecordService,
) : MiddlewareBase {

    override fun onModelCall(
        agent: Agent,
        ctx: RuntimeContext,
        input: ModelCallInput,
        next: Function<ModelCallInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val runId = ctx.get<String>(RUNTIME_CONTEXT_RUN_ID)
        if (runId.isNullOrBlank()) {
            return next.apply(input)
        }

        return next.apply(input)
            .doOnNext { event ->
                if (event is ModelCallEndEvent) {
                    runCatching {
                        val usage = event.usage
                        agentRunRecordService.recordModelCallUsage(
                            runId = runId,
                            inputTokens = usage?.inputTokens?.toLong() ?: 0,
                            outputTokens = usage?.outputTokens?.toLong() ?: 0,
                            cachedTokens = usage?.cachedTokens?.toLong() ?: 0,
                            durationMs = usage?.time?.let { (it * 1000).toLong() } ?: 0,
                        )
                    }.onFailure { logger.warn("failed to observe model call usage for run[$runId]", it) }
                }
            }
            .doOnError {
                runCatching {
                    agentRunRecordService.recordModelCallUsage(runId = runId)
                }.onFailure { ex -> logger.warn("failed to observe model call failure for run[$runId]", ex) }
            }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UsageTrackingMiddleware::class.java)
    }
}
