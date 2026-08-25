/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_PROJECT_ID
import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_RUN_ID
import com.tencent.bkrepo.agent.pojo.AgentToolCallDecision
import com.tencent.bkrepo.agent.pojo.AgentToolResultState
import com.tencent.bkrepo.agent.service.AgentToolCallRecordService
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.MiddlewareBase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * 工具级审计：记录每次工具调用尝试的调用方、工具名/参数、权限裁决、执行结果与耗时，落到
 * [com.tencent.bkrepo.agent.model.TAgentToolCall]（`agent_tool_call` 集合）。
 *
 * ## 为什么需要两个钩子，而不是只用 [onActing]
 *
 * 拍平后的本地写工具（[com.tencent.bkrepo.agent.tool.local.ExternalLocalTool]）在一次完整的
 * "确认 -> 执行" 生命周期里要经历两段挂起：
 * 1. `PermissionEngine` 判定 ASK -> `RequireUserConfirmEvent`，工具还没跑。
 * 2. 用户确认后，工具真正被放行调用 -> `callAsync` 立刻抛 `ToolSuspendException` -> 服务端视角的终态
 *    只能是"挂起转发给客户端"（[AgentToolResultState.RUNNING]），因为本地工具服务端从不真正执行。
 * 3. 客户端本地执行完、把结果通过 resume 回传——这一步命中的是 `TOOL_SUSPENDED` 的框架原生"纯状态替换"
 *    恢复路径（不会重新触发 `callAsync`，见 `ReActAgent.call` 对 `providedResults`/
 *    `validateAndAddToolResults` 的处理），**完全不经过 [onActing]**，因此单靠 [onActing] 永远只能
 *    观察到"已批准并转发给客户端"，看不到客户端真实的执行结果——这恰恰是写工具（风险最高、最需要留痕
 *    的一类）最关键的一环。
 *
 * 为补上这个缺口，第三段改用 [onAgent]：每次协调者被调用时，直接检查本次输入消息里是否携带了
 * `ToolResultBlock`（resume 场景下由框架标准 `AguiMessageConverter` 转换出来），如果其 toolCallId
 * 命中一行仍停在 [AgentToolResultState.RUNNING] 的记录，就用它的真实 `state` 补齐终态。
 *
 * 已知的剩余缺口：同一批工具调用里如果同时出现"需要 ASK"和"命中 DENY 规则"两种工具（罕见的并行调用
 * 场景），框架会整批直接返回 `RequireUserConfirmEvent` 并跳过 `runToolBatch`，此时被规则拒绝的那个
 * 工具调用不会有 `ToolResultEndEvent`，对应审计行会停在初始态。这是框架事件流本身的限制，代价与收益
 * 不成正比，本次不特殊处理。
 */
@Component
class ToolAuditMiddleware(
    private val agentToolCallRecordService: AgentToolCallRecordService,
    private val objectMapper: ObjectMapper,
) : MiddlewareBase {

    override fun onActing(
        agent: Agent,
        ctx: RuntimeContext,
        input: ActingInput,
        next: Function<ActingInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val runId = ctx.get(RUNTIME_CONTEXT_RUN_ID, String::class.java)
        if (runId.isNullOrBlank()) {
            return next.apply(input)
        }
        val threadId = ctx.sessionId
        val userId = ctx.userId
        if (threadId.isNullOrBlank() || userId.isNullOrBlank()) {
            return next.apply(input)
        }
        val projectId = ctx.get(RUNTIME_CONTEXT_PROJECT_ID, String::class.java).orEmpty()

        input.toolCalls()?.forEach { toolCall: ToolUseBlock ->
            agentToolCallRecordService.recordCalled(
                runId = runId,
                threadId = threadId,
                userId = userId,
                projectId = projectId,
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                argsDigest = truncate(toJson(toolCall.input)),
                initialDecision = AgentToolCallDecision.ALLOWED,
            )
        }

        val toolText = ConcurrentHashMap<String, StringBuilder>()
        return next.apply(input)
            .doOnNext { event ->
                when (event) {
                    is RequireUserConfirmEvent ->
                        event.toolCalls.forEach { toolCall ->
                            agentToolCallRecordService.recordAsking(runId, toolCall.id)
                        }
                    is ToolResultTextDeltaEvent ->
                        event.delta?.let { delta ->
                            toolText.computeIfAbsent(event.toolCallId) { StringBuilder() }.append(delta)
                        }
                    is ToolResultEndEvent -> {
                        val digest = truncate(toolText[event.toolCallId]?.toString())
                        if (event.state == ToolResultState.DENIED) {
                            agentToolCallRecordService.recordRuleDenied(runId, event.toolCallId)
                        } else {
                            agentToolCallRecordService.recordExecuted(
                                runId,
                                event.toolCallId,
                                event.state.toAgentToolResultState(),
                                digest,
                            )
                        }
                    }
                    else -> Unit
                }
            }
    }

    override fun onAgent(
        agent: Agent,
        ctx: RuntimeContext,
        input: AgentInput,
        next: Function<AgentInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        input.msgs()?.forEach { msg: Msg ->
            msg.getContentBlocks(ToolResultBlock::class.java)?.forEach { block ->
                val toolCallId = block.id?.takeIf { it.isNotBlank() } ?: return@forEach
                agentToolCallRecordService.recordClientReportedResult(
                    toolCallId = toolCallId,
                    resultState = block.state.toAgentToolResultState(),
                    resultDigest = truncate(extractTextDigest(block)),
                )
            }
        }
        return next.apply(input)
    }

    private fun extractTextDigest(block: ToolResultBlock): String? =
        block.output?.joinToString(separator = "\n") { it.toString() }

    private fun toJson(value: Any?): String? = if (value == null) {
        null
    } else {
        runCatching { objectMapper.writeValueAsString(value) }
            .getOrElse { logger.debug("failed to serialize tool call payload", it); null }
    }

    private fun truncate(text: String?): String? {
        if (text == null) return null
        if (text.length <= MAX_DIGEST_LENGTH) return text
        return text.substring(0, MAX_DIGEST_LENGTH) + "...[truncated, limit=$MAX_DIGEST_LENGTH chars]"
    }

    private fun ToolResultState.toAgentToolResultState(): AgentToolResultState = when (this) {
        ToolResultState.SUCCESS -> AgentToolResultState.SUCCESS
        ToolResultState.ERROR -> AgentToolResultState.ERROR
        ToolResultState.INTERRUPTED -> AgentToolResultState.INTERRUPTED
        ToolResultState.DENIED -> AgentToolResultState.DENIED
        ToolResultState.RUNNING -> AgentToolResultState.RUNNING
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ToolAuditMiddleware::class.java)
        private const val MAX_DIGEST_LENGTH = 2000
    }
}
