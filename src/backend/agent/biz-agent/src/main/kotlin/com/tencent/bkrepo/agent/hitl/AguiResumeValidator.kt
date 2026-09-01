/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.hitl.AgentInterruptStateRepository
import com.tencent.bkrepo.agent.hitl.AguiResumeValidator
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 校验 AG-UI resume[]：覆盖全部 pending interrupt、拒绝非法 interruptId、拒绝已过期的确认卡片，
 * 并在写工具执行前重新 IAM 鉴权。
 */
@Component
class AguiResumeValidator(
    private val interruptStateRepository: AgentInterruptStateRepository,
    private val frontendToolCatalog: FrontendToolCatalog,
    private val permissionManager: PermissionManager,
    private val objectMapper: ObjectMapper,
) {

    fun validateAndPrepare(userId: String, projectId: String, input: RunAgentInput) {
        val threadId = input.threadId
        val pending = interruptStateRepository.getPendingInterrupt(threadId)

        if (pending != null && !input.hasResume()) {
            throw ParameterInvalidException("resume: thread[$threadId] has pending interrupts")
        }
        if (!input.hasResume()) {
            return
        }

        if (pending == null) {
            throw ParameterInvalidException("resume: no pending interrupts for thread[$threadId]")
        }

        val resumeEntries = input.resume
        val resumeIds = resumeEntries.map { it.interruptId }.toSet()
        val pendingIds = pending.interrupts.map { it.id }.toSet()
        if (resumeIds != pendingIds) {
            throw ParameterInvalidException("resume: resume must cover all pending interrupts exactly once")
        }

        for (entry in resumeEntries) {
            val snapshot = pending.interrupts.first { it.id == entry.interruptId }
            validateEntry(userId, projectId, threadId, snapshot, entry)
        }
    }

    private fun validateEntry(
        userId: String,
        projectId: String,
        threadId: String,
        snapshot: PendingInterruptSnapshot,
        entry: AguiResume,
    ) {
        assertNotExpired(entry.interruptId, snapshot)

        if (!interruptStateRepository.tryMarkResume(threadId, entry.interruptId, fingerprint(entry))) {
            throw ParameterInvalidException("resume: duplicate resume for interrupt[${entry.interruptId}]")
        }

        if (!isResolved(entry)) {
            return
        }

        val toolName = snapshot.toolName
        if (toolName.isNullOrBlank()) {
            return
        }

        if (frontendToolCatalog.find(toolName) == null) {
            throw ParameterInvalidException("resume: tool[$toolName] is not allowed")
        }

        val approved = extractApproved(entry.payload)
        if (snapshot.requiresApproval && approved != true) {
            return
        }

        if (frontendToolCatalog.isWriteTool(toolName) && isExecutableResume(entry, snapshot)) {
            permissionManager.checkProjectPermission(PermissionAction.READ, projectId, userId)
        }
    }

    /**
     * `snapshot.expiresAt` 在持久化时由 [AguiInterruptNormalizer.normalizeSnapshot] 按
     * `activeRunTtl` 兜底填充，是客户端确认卡片上展示的"多久后失效"。此前这里从未校验，真正拦住过老
     * resume 的只是 Redis 存储本身的 TTL（`sessionTtl`，默认 30 天，远长于 `activeRunTtl` 的 11 分钟）
     * ——也就是说过期的确认卡片在这个窗口内其实一直能被 resume 执行，与 UI 上的过期时间不符。
     * 这里显式按 `expiresAt` 拒绝迟到的 resume，让服务端行为与客户端展示的过期时间一致。
     */
    private fun assertNotExpired(interruptId: String, snapshot: PendingInterruptSnapshot) {
        val expiresAt = snapshot.expiresAt?.takeIf { it.isNotBlank() } ?: return
        val deadline = try {
            Instant.parse(expiresAt)
        } catch (_: Exception) {
            return
        }
        if (Instant.now().isAfter(deadline)) {
            throw ParameterInvalidException("resume: interrupt[$interruptId] has expired at $expiresAt")
        }
    }

    private fun isExecutableResume(entry: AguiResume, snapshot: PendingInterruptSnapshot): Boolean {
        if (snapshot.requiresApproval) {
            return extractApproved(entry.payload) == true
        }
        return entry.payload != null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractApproved(payload: Any?): Boolean? {
        if (payload == null) return null
        if (payload is Boolean) return payload
        if (payload is Map<*, *>) {
            return payload["approved"] as? Boolean
        }
        return null
    }

    private fun isResolved(entry: AguiResume): Boolean =
        RESOLVED_STATUS.equals(entry.status, ignoreCase = true)

    private fun fingerprint(entry: AguiResume): String {
        return try {
            objectMapper.writeValueAsString(
                mapOf(
                    "status" to entry.status,
                    "payload" to entry.payload,
                ),
            )
        } catch (_: Exception) {
            "${entry.status}:${entry.payload}"
        }
    }

    companion object {
        private const val RESOLVED_STATUS = "resolved"
    }
}
