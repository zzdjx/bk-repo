/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.agui.AguiMessageArchiveHandler
import com.tencent.bkrepo.agent.config.properties.AgentRuntimeProperties
import com.tencent.bkrepo.agent.config.properties.AgentRuntimePropertiesResolver
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
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import com.tencent.bkrepo.common.security.exception.PermissionException
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.processor.AguiRequestProcessor
import io.agentscope.core.shutdown.GracefulShutdownManager
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** 验证灰度总闸只在开启时生效、按项目/用户名单 OR 放行，拦下时不产生任何副作用。 */
@DisplayName("AgentRunOrchestrator灰度总闸单测")
class AgentRunOrchestratorGrayReleaseTest {

    private val inputValidator = mock<RunAgentInputValidator>()
    private val activeRunManager = mock<ActiveRunManager>()
    private val runRecordService = mock<AgentRunRecordService>()
    private val sessionService = mock<AgentSessionService>()
    private val shutdownManager = mock<GracefulShutdownManager>().apply {
        whenever(isAcceptingRequests).thenReturn(true)
    }

    @Test
    fun `灰度关闭时任何项目和用户都应放行`() {
        assertThrows(MarkerException::class.java) {
            orchestrator(grayProperties()).run("stranger-user", "stranger-project", input())
        }
    }

    @Test
    fun `灰度开启且项目在名单内应放行`() {
        val properties = grayProperties(
            AgentRuntimeProperties.Gray(enabled = true, allowedProjectIds = setOf("project-1")),
        )

        assertThrows(MarkerException::class.java) {
            orchestrator(properties).run("any-user", "project-1", input())
        }
    }

    @Test
    fun `灰度开启且用户在名单内应放行`() {
        val properties = grayProperties(
            AgentRuntimeProperties.Gray(enabled = true, allowedUserIds = setOf("user-1")),
        )

        assertThrows(MarkerException::class.java) {
            orchestrator(properties).run("user-1", "any-project", input())
        }
    }

    @Test
    fun `灰度开启且项目和用户都不在名单时应拒绝且不产生任何副作用`() {
        val properties = grayProperties(
            AgentRuntimeProperties.Gray(
                enabled = true,
                allowedProjectIds = setOf("project-1"),
                allowedUserIds = setOf("user-1"),
            ),
        )

        assertThrows(PermissionException::class.java) {
            orchestrator(properties).run("stranger-user", "stranger-project", input())
        }

        verify(inputValidator, never()).validate(any())
        verifyNoInteractions(activeRunManager, runRecordService, sessionService)
    }

    private fun grayProperties(gray: AgentRuntimeProperties.Gray = AgentRuntimeProperties.Gray()):
        EffectiveAgentRuntimeProperties =
        AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties(gray = gray))

    private fun input(): RunAgentInput = RunAgentInput.builder()
        .threadId("thread-1")
        .runId("run-1")
        .build()

    private fun orchestrator(properties: EffectiveAgentRuntimeProperties): AgentRunOrchestrator {
        whenever(inputValidator.validate(any())).thenThrow(MarkerException())
        return AgentRunOrchestrator(
            properties = properties,
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
    }

    /** 用来证明"灰度总闸放行了、请求继续往下走到了输入校验"，避免为此把整条 run 链路都搭起来。 */
    private class MarkerException : RuntimeException("reached input validation")
}
