/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.hitl.AguiInterruptTracker
import com.tencent.bkrepo.agent.hitl.AgentInterruptStateRepository
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import io.agentscope.core.agui.event.AguiEvent
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/** 从 AG-UI 终态事件推导 run 状态，并维护 pending interrupt 快照。 */
@Component
class AgentRunOutcomeTracker(
    private val aguiInterruptTracker: AguiInterruptTracker,
    private val interruptStateRepository: AgentInterruptStateRepository,
) {

    fun applyTerminalEvent(event: AguiEvent, terminalStatus: AtomicReference<AgentRunStatus>) {
        when (event) {
            is AguiEvent.RunFinished -> {
                // 2.0.1 legacy：errorEvents() 可能在 RUN_ERROR 后再发 RUN_FINISHED，终态以 FAILED 为准。
                if (terminalStatus.get() == AgentRunStatus.FAILED) return
                // 已经进入 SUSPENDED 终态后，任何后续的非 interrupt RunFinished 都不应覆盖为 COMPLETED
                // （防御性保护，正常链路每次 run 只会有一个终态 RunFinished）。
                if (terminalStatus.get() == AgentRunStatus.SUSPENDED &&
                    event.outcome() !is AguiEvent.RunFinishedInterruptOutcome
                ) {
                    return
                }
                terminalStatus.set(
                    if (event.outcome() is AguiEvent.RunFinishedInterruptOutcome) {
                        AgentRunStatus.SUSPENDED
                    } else {
                        AgentRunStatus.COMPLETED
                    },
                )
            }
            is AguiEvent.RunError -> terminalStatus.set(AgentRunStatus.FAILED)
            else -> Unit
        }
    }

    fun capturePendingInterruptIfNeeded(
        threadId: String,
        runId: String,
        event: AguiEvent,
        terminalStatus: AgentRunStatus,
        interruptState: AguiInterruptTracker.State,
    ) {
        if (event !is AguiEvent.RunFinished) return
        when (terminalStatus) {
            AgentRunStatus.SUSPENDED -> {
                aguiInterruptTracker.captureSuspendedSession(runId, event, interruptState)?.let { session ->
                    interruptStateRepository.savePendingInterrupt(threadId, session)
                }
            }
            AgentRunStatus.COMPLETED -> interruptStateRepository.clearPendingInterrupt(threadId)
            else -> Unit
        }
    }
}
