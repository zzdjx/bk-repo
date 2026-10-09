/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.runtime.ActiveRunKey
import com.tencent.bkrepo.agent.runtime.AgentRunAbortReason
import com.tencent.bkrepo.agent.runtime.AgentSessionInterruptor
import com.tencent.bkrepo.agent.service.AgentRunEventService
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.scheduler.Schedulers

/**
 * run 结束、资源清理、跨副本 cancel 与 SSE 收尾。
 *
 * 一次 run 只有 [FinishReason] 列出的三种结束方式，全部经由 [finish] 收口；`runFinished` 的 CAS
 * 保证无论几条路径同时触发（事件流自然结束的同一刻客户端点了停止、abort handle 与本地 stop 标志
 * 撞上），落库、清理与 SSE 收尾都只做一次。
 */
@Component
class AgentRunLifecycleManager(
    private val activeRunManager: ActiveRunManager,
    private val agentSessionInterruptor: AgentSessionInterruptor,
    private val agentRunRecordService: AgentRunRecordService,
    private val runEventService: AgentRunEventService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * run 结束的原因。每种原因完整决定了终态状态、是否需要打断 Agent、以及落库时记什么审计字段，
     * 调用方不需要（也无法）自行拼装这几项的组合。
     */
    sealed interface FinishReason {

        /**
         * 事件流自然结束。终态取 [AgentRunScope.terminalStatus]——由
         * [AgentRunOutcomeTracker.applyTerminalEvent] 从 `RunFinished`/`RunError` 事件推导出来，
         * 可能是 COMPLETED / SUSPENDED / FAILED。一个终态事件都没发过就正常结束的流按 COMPLETED 记
         * （`terminalStatus` 的初值），因为"流正常走完"本身就说明没有出错。
         *
         * 这是唯一不需要打断 Agent 的结束方式：Agent 已经自己停了。
         */
        data object StreamCompleted : FinishReason

        /** 用户点了停止，或别的副本通过 [ActiveRunManager] 的 abort handle 要求停止。 */
        data class Cancelled(val reason: AgentRunAbortReason) : FinishReason

        /** 事件流本身抛错（区别于 Agent 发出 `RunError` 事件——那条走 [StreamCompleted]）。 */
        data class Failed(val error: Throwable) : FinishReason
    }

    fun registerHandle(scope: AgentRunScope) {
        activeRunManager.registerHandle(
            key = scope.activeRunKey(),
            runId = scope.runId,
            runtimeContext = scope.runtimeContext,
            abort = { reason -> finish(scope, FinishReason.Cancelled(reason)) },
        )
    }

    fun attachEmitterCallbacks(scope: AgentRunScope) {
        scope.emitter.onCompletion { markPrimaryClientDisconnected(scope) }
        scope.emitter.onError { markPrimaryClientDisconnected(scope) }
        scope.emitter.onTimeout { markPrimaryClientDisconnected(scope) }
    }

    fun markPrimaryClientDisconnected(scope: AgentRunScope) {
        if (scope.primaryClientDisconnected.compareAndSet(false, true)) {
            logger.info("primary sse disconnected, run continues: threadId={}", scope.threadId)
        }
    }

    fun finish(scope: AgentRunScope, reason: FinishReason = FinishReason.StreamCompleted) {
        if (!scope.runFinished.compareAndSet(false, true)) return
        // StreamCompleted 之外的两种原因都意味着 Agent 还在跑，必须先打断，否则它会继续消耗 token 并
        // 往一条已经收尾的流里发事件。
        val abortAgent = reason !is FinishReason.StreamCompleted
        if (abortAgent) {
            agentSessionInterruptor.interrupt(scope.runtimeContext)
        }
        disposeSubscription(scope, abortAgent)
        agentRunRecordService.finishRun(
            runId = scope.runId,
            status = when (reason) {
                FinishReason.StreamCompleted -> scope.terminalStatus.get()
                is FinishReason.Cancelled -> AgentRunStatus.CANCELLED
                is FinishReason.Failed -> AgentRunStatus.FAILED
            },
            cancelReason = (reason as? FinishReason.Cancelled)?.reason?.value,
            errorCode = (reason as? FinishReason.Failed)?.error?.javaClass?.simpleName,
        )
        cleanup(scope)
        completeEmitter(scope.emitter)
    }

    private fun disposeSubscription(scope: AgentRunScope, abortAgent: Boolean) {
        val subscription = scope.subscriptionRef.get() ?: return
        if (abortAgent) {
            // 避免在 Reactor onNext 回调内同步 dispose，导致 doFinally 延迟或不触发。
            Schedulers.boundedElastic().schedule { subscription.dispose() }
        } else {
            subscription.dispose()
        }
    }

    private fun cleanup(scope: AgentRunScope) {
        runEventService.closeRun(scope.runId)
        activeRunManager.removeHandle(scope.activeRunKey())
        activeRunManager.releaseRun(scope.activeRunKey(), scope.runId)
    }

    private fun completeEmitter(emitter: SseEmitter) {
        try {
            emitter.complete()
        } catch (ex: Exception) {
            logger.debug("SSE complete ignored: {}", ex.message)
        }
    }
}

fun AgentRunScope.activeRunKey(): ActiveRunKey = ActiveRunKey(userId, projectId, threadId)
