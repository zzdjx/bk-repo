/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.hitl.AguiInterruptTracker
import com.tencent.bkrepo.agent.agui.AguiMessageArchiveHandler
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agent.RuntimeContext
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 单次 AG-UI run 的内存上下文，供编排、事件管道与生命周期组件共享。 */
class AgentRunScope(
    val userId: String,
    val projectId: String,
    val input: RunAgentInput,
    val threadId: String,
    val runId: String,
    val executionId: String,
    val runtimeContext: RuntimeContext,
    val eventFlux: Flux<AguiEvent>,
    val archiveState: AguiMessageArchiveHandler.State,
    val interruptState: AguiInterruptTracker.State,
    val emitter: SseEmitter,
    val terminalStatus: AtomicReference<AgentRunStatus> = AtomicReference(AgentRunStatus.COMPLETED),
    val runFinished: AtomicBoolean = AtomicBoolean(false),
    val primaryClientDisconnected: AtomicBoolean = AtomicBoolean(false),
) {
    private val eventIndexSeq = AtomicLong(0)

    private val lastStopPollAt = AtomicLong(0)

    val subscriptionRef = AtomicReference<Disposable>()

    fun nextEventIndex(): Long = eventIndexSeq.getAndIncrement()

    /**
     * 停止标志轮询的节流闸门，[intervalMillis] 内最多放行一次。
     *
     * 同副本点停止走的是 [com.tencent.bkrepo.agent.runtime.ActiveRunManager] 持有的本地 handle，同步中止、
     * 根本不经过轮询；轮询只为"停止请求打到了别的副本"这一种情况兜底。但它挂在每个 AG-UI 事件上，流式输出时
     * 等于每个 token 查一次共享存储，频率和它承担的职责完全不匹配。
     *
     * 节流之后跨副本停止最晚延迟 [intervalMillis] 生效，同副本仍然是零延迟。
     *
     * 初值 0 保证首个事件必然放行：run 刚起来就被停的场景不能被节流窗口吃掉。
     */
    fun shouldPollStopFlag(intervalMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = lastStopPollAt.get()
        if (now - last < intervalMillis) {
            return false
        }
        // CAS 失败说明并发的另一个事件已经放行了这一轮，让它去查就行。
        return lastStopPollAt.compareAndSet(last, now)
    }
}
