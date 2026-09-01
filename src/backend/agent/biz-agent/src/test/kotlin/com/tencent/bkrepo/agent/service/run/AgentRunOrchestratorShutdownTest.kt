/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.agui.AguiMessageArchiveHandler
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.context.AgentChatContextResolver
import com.tencent.bkrepo.agent.hitl.AguiPermissionResumeAdapter
import com.tencent.bkrepo.agent.hitl.AguiResumeValidator
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.runtime.AgentRunReplaySinkRegistry
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import com.tencent.bkrepo.agent.service.AgentSessionService
import com.tencent.bkrepo.agent.session.AgentSessionStore
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolSanitizer
import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.processor.AguiRequestProcessor
import io.agentscope.core.shutdown.GracefulShutdownManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * 验证停机窗口内的新 run 请求会被前置拦成 503，而且拦下来之前不产生任何副作用。
 *
 * 这里 mock 的是 [GracefulShutdownManager]（而不是像 `AgentRunShutdownHandlerTest` 那样操作真实单例），
 * 因为本用例只关心"不接受新请求时编排器怎么反应"，用真单例反而要在测试之间小心复位停机状态。
 */
@DisplayName("AgentRunOrchestrator停机前置检查单测")
class AgentRunOrchestratorShutdownTest {

    private val inputValidator = mock<RunAgentInputValidator>()
    private val activeRunManager = mock<ActiveRunManager>()
    private val runRecordService = mock<AgentRunRecordService>()
    private val sessionService = mock<AgentSessionService>()
    private val shutdownManager = mock<GracefulShutdownManager>()

    @Test
    fun `停机中应直接返回503且不建run记录不抢会话锁`() {
        whenever(shutdownManager.isAcceptingRequests).thenReturn(false)

        val exception = assertThrows(ErrorCodeException::class.java) {
            orchestrator().run("user-1", "project-1", input())
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status)
        verifyNoInteractions(inputValidator, activeRunManager, runRecordService, sessionService)
    }

    @Test
    fun `正常运行时前置检查应放行并继续走入参校验`() {
        whenever(shutdownManager.isAcceptingRequests).thenReturn(true)
        val input = input()
        whenever(inputValidator.validate(input)).thenThrow(MarkerException())

        assertThrows(MarkerException::class.java) { orchestrator().run("user-1", "project-1", input) }

        verify(inputValidator).validate(input)
    }

    private fun input(): RunAgentInput = RunAgentInput.builder()
        .threadId("thread-1")
        .runId("run-1")
        .build()

    private fun orchestrator(): AgentRunOrchestrator = AgentRunOrchestrator(
        properties = EffectiveAgentRuntimeProperties.defaults(),
        permissionManager = mock<PermissionManager>(),
        inputValidator = inputValidator,
        aguiResumeValidator = mock<AguiResumeValidator>(),
        aguiPermissionResumeAdapter = mock<AguiPermissionResumeAdapter>(),
        frontendToolSanitizer = mock<FrontendToolSanitizer>(),
        agentSessionService = sessionService,
        agentRunRecordService = runRecordService,
        activeRunManager = activeRunManager,
        replaySinkRegistry = mock<AgentRunReplaySinkRegistry>(),
        agentSessionStore = mock<AgentSessionStore>(),
        agentChatContextResolver = mock<AgentChatContextResolver>(),
        aguiRequestProcessor = mock<AguiRequestProcessor>(),
        messageArchiveHandler = mock<AguiMessageArchiveHandler>(),
        agentRunStreamOrchestrator = mock<AgentRunStreamOrchestrator>(),
        lifecycleManager = mock<AgentRunLifecycleManager>(),
        eventPipeline = mock<AgentRunEventPipeline>(),
        shutdownManager = shutdownManager,
    )

    /** 用来证明"前置检查放行了"，避免为此把整条 run 链路都搭起来。 */
    private class MarkerException : RuntimeException("reached input validation")
}
