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
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.subagent

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.middleware.ReasoningInput
import io.agentscope.harness.agent.subagent.task.TaskRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.function.Function

/**
 * 委派并发预算的"软限流"那一半，覆盖 [com.tencent.bkrepo.agent.task.LimitedWorkspaceTaskRepository]
 * 无法触达的同步委派路径（`agent_spawn`/`agent_send` 在 `timeout_seconds > 0` 且未超时的情况下，
 * 全程不经过 `TaskRepository`）。
 *
 * ## 两个钩子的分工
 * - [onActing]：批量执行前，按本批次里 `agent_spawn`/`agent_send` 的调用数给
 *   [DelegationConcurrencyGuard] 加计数；批次结束（无论成功/失败/取消）后原样减回去。这一步只是
 *   记账，不拒绝任何调用——真正的硬拦截只发生在异步/超时 promote 路径（见
 *   [com.tencent.bkrepo.agent.task.LimitedWorkspaceTaskRepository]）。
 * - [onReasoning]：每轮推理前，把"当前未终态的后台任务数"（来自 [TaskRepository.listTasks]）与
 *   "当前同步在跑的委派数"（来自 [DelegationConcurrencyGuard]）相加，一旦达到或超过预算，就在这一轮
 *   额外注入一条 `<system-reminder>`，告诉模型停止新开委派、等现有的跑完。这是基于实时计数的动态
 *   提醒，比写死在系统提示词里的固定文案更准，但仍然是软约束——模型可以选择不听。
 *
 * 只有 `agent.runtime.topology.coordinator.enabled=true` 时才有意义：coordinator 关闭时
 * `agent_spawn`/`agent_send` 工具根本不会注册，两个钩子天然是 no-op。
 */
@Component
class DelegationBudgetMiddleware(
    private val properties: EffectiveAgentRuntimeProperties,
    private val guard: DelegationConcurrencyGuard,
    private val taskRepositoryProvider: ObjectProvider<TaskRepository>,
) : MiddlewareBase {

    override fun onActing(
        agent: Agent,
        ctx: RuntimeContext,
        input: ActingInput,
        next: Function<ActingInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val sessionId = ctx.sessionId
        val delegationCalls = input.toolCalls()?.count { it.name in DELEGATION_TOOL_NAMES } ?: 0
        if (sessionId.isNullOrBlank() || delegationCalls == 0) {
            return next.apply(input)
        }
        guard.acquire(sessionId, delegationCalls)
        return next.apply(input).doFinally { guard.release(sessionId, delegationCalls) }
    }

    override fun onReasoning(
        agent: Agent,
        ctx: RuntimeContext,
        input: ReasoningInput,
        next: Function<ReasoningInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val coordinator = properties.topology.coordinator
        val sessionId = ctx.sessionId
        if (!coordinator.enabled || sessionId.isNullOrBlank()) {
            return next.apply(input)
        }
        val maxParallel = coordinator.maxParallelDelegations
        val activeBackground = taskRepositoryProvider.getIfAvailable()
            ?.listTasks(ctx, sessionId, null)
            ?.count { !it.taskStatus.isTerminal }
            ?: 0
        val active = activeBackground + guard.inFlight(sessionId)
        if (active < maxParallel) {
            return next.apply(input)
        }
        val reminder = buildBudgetReminder(active, maxParallel)
        val rebuilt = ArrayList(input.messages())
        rebuilt.add(reminder)
        return next.apply(ReasoningInput(rebuilt, input.tools(), input.options()))
    }

    private fun buildBudgetReminder(active: Int, maxParallel: Int): Msg {
        val text = """
            <system-reminder>
            You currently have $active active delegation(s) (agent_spawn/agent_send, including
            background tasks), at or above this session's budget of $maxParallel. Do NOT start any
            new delegation right now. Either wait for an existing one to finish (check task_list()
            for background tasks), or do the work yourself instead of delegating.
            </system-reminder>
        """.trimIndent()
        return Msg.builder()
            .role(MsgRole.USER)
            .name("system")
            .content(TextBlock.builder().text(text).build())
            .metadata(mapOf(Msg.METADATA_SYNTHETIC to true, Msg.METADATA_REMINDER_KIND to "delegation_budget"))
            .build()
    }

    companion object {
        private val DELEGATION_TOOL_NAMES = setOf("agent_spawn", "agent_send")
    }
}
