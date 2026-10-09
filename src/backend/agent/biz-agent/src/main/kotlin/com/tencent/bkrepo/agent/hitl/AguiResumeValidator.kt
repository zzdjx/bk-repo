/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 校验 AG-UI `resume[]` 并登记受理。
 *
 * 一共五条规则：thread 上有未决 interrupt 就必须带 resume（反之亦然）、resume 必须不多不少覆盖全部
 * 未决 interrupt、确认卡片不能过期、涉及的工具必须在服务端 allowlist 里、同一份 resume 不能重复受理。
 *
 * ## 这里不做权限校验
 *
 * 此前这里对写工具补了一次 `checkProjectPermission(READ)`，并在注释里称其为"二次 IAM"。它名不副实：
 * 查的是项目读权限，而 [com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator] 在同一次请求里
 * 已经先查过同一个权限，等于同一个检查做两遍，对删除类工具没有提供任何额外保护。
 *
 * 需要知道的是：resume 涉及的是前端工具，执行发生在客户端，服务端没有执行时闸门（服务端执行的 domain
 * 工具由 [com.tencent.bkrepo.agent.tool.gateway.DomainToolGateway] 逐次做资源级鉴权，不走这条路）。
 * 真要给写工具加服务端闸门，应该是按工具风险等级查写权限或资源级权限，那会收紧现有行为——只有读权限的
 * 用户将无法再批准写工具，属于产品决策，不在重构范围内。这里只保证不用一个假检查把这件事盖过去。
 */
@Component
class AguiResumeValidator(
    private val interruptStateRepository: AgentInterruptStateRepository,
    private val frontendToolCatalog: FrontendToolCatalog,
    private val objectMapper: ObjectMapper,
) {

    /**
     * 校验与登记分成两趟：先把所有条目校验完，全部通过之后再统一登记幂等标记。
     *
     * 顺序不能反。登记是不可回滚的写操作，一旦写在校验中间，被拒的 resume 也会把幂等名额吃掉——用户
     * 补齐条件后再点同一张确认卡片，收到的会是"重复提交"，那张卡片在幂等 TTL 内彻底点不动。多条
     * interrupt 一起 resume 时更糟：前一条已登记、后一条校验失败，前一条的名额同样白白消耗。
     */
    fun validateAndAccept(input: RunAgentInput) {
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

        val entries = input.resume
        val snapshotById = pending.interrupts.associateBy { it.id }
        if (entries.map { it.interruptId }.toSet() != snapshotById.keys) {
            throw ParameterInvalidException("resume: resume must cover all pending interrupts exactly once")
        }

        entries.forEach { validateEntry(it, snapshotById.getValue(it.interruptId)) }
        entries.forEach { acceptOnce(threadId, it) }
    }

    private fun validateEntry(entry: AguiResume, snapshot: PendingInterruptSnapshot) {
        assertNotExpired(entry.interruptId, snapshot)
        if (!isResolved(entry)) {
            return
        }
        val toolName = snapshot.toolName?.takeIf { it.isNotBlank() } ?: return
        if (frontendToolCatalog.find(toolName) == null) {
            throw ParameterInvalidException("resume: tool[$toolName] is not allowed")
        }
    }

    /**
     * `snapshot.expiresAt` 在持久化时由 [AguiInterruptNormalizer.normalizeSnapshot] 按 `activeRunTtl`
     * 兜底填充，是客户端确认卡片上展示的"多久后失效"。此前这里从未校验，真正拦住过老 resume 的只是
     * `agent_pending_interrupt` 文档本身的 TTL（`sessionTtl`，默认 30 天，远长于 `activeRunTtl` 的
     * 11 分钟）——也就是说过期的确认卡片在这个窗口内其实一直能被 resume 执行，与 UI 上的过期时间不符。
     *
     * 解析不了的 `expiresAt` 按过期拒绝，而不是放行：这个字段是服务端自己按 ISO-8601 写进去的，解析
     * 失败只可能是数据被改坏或格式变更，这两种情况下都没有理由相信这张卡片还有效。
     */
    private fun assertNotExpired(interruptId: String, snapshot: PendingInterruptSnapshot) {
        val expiresAt = snapshot.expiresAt?.takeIf { it.isNotBlank() } ?: return
        val deadline = runCatching { Instant.parse(expiresAt) }.getOrNull()
            ?: throw ParameterInvalidException("resume: interrupt[$interruptId] has an unreadable deadline")
        if (Instant.now().isAfter(deadline)) {
            throw ParameterInvalidException("resume: interrupt[$interruptId] has expired at $expiresAt")
        }
    }

    private fun acceptOnce(threadId: String, entry: AguiResume) {
        if (!interruptStateRepository.tryMarkResume(threadId, entry.interruptId, fingerprint(entry))) {
            throw ParameterInvalidException("resume: duplicate resume for interrupt[${entry.interruptId}]")
        }
    }

    private fun isResolved(entry: AguiResume): Boolean =
        RESOLVED_STATUS.equals(entry.status, ignoreCase = true)

    /** 指纹覆盖 resume 的内容：改了回答内容就不算重复提交，原样再发一次才算。 */
    private fun fingerprint(entry: AguiResume): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "status" to entry.status,
                "payload" to entry.payload,
            ),
        )
    }

    companion object {
        private const val RESOLVED_STATUS = "resolved"
    }
}
