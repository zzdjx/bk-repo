/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.usage

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tencent.bkrepo.agent.agent.AgentCatalog
import com.tencent.bkrepo.agent.agent.AgentFactory
import com.tencent.bkrepo.agent.agent.discovery.ArtifactDiscoveryAgentDefinition
import com.tencent.bkrepo.agent.audit.NoopAgentToolCallRecordService
import com.tencent.bkrepo.agent.audit.ToolAuditMiddleware
import com.tencent.bkrepo.agent.config.AgentHarnessConfigurer
import com.tencent.bkrepo.agent.config.AgentMemoryConfig
import com.tencent.bkrepo.agent.config.AgentModelConfig
import com.tencent.bkrepo.agent.config.properties.AgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.AgentLlmPropertiesResolver
import com.tencent.bkrepo.agent.config.properties.AgentMemoryProperties
import com.tencent.bkrepo.agent.config.properties.AgentMemoryPropertiesResolver
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentTopology
import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_RUN_ID
import com.tencent.bkrepo.agent.hitl.PermissionConfirmResumeMiddleware
import com.tencent.bkrepo.agent.subagent.DelegationBudgetMiddleware
import com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard
import com.tencent.bkrepo.agent.subagent.NoopTaskRepositoryProvider
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.RegisteredDomainTools
import com.tencent.bkrepo.agent.tool.frontend.RegisteredFrontendTools
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.message.UserMessage
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration

/**
 * 验证 [UsageTrackingMiddleware] 真正接入 [io.agentscope.harness.agent.HarnessAgent] 后能按 runId
 * 落到 `agent_run` 记录上，而不仅仅是单元测试里孤立调用 `onModelCall`——回归"钩子签名对但没被框架实际调用"
 * 这类装配错误。
 */
@DisplayName("UsageTrackingMiddleware接入HarnessAgent后的用量落库冒烟测试")
class UsageTrackingMiddlewareTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun startStubModelServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respondWithChatCompletionChunks(exchange) }
        server.start()
    }

    @AfterEach
    fun stopStubModelServer() {
        server.stop(0)
    }

    @Test
    fun `一轮无工具对话结束后应记录一次成功的模型调用用量`(@TempDir workspace: Path) {
        val runtimeProperties = EffectiveAgentRuntimeProperties(
            name = "usage-smoke-agent",
            sysPrompt = "test",
            maxIters = 3,
            workspace = workspace.toString(),
            sseTimeout = Duration.ofMinutes(10),
            maxMessageLength = 32 * 1024,
            maxThreadIdLength = 128,
            sessionTtl = Duration.ofDays(30),
            activeRunTtl = Duration.ofMinutes(11),
            runEventTtl = Duration.ofDays(7),
            reconnectPollInterval = Duration.ofMillis(500),
            reconnectTimeout = Duration.ofMinutes(10),
            stateKeyPrefix = "bkrepo:agent:state:",
            requireRedis = false,
            taskStoreKeyPrefix = "bkrepo:agent:task-store:",
            frontendToolsEnabled = false,
            topology = EffectiveAgentTopology.defaults(),
        )
        val llmProperties = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "stub-api-key",
                modelName = "usage-stub-model",
                stream = true,
            ),
        )
        val memoryProperties = AgentMemoryPropertiesResolver.resolve(
            AgentMemoryProperties(compactionEnabled = false, toolResultEvictionEnabled = false),
        )
        val agentCatalog = AgentCatalog(
            definitions = listOf(ArtifactDiscoveryAgentDefinition()),
            runtimeProperties = runtimeProperties,
            agentFactory = AgentFactory(),
            domainToolRegistrar = object : RegisteredDomainTools {
                override val registeredToolNames = setOf(
                    DomainToolNames.LIST_REPOSITORIES,
                    DomainToolNames.GET_REPOSITORY_DETAIL,
                )
            },
            frontendToolRegistrar = object : RegisteredFrontendTools {
                override val registeredToolNames = emptySet<String>()
            },
        )
        val recordingUsageService = RecordingAgentRunRecordService()
        val agentHarnessConfigurer = AgentHarnessConfigurer(
            agentMemoryConfig = AgentMemoryConfig(),
            agentCatalog = agentCatalog,
            permissionConfirmResumeMiddleware = PermissionConfirmResumeMiddleware(),
            usageTrackingMiddleware = UsageTrackingMiddleware(recordingUsageService),
            toolAuditMiddleware = ToolAuditMiddleware(NoopAgentToolCallRecordService(), ObjectMapper()),
            delegationBudgetMiddleware = DelegationBudgetMiddleware(
                runtimeProperties,
                DelegationConcurrencyGuard(),
                NoopTaskRepositoryProvider(),
            ),
        )
        val agent = agentHarnessConfigurer.configure(
            properties = runtimeProperties,
            memory = memoryProperties,
            model = AgentModelConfig().agentChatModel(llmProperties),
            stateStore = InMemoryAgentStateStore(),
            toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build()),
            permissionContext = PermissionContextState.builder().build(),
        )
        val runtimeContext = RuntimeContext.builder()
            .userId("usage-smoke-user")
            .sessionId("usage-smoke-session")
            .put(RUNTIME_CONTEXT_RUN_ID, "usage-smoke-run")
            .build()

        agent.streamEvents(UserMessage("usage-smoke-user", "你好"), runtimeContext)
            .collectList()
            .block(Duration.ofSeconds(60))

        assertEquals(1, recordingUsageService.calls.size) {
            "应恰好记录一次模型调用用量，实际=${recordingUsageService.calls}"
        }
        val recorded = recordingUsageService.calls.single()
        assertEquals("usage-smoke-run", recorded.runId)
    }

    private fun respondWithChatCompletionChunks(exchange: HttpExchange) {
        exchange.use {
            it.responseHeaders.add("Content-Type", "text/event-stream")
            it.sendResponseHeaders(200, 0)
            val body = REPLY_TEXT.map { char -> deltaChunk("""{"content":"$char"}""") }
                .plus(deltaChunk("{}", finishReason = "stop"))
                .plus("data: [DONE]\n\n")
                .joinToString("")
            it.responseBody.write(body.toByteArray(StandardCharsets.UTF_8))
            it.responseBody.flush()
        }
    }

    private fun deltaChunk(delta: String, finishReason: String? = null): String {
        val finish = finishReason?.let { "\"$it\"" } ?: "null"
        return "data: {\"id\":\"chatcmpl-usage-smoke\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"usage-stub-model\",\"choices\":[{\"index\":0,\"delta\":$delta," +
            "\"finish_reason\":$finish}]}\n\n"
    }

    companion object {
        private const val REPLY_TEXT = "你好"
    }
}
