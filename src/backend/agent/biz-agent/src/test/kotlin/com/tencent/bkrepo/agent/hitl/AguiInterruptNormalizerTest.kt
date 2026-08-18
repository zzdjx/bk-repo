/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import io.agentscope.core.agui.event.AguiEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class AguiInterruptNormalizerTest {

    private val catalog = FrontendToolCatalog()
    private val normalizer = AguiInterruptNormalizer(catalog)
    private val ttl = Duration.ofMinutes(5)

    @Test
    fun `fills missing responseSchema and expiresAt for tool_call interrupt`() {
        val interrupt = AguiEvent.Interrupt(
            "int-1",
            "tool_call",
            "list tasks",
            "tc-1",
            null,
            null,
            null,
        )

        val normalized = normalizer.normalizeInterrupt(interrupt, ttl, "list_download_tasks")

        assertEquals("object", (normalized.responseSchema() as Map<*, *>)["type"])
        assertNotNull(normalized.expiresAt())
        assertFalse(normalized.expiresAt()!!.isBlank())
    }

    @Test
    fun `uses approval schema for write tools`() {
        val interrupt = AguiEvent.Interrupt(
            "int-2",
            "tool_call",
            "confirm",
            "tc-2",
            null,
            null,
            mapOf("toolName" to "set_download_path"),
        )

        val normalized = normalizer.normalizeInterrupt(interrupt, ttl, "set_download_path")
        val properties = (normalized.responseSchema() as Map<*, *>)["properties"] as Map<*, *>

        assertTrue(properties.containsKey("approved"))
    }

    @Test
    fun `uses approval schema for permission confirm metadata`() {
        val interrupt = AguiEvent.Interrupt(
            "int-3",
            "tool_call",
            "confirm",
            "tc-3",
            null,
            null,
            mapOf("agentscope.interruptKind" to "permission_confirm"),
        )

        val normalized = normalizer.normalizeInterrupt(interrupt, ttl)
        val properties = (normalized.responseSchema() as Map<*, *>)["properties"] as Map<*, *>

        assertTrue(properties.containsKey("approved"))
    }

    @Test
    fun `normalizes pending snapshot for client validation`() {
        val snapshot = PendingInterruptSnapshot(
            id = "int-4",
            reason = "tool_call",
            toolCallId = "tc-4",
            toolName = "list_download_tasks",
        )

        val normalized = normalizer.normalizeSnapshot(snapshot, ttl)

        assertNotNull(normalized.responseSchema)
        assertNotNull(normalized.expiresAt)
        assertFalse(normalized.expiresAt!!.isBlank())
    }

    @Test
    fun `normalizes RunFinished interrupt outcome`() {
        val event = AguiEvent.RunFinished(
            "thread-1",
            "run-1",
            null,
            AguiEvent.RunFinishedInterruptOutcome(
                listOf(
                    AguiEvent.Interrupt("int-5", "tool_call", null, "tc-5", null, null, null),
                ),
            ),
        )

        val normalized = normalizer.normalizeRunFinished(event, ttl)
        val outcome = normalized.outcome() as AguiEvent.RunFinishedInterruptOutcome
        val interrupt = outcome.interrupts().first()

        assertNotNull(interrupt.responseSchema())
        assertNotNull(interrupt.expiresAt())
    }
}
