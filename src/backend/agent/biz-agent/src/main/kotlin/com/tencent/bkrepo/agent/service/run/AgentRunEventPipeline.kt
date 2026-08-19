/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.hitl.AguiInterruptTracker
import com.tencent.bkrepo.agent.hitl.AguiResumeContractBridge
import com.tencent.bkrepo.agent.hitl.SubagentHitlPromoter
import com.tencent.bkrepo.agent.agui.AguiMessageArchiveHandler
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.service.AgentRunEventService
import io.agentscope.core.agui.event.AguiEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.Disposable
import reactor.core.scheduler.Schedulers
import java.io.IOException

/** 订阅 AgentScope AG-UI 事件流：编码 SSE、归档、终态跟踪与异常兜底。 */
@Component
class AgentRunEventPipeline(
    private val activeRunManager: ActiveRunManager,
    private val runEventService: AgentRunEventService,
    private val aguiInterruptTracker: AguiInterruptTracker,
    private val subagentHitlPromoter: SubagentHitlPromoter,
    private val messageArchiveHandler: AguiMessageArchiveHandler,
    private val outcomeTracker: AgentRunOutcomeTracker,
    private val lifecycleManager: AgentRunLifecycleManager,
    private val resumeContractBridge: AguiResumeContractBridge,
    private val sseEventWriter: AguiSseEventWriter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun subscribe(scope: AgentRunScope): Disposable {
        val subscription = scope.eventFlux
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { event -> handleEvent(scope, event) },
                { error -> handleError(scope, error) },
                { lifecycleManager.finish(scope) },
            )
        scope.subscriptionRef.set(subscription)
        return subscription
    }

    private fun handleEvent(scope: AgentRunScope, event: AguiEvent) {
        if (scope.runFinished.get()) return
        if (handleCancelIfRequested(scope)) return
        aguiInterruptTracker.onEvent(event, scope.interruptState)
        subagentHitlPromoter.onEvent(event, scope.interruptState, scope.subagentHitlState)
        dispatchEvent(scope, event)
        promoteSubagentHitlIfNeeded(scope, event)
    }

    private fun dispatchEvent(scope: AgentRunScope, event: AguiEvent) {
        val outbound = aguiInterruptTracker.enrichEvent(event, scope.interruptState)
        if (outbound is AguiEvent.RunFinished) {
            resumeContractBridge.syncInterruptOutcome(scope.threadId, scope.runId, outbound)
        }
        outcomeTracker.applyTerminalEvent(outbound, scope.terminalStatus)
        outcomeTracker.capturePendingInterruptIfNeeded(
            threadId = scope.threadId,
            runId = scope.runId,
            event = outbound,
            terminalStatus = scope.terminalStatus.get(),
            interruptState = scope.interruptState,
        )
        messageArchiveHandler.onEvent(outbound, scope.threadId, scope.runId, scope.archiveState)
        runEventService.append(scope, outbound)
        sendToPrimaryClient(scope, outbound)
    }

    private fun promoteSubagentHitlIfNeeded(scope: AgentRunScope, event: AguiEvent) {
        val promoted = subagentHitlPromoter.buildRunFinishedIfNeeded(
            event = event,
            threadId = scope.threadId,
            runId = scope.runId,
            interruptState = scope.interruptState,
            state = scope.subagentHitlState,
        ) ?: return
        dispatchEvent(scope, promoted)
        lifecycleManager.finish(
            scope,
            AgentRunLifecycleManager.FinishOptions(
                abortAgent = true,
                runStatus = AgentRunStatus.SUSPENDED,
            ),
        )
    }

    private fun sendToPrimaryClient(scope: AgentRunScope, event: AguiEvent) {
        if (scope.primaryClientDisconnected.get()) return
        try {
            sseEventWriter.send(scope.emitter, event)
        } catch (ignored: IOException) {
            lifecycleManager.markPrimaryClientDisconnected(scope)
        }
    }

    private fun handleCancelIfRequested(scope: AgentRunScope): Boolean {
        if (!activeRunManager.isStopRequested(scope.runId)) return false
        messageArchiveHandler.forceFinalizeAssistant(scope.threadId, scope.runId, scope.archiveState)
        lifecycleManager.finish(
            scope,
            AgentRunLifecycleManager.FinishOptions(
                abortAgent = true,
                runStatus = AgentRunStatus.CANCELLED,
                cancelReason = "user_stop",
            ),
        )
        return true
    }

    private fun handleError(scope: AgentRunScope, error: Throwable) {
        logger.error("agent ag-ui run failed, user[{}] session[{}]", scope.userId, scope.threadId, error)
        lifecycleManager.finishWithError(scope, error)
    }
}
