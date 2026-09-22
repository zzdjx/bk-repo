/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.processor.AguiRequestProcessor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentMap

/**
 * 把 Redis 里的 pending interrupt 灌回 AgentScope 进程内 [io.agentscope.core.agui.processor.AguiResumeCoordinator]。
 *
 * AgentScope 2.0.1 的协调器是 `AguiRequestProcessor` 里一份 ConcurrentHashMap，只认本 JVM
 * 上一轮 `RunFinished(interrupt)` 写进去的记录。我们自己的 pending 快照已经进 Redis
 *（跨副本 / 滚动发布 / 进程重启都还在），但框架这份内存表不会跟着恢复。
 *
 * 于是会出现：我们的 [AguiResumeValidator] 放行（Redis 对得上），紧接着框架报
 * `RunAgentInput.resume does not match any open interrupt`——这正是客户端「查询任务列表」后
 * 立刻看到的那句错误。本地工具 `list_download_tasks` 第一轮挂起写 Redis，第二轮 resume
 * 打到另一副本或空表 JVM 时必现。
 *
 * 框架没有公开 restore API，这里只在内存表为空时按快照补进去，已有本轮记录则不动。
 */
@Component
class AguiResumeCoordinatorHydrator(
    private val interruptStateRepository: AgentInterruptStateRepository,
) {

    fun hydrateIfNeeded(processor: AguiRequestProcessor, input: RunAgentInput) {
        if (!input.hasResume()) {
            return
        }
        val snapshots = interruptStateRepository.getPendingInterrupt(input.threadId)
            ?.interrupts
            .orEmpty()
        if (snapshots.isEmpty()) {
            return
        }
        val resumeIds = input.resume.map { it.interruptId }.toSet()
        hydrate(processor, input.threadId, snapshots.filter { it.id in resumeIds })
    }

    fun hydrate(
        processor: AguiRequestProcessor,
        threadId: String,
        snapshots: List<PendingInterruptSnapshot>,
    ) {
        if (snapshots.isEmpty()) {
            return
        }
        val pendingByThread = pendingInterruptsByThread(processor)
        val existing = pendingByThread[threadId]
        if (!existing.isNullOrEmpty()) {
            return
        }
        val restored = LinkedHashMap<String, AguiEvent.Interrupt>(snapshots.size)
        for (snapshot in snapshots) {
            restored[snapshot.id] = toInterrupt(snapshot)
        }
        pendingByThread[threadId] = restored
        logger.info(
            "Hydrated {} pending interrupt(s) into AguiResumeCoordinator for thread[{}]",
            restored.size,
            threadId,
        )
    }

    private fun pendingInterruptsByThread(
        processor: AguiRequestProcessor,
    ): ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> {
        val coordinatorField = AguiRequestProcessor::class.java.getDeclaredField(COORDINATOR_FIELD)
        coordinatorField.isAccessible = true
        val coordinator = coordinatorField.get(processor)
        val mapField = coordinator.javaClass.getDeclaredField(PENDING_MAP_FIELD)
        mapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return mapField.get(coordinator) as ConcurrentMap<String, Map<String, AguiEvent.Interrupt>>
    }

    private fun toInterrupt(snapshot: PendingInterruptSnapshot): AguiEvent.Interrupt {
        return AguiEvent.Interrupt(
            snapshot.id,
            snapshot.reason,
            snapshot.message,
            snapshot.toolCallId,
            snapshot.responseSchema,
            snapshot.expiresAt,
            snapshot.metadata,
        )
    }

    companion object {
        private const val COORDINATOR_FIELD = "resumeCoordinator"
        private const val PENDING_MAP_FIELD = "pendingInterruptsByThread"
        private val logger = LoggerFactory.getLogger(AguiResumeCoordinatorHydrator::class.java)
    }
}
