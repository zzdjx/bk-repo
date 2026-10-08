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
import com.tencent.bkrepo.agent.context.AgentChatContextResolver
import com.tencent.bkrepo.agent.hitl.AguiResumeDenialAuditor
import com.tencent.bkrepo.agent.hitl.AguiResumeCoordinatorHydrator
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
            orchestrator().run("stranger-user", "stranger-project", input())
        }
    }

    @Test
    fun `灰度开启且项目在名单内应放行`() {
        val gray = AgentRuntimeProperties.Gray(enabled = true, allowedProjectIds = setOf("project-1"))

        assertThrows(MarkerException::class.java) {
            orchestrator(gray).run("any-user", "project-1", input())
        }
    }

    @Test
    fun `灰度开启且用户在名单内应放行`() {
        val gray = AgentRuntimeProperties.Gray(enabled = true, allowedUserIds = setOf("user-1"))

        assertThrows(MarkerException::class.java) {
            orchestrator(gray).run("user-1", "any-project", input())
        }
    }

    @Test
    fun `灰度开启且项目和用户都不在名单时应拒绝且不产生任何副作用`() {
        val gray = AgentRuntimeProperties.Gray(
            enabled = true,
            allowedProjectIds = setOf("project-1"),
            allowedUserIds = setOf("user-1"),
        )

        assertThrows(PermissionException::class.java) {
            orchestrator(gray).run("stranger-user", "stranger-project", input())
        }

        verify(inputValidator, never()).validate(any())
        verifyNoInteractions(activeRunManager, runRecordService, sessionService)
    }

    @Test
    fun `灰度名单变更后同一个runtimeProperties实例应立即按新名单判定_模拟配置中心热更新`() {
        val runtimeProperties = AgentRuntimeProperties(
            gray = AgentRuntimeProperties.Gray(enabled = true, allowedProjectIds = setOf("project-1")),
        )
        val orchestrator = orchestrator(runtimeProperties)

        assertThrows(PermissionException::class.java) {
            orchestrator.run("stranger-user", "stranger-project", input())
        }

        // 模拟 ConfigurationPropertiesRebinder 收到配置中心推送后原地改写同一个 bean 实例的字段。
        runtimeProperties.gray = AgentRuntimeProperties.Gray(
            enabled = true,
            allowedProjectIds = setOf("project-1", "stranger-project"),
        )

        assertThrows(MarkerException::class.java) {
            orchestrator.run("stranger-user", "stranger-project", input())
        }
    }

    private fun input(): RunAgentInput = RunAgentInput.builder()
        .threadId("thread-1")
        .runId("run-1")
        .build()

    private fun orchestrator(gray: AgentRuntimeProperties.Gray = AgentRuntimeProperties.Gray()): AgentRunOrchestrator =
        orchestrator(AgentRuntimeProperties(gray = gray))

    private fun orchestrator(runtimeProperties: AgentRuntimeProperties): AgentRunOrchestrator {
        whenever(inputValidator.validate(any())).thenThrow(MarkerException())
        return AgentRunOrchestrator(
            properties = AgentRuntimePropertiesResolver.resolve(runtimeProperties),
            runtimeProperties = runtimeProperties,
            permissionManager = mock<PermissionManager>(),
            inputValidator = inputValidator,
            aguiResumeValidator = mock<AguiResumeValidator>(),
            aguiResumeCoordinatorHydrator = mock<AguiResumeCoordinatorHydrator>(),
            aguiResumeDenialAuditor = mock<AguiResumeDenialAuditor>(),
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
