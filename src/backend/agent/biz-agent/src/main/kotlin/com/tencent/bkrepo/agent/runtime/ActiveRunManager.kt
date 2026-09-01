/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.runtime.store.ActiveRunStateStore
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.state.AgentStateStore
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行态唯一入口：分布式 lock/active/stop + 本机 handle + 早到 stop + AgentState 清理。
 */
class ActiveRunManager(
    private val stateStore: ActiveRunStateStore,
    private val agentStateStore: AgentStateStore,
    private val eventPublisher: ApplicationEventPublisher,
) {

    data class LocalHandle(
        val userId: String,
        val threadId: String,
        val runId: String,
        val runtimeContext: RuntimeContext,
        val abort: (AgentRunAbortReason) -> Unit,
    )

    private val localHandles = ConcurrentHashMap<String, LocalHandle>()
    private val pendingStopRunIds = ConcurrentHashMap<String, String>()

    fun tryAcquire(scope: ActiveRunScope): Boolean {
        return stateStore.tryAcquireLock(scope.userId, scope.threadId)
    }

    fun isRunning(scope: ActiveRunScope): Boolean {
        return stateStore.isLockHeld(scope.userId, scope.threadId)
    }

    fun bindActiveRun(scope: ActiveRunScope, runId: String) {
        stateStore.bindActiveRun(scope.userId, scope.threadId, runId)
        stateStore.clearStop(runId)
        pendingStopRunIds.remove(scopeKey(scope))
    }

    fun getActiveRunId(scope: ActiveRunScope): String? {
        return stateStore.getActiveRunId(scope.userId, scope.threadId)
    }

    fun releaseRun(scope: ActiveRunScope, runId: String) {
        if (stateStore.getActiveRunId(scope.userId, scope.threadId) != runId) {
            return
        }
        stateStore.clearActiveRun(scope.userId, scope.threadId)
        stateStore.clearStop(runId)
        stateStore.releaseLock(scope.userId, scope.threadId)
        pendingStopRunIds.remove(scopeKey(scope))
    }

    fun requestStop(scope: ActiveRunScope, runId: String) {
        stateStore.requestStop(runId)
        eventPublisher.publishEvent(AgentRunStopBroadcastEvent(scope, runId))
        abortLocalHandle(scope, runId)
        if (!hasLocalHandle(scope, runId)) {
            pendingStopRunIds[scopeKey(scope)] = runId
        }
    }

    fun isStopRequested(runId: String): Boolean {
        return stateStore.isStopRequested(runId)
    }

    fun registerHandle(
        scope: ActiveRunScope,
        runId: String,
        runtimeContext: RuntimeContext,
        abort: (AgentRunAbortReason) -> Unit,
    ) {
        localHandles[scopeKey(scope)] = LocalHandle(
            userId = scope.userId,
            threadId = scope.threadId,
            runId = runId,
            runtimeContext = runtimeContext,
            abort = abort,
        )
        if (stateStore.isStopRequested(runId) || pendingStopRunIds[scopeKey(scope)] == runId) {
            abort(AgentRunAbortReason.USER_STOP)
        }
    }

    /**
     * 中止本副本所有在跑的 run，返回实际中止的条数；供停机流程调用（见
     * [com.tencent.bkrepo.agent.runtime.AgentRunShutdownHandler]）。
     *
     * 只处理本进程的 [localHandles]，不去扫 Redis：其它副本的 run 由它们各自停机时处理，跨副本代劳
     * 会把还在正常服务的 run 也一起杀掉。每个 handle 的 abort 回调走的是与用户停止完全相同的收尾路径
     * （中断 Agent、落 CANCELLED 终态、关事件流、释放会话锁、complete SSE），所以这里不需要重复清理。
     */
    fun abortLocalRuns(reason: AgentRunAbortReason): Int {
        var aborted = 0
        localHandles.values.toList().forEach { handle ->
            try {
                handle.abort(reason)
                aborted++
            } catch (exception: Exception) {
                logger.warn(
                    "failed to abort run[${handle.runId}] for user[${handle.userId}] " +
                        "thread[${handle.threadId}] on $reason",
                    exception,
                )
            }
        }
        return aborted
    }

    fun removeHandle(scope: ActiveRunScope) {
        localHandles.remove(scopeKey(scope))
    }

    fun clearActiveRunBinding(scope: ActiveRunScope) {
        stateStore.clearActiveRun(scope.userId, scope.threadId)
        pendingStopRunIds.remove(scopeKey(scope))
    }

    fun hasLocalHandle(scope: ActiveRunScope, runId: String): Boolean {
        return localHandles[scopeKey(scope)]?.runId == runId
    }

    fun clearAgentRuntimeState(userId: String, threadId: String) {
        try {
            agentStateStore.delete(userId, threadId)
        } catch (exception: Exception) {
            logger.warn("failed to clear agent runtime state for user[$userId] thread[$threadId]", exception)
        }
    }

    @EventListener
    fun onStopBroadcast(event: AgentRunStopBroadcastEvent) {
        abortLocalHandle(event.scope, event.runId)
    }

    private fun abortLocalHandle(scope: ActiveRunScope, runId: String) {
        localHandles[scopeKey(scope)]
            ?.takeIf { it.runId == runId }
            ?.abort?.invoke(AgentRunAbortReason.USER_STOP)
    }

    private fun scopeKey(scope: ActiveRunScope): String = "${scope.userId}:${scope.threadId}"

    companion object {
        private val logger = LoggerFactory.getLogger(ActiveRunManager::class.java)
    }
}
