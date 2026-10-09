/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.session.InMemoryAgentPendingInterruptStore
import com.tencent.bkrepo.agent.session.InMemoryAgentResumeIdempotencyStore
import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 覆盖 [AguiResumeValidator] 此前完全没有单测的两块行为：
 * 1. 过期确认卡片应被拒绝——回归"服务端从不校验 `expiresAt`，只靠 30 天 sessionTtl 兜底"的问题；
 * 2. 重复 resume / 缺失 pending interrupt 的既有幂等与校验逻辑。
 */
@DisplayName("AguiResumeValidator：resume 过期、重复与缺失校验")
class AguiResumeValidatorTest {

    private val interruptStateRepository = DefaultAgentInterruptStateRepository(
        pendingInterruptStore = InMemoryAgentPendingInterruptStore(),
        resumeIdempotencyStore = InMemoryAgentResumeIdempotencyStore(),
    )
    private val validator = AguiResumeValidator(
        interruptStateRepository = interruptStateRepository,
        frontendToolCatalog = FrontendToolCatalog(),
        objectMapper = ObjectMapper(),
    )

    private val threadId = "thread-resume-1"

    @Test
    @DisplayName("确认卡片整组过期时应拒绝 resume，而不是静默放行到 30 天后才失效")
    fun `rejects resume for an already expired interrupt`() {
        val interruptId = "int-expired-1"
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                expiresAt = Instant.now().minusSeconds(60).toString(),
            ),
        )

        val ex = assertThrows(ParameterInvalidException::class.java) {
            validator.validateAndAccept(resumeInput(interruptId))
        }
        // 整组过期会被 DefaultAgentInterruptStateRepository.getPendingInterrupt 当成"没有未决中断"
        // 并顺手清掉——那一步同时解开了"既 resume 不了又发不了新消息"的死锁，所以这里看到的是缺失
        // 而不是过期。单张卡片过期的拦截见下一条用例。
        assert(ex.parameter().contains("no pending interrupts")) {
            "异常信息应指出没有可 resume 的中断，实际=${ex.parameter()}"
        }
    }

    @Test
    @DisplayName("同一组里只有这张卡片过期时应按过期拒绝")
    fun `rejects resume for an expired interrupt while a sibling is still valid`() {
        val interruptId = "int-expired-2"
        interruptStateRepository.savePendingInterrupt(
            threadId,
            PendingInterruptSession(
                originRunId = "run-origin-1",
                interrupts = listOf(
                    PendingInterruptSnapshot(
                        id = interruptId,
                        reason = "tool_call",
                        expiresAt = Instant.now().minusSeconds(60).toString(),
                    ),
                    PendingInterruptSnapshot(
                        id = "int-still-valid",
                        reason = "tool_call",
                        expiresAt = Instant.now().plusSeconds(600).toString(),
                    ),
                ),
            ),
        )

        // 2.0.4 要求 resume[] 覆盖全部未决中断，少一条会先被"覆盖不全"挡下，走不到过期判断。
        val ex = assertThrows(ParameterInvalidException::class.java) {
            validator.validateAndAccept(resumeInput(interruptId, "int-still-valid"))
        }
        assert(ex.parameter().contains("expired")) {
            "异常信息应指出过期原因，实际=${ex.parameter()}"
        }
    }

    @Test
    @DisplayName("确认卡片未过期时 resume 应正常通过")
    fun `accepts resume for an interrupt that has not expired yet`() {
        val interruptId = "int-not-expired-1"
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                expiresAt = Instant.now().plusSeconds(600).toString(),
            ),
        )

        assertDoesNotThrow {
            validator.validateAndAccept(resumeInput(interruptId))
        }
    }

    @Test
    @DisplayName("旧快照没有 expiresAt 字段时不应被误判为过期")
    fun `does not reject resume when expiresAt is missing`() {
        val interruptId = "int-no-expiry-1"
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                expiresAt = null,
            ),
        )

        assertDoesNotThrow {
            validator.validateAndAccept(resumeInput(interruptId))
        }
    }

    @Test
    @DisplayName("同一个 interruptId 重复 resume 第二次应被拒绝")
    fun `rejects duplicate resume for the same interrupt`() {
        val interruptId = "int-dup-1"
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                expiresAt = Instant.now().plusSeconds(600).toString(),
            ),
        )

        validator.validateAndAccept(resumeInput(interruptId))
        // 幂等仓库不会因为校验通过就清掉 pending interrupt（清理由编排层负责），
        // 这里直接用同一个 threadId 再 resume 一次同一个 interruptId 来复现重复提交。
        val ex = assertThrows(ParameterInvalidException::class.java) {
            validator.validateAndAccept(resumeInput(interruptId))
        }
        assert(ex.parameter().contains("duplicate resume")) {
            "异常信息应指出重复 resume，实际=${ex.parameter()}"
        }
    }

    @Test
    @DisplayName("resume 里缺少某个 pending interrupt 时应拒绝")
    fun `rejects resume that does not cover all pending interrupts`() {
        val interruptId = "int-missing-1"
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                expiresAt = Instant.now().plusSeconds(600).toString(),
            ),
        )

        val ex = assertThrows(ParameterInvalidException::class.java) {
            validator.validateAndAccept(resumeInput("some-other-interrupt-id"))
        }
        assert(ex.parameter().contains("must cover all pending interrupts")) {
            "异常信息应指出未覆盖全部 pending interrupt，实际=${ex.parameter()}"
        }
    }

    @Test
    @DisplayName("校验失败的 resume 不应吃掉幂等名额，补齐条件后重试必须还能通过")
    fun `does not consume the idempotency slot when validation fails`() {
        val interruptId = "int-rejected-1"
        // 工具不在服务端 allowlist 里，校验会在工具检查这一步失败。
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                toolName = "not_a_registered_tool",
                expiresAt = Instant.now().plusSeconds(600).toString(),
            ),
        )
        assertThrows(ParameterInvalidException::class.java) {
            validator.validateAndAccept(resumeInput(interruptId))
        }

        // 把工具换成合法的（等价于用户补齐条件后重试同一张确认卡片）。
        savePending(
            PendingInterruptSnapshot(
                id = interruptId,
                reason = "tool_call",
                expiresAt = Instant.now().plusSeconds(600).toString(),
            ),
        )
        assertDoesNotThrow({ validator.validateAndAccept(resumeInput(interruptId)) }, "被拒的 resume 不该把幂等名额占掉")
    }

    private fun savePending(snapshot: PendingInterruptSnapshot) {
        interruptStateRepository.savePendingInterrupt(
            threadId,
            PendingInterruptSession(originRunId = "run-origin-1", interrupts = listOf(snapshot)),
        )
    }

    /** [ErrorCodeException.message] 只返回错误码，真正的排查信息存在 [ErrorCodeException.params] 里。 */
    private fun ErrorCodeException.parameter(): String = params.first() as String

    private fun resumeInput(vararg interruptIds: String): RunAgentInput =
        RunAgentInput.builder()
            .threadId(threadId)
            .runId("run-resume-1")
            .resume(
                interruptIds.map { AguiResume(it, AguiResume.STATUS_RESOLVED, mapOf("approved" to true)) },
            )
            .build()
}
