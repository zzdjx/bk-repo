/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import com.tencent.bkrepo.agent.hitl.AguiInterruptNormalizer
import com.tencent.bkrepo.agent.hitl.AgentInterruptStateRepository
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.runtime.ActiveRunKey
import com.tencent.bkrepo.agent.runtime.AgentRunReplaySinkRegistry
import com.tencent.bkrepo.agent.service.AgentRunEventService
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.RunAgentInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.scheduler.Schedulers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * run 事件流衔接：终态全量回放、active run 增量回放 + 本地 sink / 跨实例 Mongo 轮询。
 *
 * 不重新执行 Agent，也不重复归档 message（手册 §6.4）。
 */
@Component
class AgentRunStreamOrchestrator(
    private val properties: EffectiveAgentRuntimeProperties,
    private val agentRunRecordService: AgentRunRecordService,
    private val activeRunManager: ActiveRunManager,
    private val runEventService: AgentRunEventService,
    private val replaySinkRegistry: AgentRunReplaySinkRegistry,
    private val interruptStateRepository: AgentInterruptStateRepository,
    private val interruptNormalizer: AguiInterruptNormalizer,
    private val sseEventWriter: AguiSseEventWriter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun stream(
        userId: String,
        projectId: String,
        threadId: String,
        runId: String?,
        lastEventIndex: Long?,
    ): SseEmitter {
        val runKey = ActiveRunKey(userId, projectId, threadId)
        val resolvedRunId = resolveRunId(runKey, threadId, runId)
        val existingRun = agentRunRecordService.findByRunId(resolvedRunId)
            ?: throw ParameterInvalidException("runId: run[$resolvedRunId] not found")
        if (existingRun.threadId != threadId) {
            throw ParameterInvalidException("threadId: run does not belong to thread[$threadId]")
        }
        val stillRunning = existingRun.status == AgentRunStatus.RUNNING || activeRunManager.isRunning(runKey)
        return if (stillRunning) {
            streamActiveRun(threadId, resolvedRunId, lastEventIndex, runKey)
        } else {
            replayTerminalRun(threadId, resolvedRunId, lastEventIndex ?: -1L, existingRun)
        }
    }

    /** 幂等 POST /run 时对已终态 run 重放事件，不重新执行 Agent。 */
    fun replayTerminalForRun(input: RunAgentInput, existingRun: TAgentRun): SseEmitter {
        return replayTerminalRun(input.threadId, input.runId, -1L, existingRun)
    }

    private fun resolveRunId(key: ActiveRunKey, threadId: String, runId: String?): String {
        if (!runId.isNullOrBlank()) {
            return runId
        }
        activeRunManager.getActiveRunId(key)?.let { return it }
        return agentRunRecordService.findLatestByThreadId(threadId)?.runId
            ?: throw ParameterInvalidException("runId: no run found for thread[$threadId]")
    }

    private fun replayTerminalRun(
        threadId: String,
        runId: String,
        afterIndex: Long,
        existingRun: TAgentRun,
    ): SseEmitter {
        logger.info("replay terminal run[{}] status[{}]", runId, existingRun.status)
        val emitter = SseEmitter(properties.sseTimeout.toMillis())
        Schedulers.boundedElastic().schedule {
            try {
                if (runEventService.hasEvents(runId)) {
                    runEventService.replayTo(emitter, runId, afterIndex)
                } else {
                    replaySyntheticTerminalEvents(emitter, threadId, runId, existingRun)
                }
                emitter.complete()
            } catch (ex: Exception) {
                emitter.completeWithError(ex)
            }
        }
        return emitter
    }

    private fun streamActiveRun(
        threadId: String,
        runId: String,
        lastEventIndex: Long?,
        runKey: ActiveRunKey,
    ): SseEmitter {
        logger.info("live stream run[{}] thread[{}]", runId, threadId)
        val completion = StreamCompletion(SseEmitter(properties.reconnectTimeout.toMillis()))
        val afterIndex = lastEventIndex ?: -1L

        Schedulers.boundedElastic().schedule {
            try {
                val cursor = runEventService.replayTo(completion.emitter, runId, afterIndex)
                if (attachLocalSink(runId, threadId, cursor, completion)) {
                    return@schedule
                }
                pollRemoteEvents(runId, threadId, runKey, cursor, completion)
            } catch (ex: Exception) {
                completion.completeWithError(ex)
            }
        }
        return completion.emitter
    }

    private fun attachLocalSink(
        runId: String,
        threadId: String,
        cursor: Long,
        completion: StreamCompletion,
    ): Boolean {
        val unsubscribe = replaySinkRegistry.subscribe(runId, cursor) { event ->
            if (completion.isClaimed) return@subscribe
            try {
                sseEventWriter.sendJson(completion.emitter, event.eventJson)
                if (event.terminal) {
                    completion.complete()
                }
            } catch (ex: Exception) {
                completion.completeWithError(ex)
            }
        } ?: return false

        completion.emitter.onCompletion { unsubscribe() }
        completion.emitter.onTimeout {
            unsubscribe()
            if (completion.claim()) {
                sendStreamTimeout(completion.emitter, threadId, runId)
            }
        }
        completion.emitter.onError { unsubscribe() }
        return true
    }

    private fun pollRemoteEvents(
        runId: String,
        threadId: String,
        runKey: ActiveRunKey,
        startCursor: Long,
        completion: StreamCompletion,
    ) {
        var cursor = startCursor
        val deadline = System.currentTimeMillis() + properties.reconnectTimeout.toMillis()
        while (!completion.isClaimed && System.currentTimeMillis() < deadline) {
            val events = runEventService.listAfterIndex(runId, cursor)
            for (event in events) {
                if (completion.isClaimed) return
                sseEventWriter.sendJson(completion.emitter, event.eventData)
                cursor = event.eventIndex
                if (event.terminal) {
                    completion.complete()
                    return
                }
            }
            val latestRun = agentRunRecordService.findByRunId(runId)
            if (latestRun?.status != AgentRunStatus.RUNNING && !activeRunManager.isRunning(runKey)) {
                completion.complete()
                return
            }
            Thread.sleep(properties.reconnectPollInterval.toMillis())
        }
        if (completion.claim()) {
            sendStreamTimeout(completion.emitter, threadId, runId)
        }
    }

    /**
     * 重连超时：补一条 `stream_timeout` 的 `RunError` 再收尾，让客户端知道是这条流到点了、而不是
     * run 失败了。调用前必须已经 [StreamCompletion.claim]，所以这里直接操作 emitter。
     */
    private fun sendStreamTimeout(emitter: SseEmitter, threadId: String, runId: String) {
        try {
            sseEventWriter.send(
                emitter,
                AguiEvent.RunError(
                    threadId,
                    runId,
                    "stream_timeout",
                    "stream_timeout",
                ),
            )
            emitter.complete()
        } catch (ex: Exception) {
            emitter.completeWithError(ex)
        }
    }

    /**
     * 事件库为空时的最小终态回放（迁移期兼容：Phase C 之前完成的 run 无持久化事件）。
     */
    private fun replaySyntheticTerminalEvents(
        emitter: SseEmitter,
        threadId: String,
        runId: String,
        existingRun: TAgentRun,
    ) {
        sseEventWriter.send(emitter, AguiEvent.RunStarted(threadId, runId, null, null))
        when (existingRun.status) {
            AgentRunStatus.FAILED -> sseEventWriter.send(
                emitter,
                AguiEvent.RunError(
                    threadId,
                    runId,
                    existingRun.errorCode ?: "run_failed",
                    existingRun.errorCode,
                ),
            )
            AgentRunStatus.SUSPENDED -> sseEventWriter.send(
                emitter,
                AguiEvent.RunFinished(
                    threadId,
                    runId,
                    null,
                    AguiEvent.RunFinishedInterruptOutcome(pendingInterrupts(threadId)),
                ),
            )
            else -> sseEventWriter.send(
                emitter,
                AguiEvent.RunFinished(
                    threadId,
                    runId,
                    null,
                    AguiEvent.RunFinishedSuccessOutcome(),
                ),
            )
        }
    }

    private fun pendingInterrupts(threadId: String): List<AguiEvent.Interrupt> {
        val snapshots = interruptStateRepository.getPendingInterrupt(threadId)?.interrupts ?: return emptyList()
        return snapshots
            .filter { it.toolName !in AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS }
            .map { snapshot ->
                val normalized = interruptNormalizer.normalizeSnapshot(snapshot, properties.activeRunTtl)
                AguiEvent.Interrupt(
                    normalized.id,
                    normalized.reason,
                    normalized.message,
                    normalized.toolCallId,
                    normalized.responseSchema,
                    normalized.expiresAt,
                    normalized.metadata,
                )
            }
    }
}

/**
 * 一条重连流的一次性收尾闸。
 *
 * 同一个 emitter 会被好几条路径同时推进：本地 sink 的事件回调、emitter 自己的 timeout/error 回调、
 * 跨实例轮询循环、以及外层捕获异常的 catch 块。谁先到谁负责收尾，之后所有人都必须闭嘴——往一个已经
 * complete 过的 [SseEmitter] 上再写会抛 `IllegalStateException`，把本来正常的收尾变成一条噪音错误日志。
 *
 * [claim] 单独暴露出来是给"抢到收尾权之后还要先发一条事件再 complete"的场景（见
 * [AgentRunStreamOrchestrator.sendStreamTimeout]）。
 */
private class StreamCompletion(val emitter: SseEmitter) {

    private val claimed = AtomicBoolean(false)

    val isClaimed: Boolean get() = claimed.get()

    fun claim(): Boolean = claimed.compareAndSet(false, true)

    fun complete() {
        if (claim()) emitter.complete()
    }

    fun completeWithError(ex: Throwable) {
        if (claim()) emitter.completeWithError(ex)
    }
}
