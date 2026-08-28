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

package com.tencent.bkrepo.agent.task

import com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.subagent.task.BackgroundTask
import io.agentscope.harness.agent.subagent.task.TaskRunSpec
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository
import io.agentscope.harness.agent.workspace.WorkspaceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * 在真正提交后台任务前做一次"当前活跃委派数"预算检查，超出 `max-parallel-delegations` 时直接返回一个
 * 已失败的 [BackgroundTask]，不调用 `super.putTask(...)`（也就不会真正启动子 Agent 执行）。
 *
 * ## 为什么继承 [WorkspaceTaskRepository] 而不是包一层通用的 TaskRepository 装饰器
 * 框架 `HarnessAgent#shutdownTaskRepository()` 在关闭时用 `instanceof WorkspaceTaskRepository`
 * 判断要不要调用 `.shutdown()`（停掉 heartbeat/孤儿任务清扫的后台线程）。如果换成一个只实现
 * `TaskRepository` 接口、内部持有真实 repo 引用的装饰器，这个 `instanceof` 检查会失败，
 * `.shutdown()` 永远不会被调用，导致后台维护线程泄漏。继承是唯一能同时"加预算检查"又"不破坏框架
 * 生命周期钩子"的做法——`putTask` 之外的所有方法（`listTasks`/`cancelTask`/`findPendingDeliveries`/
 * `markDelivered`/`isDelivered`/`shutdown`/heartbeat/孤儿清扫）全部原样继承，行为不变。
 *
 * ## 覆盖范围
 * 只保护会落进 [io.agentscope.harness.agent.subagent.task.TaskRepository] 的委派：
 * - `agent_spawn(timeout_seconds=0)` / `agent_send(timeout_seconds=0)` 主动异步委派；
 * - 同步委派超时后被框架 promote 出来的后台任务（`TaskRunSpec.AdoptedTaskRunSpec`）；
 * - 远程子 Agent 的同步调用（`AgentSpawnTool.runRemoteSync` 内部也会先 `putTask` 再阻塞等待）。
 *
 * 不覆盖"本地同步委派在超时前已经跑完"的情况——那条路径根本不经过 [TaskRepository]，是本类无法
 * 触达的部分，由预算的另一半（[com.tencent.bkrepo.agent.subagent.DelegationBudgetMiddleware]，
 * 对同步路径只做基于实时计数的动态软提醒，不做硬拦截）兜底。
 *
 * ## 已知的非原子性
 * "读取当前活跃数 -> 判断 -> 写入新任务"不是一次原子操作（`listTasks` 与 `putTask` 之间可能有并发
 * 提交插进来），极端情况下会短暂超出 `maxParallelDelegations` 一两个名额。这是可接受的权衡——目标是
 * 防止后台任务无限堆积失控，不是做精确的信号量限流，用这个代价换取实现和维护成本的大幅降低。
 */
class LimitedWorkspaceTaskRepository(
    workspaceManager: WorkspaceManager,
    parentAgentId: String,
    private val maxParallelDelegations: Int,
    private val guard: DelegationConcurrencyGuard,
) : WorkspaceTaskRepository(workspaceManager, parentAgentId) {

    override fun putTask(
        rc: RuntimeContext,
        taskId: String,
        subAgentId: String,
        sessionId: String,
        spec: TaskRunSpec,
    ): BackgroundTask {
        val activeBackground = listTasks(rc, sessionId, null).count { !it.taskStatus.isTerminal }
        val activeSync = guard.inFlight(sessionId)
        val active = activeBackground + activeSync
        if (active >= maxParallelDelegations) {
            logger.info(
                "delegation budget exceeded for session[$sessionId]: active=$active, " +
                    "max=$maxParallelDelegations, rejected taskId=$taskId, agentId=$subAgentId",
            )
            val failed = CompletableFuture<String>()
            failed.completeExceptionally(
                IllegalStateException(
                    "Max parallel delegations ($maxParallelDelegations) reached for this session. " +
                        "Wait for an existing agent_spawn/agent_send task to finish (see task_list()) " +
                        "before starting a new one.",
                ),
            )
            return BackgroundTask(taskId, subAgentId, failed)
        }
        return super.putTask(rc, taskId, subAgentId, sessionId, spec)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LimitedWorkspaceTaskRepository::class.java)
    }
}
