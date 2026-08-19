/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.session.HarnessAgentResolver
import io.agentscope.core.agui.adapter.AguiAdapterConfig
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.processor.AguiRequestProcessor
import io.agentscope.core.agui.registry.AguiAgentRegistry
import io.agentscope.harness.agent.HarnessAgent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentMap

class AguiResumeContractBridgeTest {

    @Test
    fun `finishActiveRun clears in-memory active run`() {
        val processor = testProcessor()
        val bridge = AguiResumeContractBridge(processor)
        val coordinator = coordinator(processor)
        val beginRun = coordinator.javaClass.getDeclaredMethod("beginRun", RunAgentInput::class.java)
            .apply { isAccessible = true }

        val firstInput = runInput("thread-1", "run-1")
        assertFalse(contractError(beginRun.invoke(coordinator, firstInput)))
        assertEquals("run-1", activeRunId(coordinator, "thread-1"))

        bridge.finishActiveRun("thread-1", "run-1")
        assertNull(activeRunId(coordinator, "thread-1"))

        val secondInput = runInput("thread-1", "run-2")
        assertFalse(contractError(beginRun.invoke(coordinator, secondInput)))
        assertEquals("run-2", activeRunId(coordinator, "thread-1"))
    }

    @Test
    fun `syncInterruptOutcome records pending interrupts for resume contract`() {
        val processor = testProcessor()
        val bridge = AguiResumeContractBridge(processor)
        val coordinator = coordinator(processor)
        val beginRun = coordinator.javaClass.getDeclaredMethod("beginRun", RunAgentInput::class.java)
            .apply { isAccessible = true }
        val validate = coordinator.javaClass.getDeclaredMethod("validate", RunAgentInput::class.java)
            .apply { isAccessible = true }

        val firstInput = runInput("thread-1", "run-1")
        assertFalse(contractError(beginRun.invoke(coordinator, firstInput)))

        val interrupt = AguiEvent.Interrupt(
            "permission_confirm-call-1",
            "permission_confirm",
            "confirm",
            "call-1",
            null,
            null,
            mapOf("toolName" to "set_download_path"),
        )
        bridge.syncInterruptOutcome(
            "thread-1",
            "run-1",
            AguiEvent.RunFinished(
                "thread-1",
                "run-1",
                null,
                AguiEvent.RunFinishedInterruptOutcome(listOf(interrupt)),
            ),
        )
        bridge.finishActiveRun("thread-1", "run-1")

        val blocked = runInput("thread-1", "run-2")
        assertTrue(contractError(validate.invoke(coordinator, blocked)))

        val resumeInput = RunAgentInput.builder()
            .threadId("thread-1")
            .runId("run-2")
            .messages(emptyList())
            .resume(
                listOf(
                    AguiResume(
                        "permission_confirm-call-1",
                        AguiResume.STATUS_RESOLVED,
                        mapOf("approved" to true),
                    ),
                ),
            )
            .build()
        assertFalse(contractError(beginRun.invoke(coordinator, resumeInput)))
    }

    private fun runInput(threadId: String, runId: String): RunAgentInput =
        RunAgentInput.builder()
            .threadId(threadId)
            .runId(runId)
            .messages(emptyList())
            .build()

    private fun contractError(result: Any?): Boolean {
        requireNotNull(result)
        val method = result.javaClass.getDeclaredMethod("isError").apply { isAccessible = true }
        return method.invoke(result) as Boolean
    }

    private fun testProcessor(): AguiRequestProcessor {
        val registry = AguiAgentRegistry()
        registry.register("test", HarnessAgent.builder().name("test").build())
        val runtimeProperties = EffectiveAgentRuntimeProperties.defaults()
        val config = AguiAdapterConfig.builder()
            .defaultAgentId("test")
            .emitSubagentEventsAsNative(true)
            .build()
        return AguiRequestProcessor.builder()
            .agentResolver(HarnessAgentResolver(registry, runtimeProperties))
            .config(config)
            .build()
    }

    private fun coordinator(processor: AguiRequestProcessor): Any {
        val field = AguiRequestProcessor::class.java.getDeclaredField("resumeCoordinator")
        field.isAccessible = true
        return field.get(processor)!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun activeRunId(coordinator: Any, threadId: String): String? {
        val field = coordinator.javaClass.getDeclaredField("activeRunsByThread")
        field.isAccessible = true
        val map = field.get(coordinator) as ConcurrentMap<String, String>
        return map[threadId]
    }
}
