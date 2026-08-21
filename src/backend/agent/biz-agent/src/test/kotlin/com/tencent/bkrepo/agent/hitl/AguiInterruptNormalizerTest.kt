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
    fun `uses approval schema for write tools when reason is permission_confirm`() {
        val interrupt = AguiEvent.Interrupt(
            "int-2",
            "permission_confirm",
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

    /**
     * `reason=tool_call` 是"请客户端本地真正执行该工具"的唯一形态，即便工具本身是写工具
     * （如 `set_download_path`），也不应被写工具兜底规则误判成需要再弹一次是/否确认框——否则
     * 客户端 `agentRunLoop.ts` 的 `isApprovalInterrupt` 会截断掉真正的 `executeLocalTools` 调用，
     * 写操作从始至终不会真正执行（这正是修复前的回归行为，见 [AguiInterruptNormalizer.requiresApproval]
     * 的类注释）。
     */
    @Test
    fun `does not force approval schema for write tools when reason is tool_call`() {
        val interrupt = AguiEvent.Interrupt(
            "int-2b",
            "tool_call",
            "等待客户端执行 set_download_path",
            "tc-2b",
            null,
            null,
            mapOf("toolName" to "set_download_path"),
        )

        val normalized = normalizer.normalizeInterrupt(interrupt, ttl, "set_download_path")
        val properties = (normalized.responseSchema() as Map<*, *>)["properties"] as Map<*, *>?

        assertTrue(properties == null || !properties.containsKey("approved"))
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
