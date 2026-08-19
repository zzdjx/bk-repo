/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.processor.AguiRequestProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.reflect.Method

/**
 * 同步 AgentScope [AguiResumeCoordinator] 的 in-memory resume 合约。
 *
 * 子 Agent HITL 上冒时，合成 [AguiEvent.RunFinished] 只经 SSE 下发，不会进入 adapter flux 的
 * `doOnNext(trackPendingInterrupts)`；若提前 dispose 订阅，`doFinally(finishRun)` 也可能因
 * Reactor 在 `onNext` 内 dispose 而不及时执行，导致 resume 时 `beginRun` 报 active run 冲突。
 */
@Component
class AguiResumeContractBridge(
    aguiRequestProcessor: AguiRequestProcessor,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val coordinator: Any
    private val finishRunMethod: Method
    private val trackPendingInterruptsMethod: Method

    init {
        val coordinatorField = AguiRequestProcessor::class.java.getDeclaredField("resumeCoordinator")
        coordinatorField.isAccessible = true
        coordinator = requireNotNull(coordinatorField.get(aguiRequestProcessor)) {
            "AguiRequestProcessor.resumeCoordinator is null"
        }
        val coordinatorClass = coordinator.javaClass
        finishRunMethod = coordinatorClass.getDeclaredMethod("finishRun", String::class.java, String::class.java)
            .apply { isAccessible = true }
        trackPendingInterruptsMethod = coordinatorClass.getDeclaredMethod(
            "trackPendingInterrupts",
            String::class.java,
            String::class.java,
            AguiEvent::class.java,
            java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
    }

    fun syncInterruptOutcome(threadId: String, runId: String, event: AguiEvent) {
        if (event !is AguiEvent.RunFinished) return
        invokeQuietly(threadId, "trackPendingInterrupts") {
            trackPendingInterruptsMethod.invoke(coordinator, threadId, runId, event, false)
        }
    }

    fun finishActiveRun(threadId: String, runId: String) {
        invokeQuietly(threadId, "finishRun") {
            finishRunMethod.invoke(coordinator, threadId, runId)
        }
    }

    private inline fun invokeQuietly(threadId: String, operation: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            logger.warn("AG-UI resume contract {} failed for thread[{}]: {}", operation, threadId, ex.message)
        }
    }
}
