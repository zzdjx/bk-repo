/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.session.InMemoryAgentPendingInterruptStore
import com.tencent.bkrepo.agent.session.InMemoryAgentResumeIdempotencyStore
import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.adapter.AguiAdapterConfig
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.processor.AgentResolver
import io.agentscope.core.agui.processor.AguiRequestProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

@DisplayName("持久化的 pending interrupt 回灌 AguiResumeCoordinator")
class AguiResumeCoordinatorHydratorTest {

    private val pendingStore = InMemoryAgentPendingInterruptStore()
    private val hydrator = AguiResumeCoordinatorHydrator(
        DefaultAgentInterruptStateRepository(
            pendingInterruptStore = pendingStore,
            resumeIdempotencyStore = InMemoryAgentResumeIdempotencyStore(),
        ),
    )

    @Test
    fun `未回灌时框架会把 resume 判成没有 open interrupt`() {
        val processor = newProcessor()
        val result = validateResume(processor, THREAD_ID, INTERRUPT_ID)

        assertTrue(isError(result))
        assertEquals(CONTRACT_ERROR, errorMessage(result))
    }

    @Test
    fun `从持久化快照回灌后同一 resume 应通过框架契约校验`() {
        val processor = newProcessor()
        pendingStore.save(
            THREAD_ID,
            PendingInterruptSession(
                originRunId = "run-1",
                interrupts = listOf(
                    PendingInterruptSnapshot(
                        id = INTERRUPT_ID,
                        reason = "tool_call",
                        toolCallId = "tc-list-1",
                        toolName = "list_download_tasks",
                        message = "等待客户端执行 list_download_tasks",
                    ),
                ),
            ),
        )

        hydrator.hydrateIfNeeded(processor, resumeInput(THREAD_ID, INTERRUPT_ID))
        val result = validateResume(processor, THREAD_ID, INTERRUPT_ID)

        assertFalse(isError(result)) { "回灌后不应再报 $CONTRACT_ERROR，实际=${errorMessage(result)}" }
    }

    @Test
    fun `hydrateIfNeeded 只回灌本次 resume 引用的 interrupt`() {
        val processor = newProcessor()
        pendingStore.save(
            THREAD_ID,
            PendingInterruptSession(
                originRunId = "run-mix",
                interrupts = listOf(
                    PendingInterruptSnapshot(
                        id = INTERRUPT_ID,
                        reason = "tool_call",
                        toolCallId = "tc-list-1",
                        toolName = "list_download_tasks",
                    ),
                    PendingInterruptSnapshot(
                        id = "int-other",
                        reason = "permission_confirm",
                        toolCallId = "tc-write-1",
                        toolName = "generic_write",
                    ),
                ),
            ),
        )

        hydrator.hydrateIfNeeded(processor, resumeInput(THREAD_ID, INTERRUPT_ID))

        assertFalse(isError(validateResume(processor, THREAD_ID, INTERRUPT_ID)))
        assertEquals(setOf(INTERRUPT_ID), pendingInterruptIds(processor, THREAD_ID))
    }

    @Test
    fun `本 JVM 已有 pending 时不覆盖`() {
        val processor = newProcessor()
        val liveId = "int-live"
        hydrateDirect(processor, THREAD_ID, liveId, "tc-live")

        pendingStore.save(
            THREAD_ID,
            PendingInterruptSession(
                originRunId = "run-stale",
                interrupts = listOf(
                    PendingInterruptSnapshot(
                        id = "int-stale",
                        reason = "tool_call",
                        toolCallId = "tc-stale",
                        toolName = "list_download_tasks",
                    ),
                ),
            ),
        )
        hydrator.hydrateIfNeeded(processor, resumeInput(THREAD_ID, "int-stale"))

        val liveResult = validateResume(processor, THREAD_ID, liveId)
        assertFalse(isError(liveResult)) { "已有内存 pending 不应被持久化快照覆盖，实际=${errorMessage(liveResult)}" }
    }

    private fun newProcessor(): AguiRequestProcessor {
        val resolver = object : AgentResolver {
            override fun resolveAgent(agentId: String, threadId: String?): Agent =
                mock<Agent>()
            override fun hasMemory(runtimeContext: RuntimeContext): Boolean = false
        }
        return AguiRequestProcessor.builder()
            .agentResolver(resolver)
            .config(AguiAdapterConfig.defaultConfig())
            .build()
    }

    private fun resumeInput(threadId: String, interruptId: String): RunAgentInput =
        RunAgentInput.builder()
            .threadId(threadId)
            .runId("run-resume")
            .resume(listOf(AguiResume(interruptId, AguiResume.STATUS_RESOLVED, mapOf("ok" to true))))
            .build()

    private fun validateResume(processor: AguiRequestProcessor, threadId: String, interruptId: String): Any {
        val coordinator = coordinator(processor)
        val validate = coordinator.javaClass.getDeclaredMethod("validate", RunAgentInput::class.java)
        validate.isAccessible = true
        return validate.invoke(coordinator, resumeInput(threadId, interruptId))
    }

    private fun hydrateDirect(
        processor: AguiRequestProcessor,
        threadId: String,
        interruptId: String,
        toolCallId: String,
    ) {
        hydrator.hydrate(
            processor,
            threadId,
            listOf(
                PendingInterruptSnapshot(
                    id = interruptId,
                    reason = "tool_call",
                    toolCallId = toolCallId,
                    toolName = "list_download_tasks",
                    message = "live",
                ),
            ),
        )
    }

    private fun coordinator(processor: AguiRequestProcessor): Any {
        val field = AguiRequestProcessor::class.java.getDeclaredField("resumeCoordinator")
        field.isAccessible = true
        return field.get(processor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingInterruptIds(processor: AguiRequestProcessor, threadId: String): Set<String> {
        val coordinator = coordinator(processor)
        val mapField = coordinator.javaClass.getDeclaredField("pendingInterruptsByThread")
        mapField.isAccessible = true
        val pending = mapField.get(coordinator) as Map<String, Map<String, *>>
        return pending[threadId]?.keys.orEmpty()
    }

    private fun isError(result: Any): Boolean {
        val method = result.javaClass.getDeclaredMethod("isError")
        method.isAccessible = true
        return method.invoke(result) as Boolean
    }

    private fun errorMessage(result: Any): String? {
        val method = result.javaClass.getDeclaredMethod("message")
        method.isAccessible = true
        return method.invoke(result) as String?
    }

    companion object {
        private const val THREAD_ID = "thread-list-tasks"
        private const val INTERRUPT_ID = "int-list-1"
        private const val CONTRACT_ERROR = "RunAgentInput.resume does not match any open interrupt"
    }
}
