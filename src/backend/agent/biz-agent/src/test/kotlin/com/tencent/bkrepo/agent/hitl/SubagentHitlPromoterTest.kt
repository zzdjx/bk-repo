/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.message.ToolUseBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubagentHitlPromoterTest {

    private val promoter = SubagentHitlPromoter()
    private val interruptState = AguiInterruptTracker.State()

    @Test
    fun `raw RequireUserConfirmEvent promotes without prior tool call custom`() {
        val state = SubagentHitlPromoter.State()
        val toolUse = ToolUseBlock.builder()
            .id("call-raw-1")
            .name("set_download_path")
            .input(mapOf("path" to "D:\\Downloads"))
            .build()
        val raw = AguiEvent.Raw(
            "thread-1",
            "run-1",
            RequireUserConfirmEvent("reply-1", listOf(toolUse)),
            "thread-abc/client",
        )

        promoter.onEvent(raw, interruptState, state)
        val runFinished = promoter.buildRunFinishedIfNeeded(raw, "thread-1", "run-1", interruptState, state)
        assertNotNull(runFinished)
        val interrupt = (runFinished!!.outcome() as AguiEvent.RunFinishedInterruptOutcome).interrupts().single()
        assertEquals("call-raw-1", interrupt.toolCallId())
        assertEquals("set_download_path", interrupt.metadata()["toolName"])
        assertEquals(mapOf("path" to "D:\\Downloads"), interrupt.metadata()["toolInput"])
    }

    @Test
    fun `native ToolCallStart with subagent source enriches interrupt tracker toolName`() {
        val state = SubagentHitlPromoter.State()
        val toolCallStart = AguiEvent.ToolCallStart(
            "thread-1",
            "run-1",
            "call-native-1",
            "set_download_path",
            null,
            io.agentscope.core.event.ToolCallStartEvent("reply-1", "call-native-1", "set_download_path")
                .withSource("thread-abc/client"),
        )
        promoter.onEvent(toolCallStart, interruptState, state)

        assertEquals("set_download_path", interruptState.toolNameByCallId["call-native-1"])
    }

    @Test
    fun `promoted only once for the same raw RequireUserConfirmEvent`() {
        val state = SubagentHitlPromoter.State()
        val toolUse = ToolUseBlock.builder()
            .id("call-once-1")
            .name("set_download_path")
            .input(emptyMap())
            .build()
        val raw = AguiEvent.Raw(
            "thread-1",
            "run-1",
            RequireUserConfirmEvent("reply-1", listOf(toolUse)),
            "thread-abc/client",
        )

        promoter.onEvent(raw, interruptState, state)
        assertNotNull(promoter.buildRunFinishedIfNeeded(raw, "thread-1", "run-1", interruptState, state))
        assertEquals(null, promoter.buildRunFinishedIfNeeded(raw, "thread-1", "run-1", interruptState, state))
    }
}

class AguiPermissionResumeAdapterTest {

    private val repository = DefaultAgentInterruptStateRepository(
        pendingInterruptStore = com.tencent.bkrepo.agent.session.InMemoryAgentPendingInterruptStore(),
        resumeIdempotencyStore = com.tencent.bkrepo.agent.session.InMemoryAgentResumeIdempotencyStore(),
    )
    private val adapter = AguiPermissionResumeAdapter(repository)

    @Test
    fun `adapt extracts approval resume into ConfirmResult`() {
        repository.savePendingInterrupt(
            "thread-1",
            PendingInterruptSession(
                originRunId = "run-old",
                interrupts = listOf(
                    PendingInterruptSnapshot(
                        id = "permission_confirm-call-1",
                        reason = "permission_confirm",
                        toolCallId = "call-1",
                        toolName = "set_download_path",
                        requiresApproval = true,
                        metadata = mapOf("toolInput" to mapOf("path" to "D:\\Downloads")),
                    ),
                ),
            ),
        )

        val input = RunAgentInput.builder()
            .threadId("thread-1")
            .runId("run-new")
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

        val adapted = adapter.adapt(input)
        assertTrue(adapted.confirmResults.single().isConfirmed)
        assertEquals("set_download_path", adapted.confirmResults.single().toolCall.name)
        assertEquals(false, adapted.input.hasResume())
    }
}
