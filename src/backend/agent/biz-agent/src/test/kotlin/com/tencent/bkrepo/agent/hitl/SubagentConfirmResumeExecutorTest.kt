/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tencent.bkrepo.agent.agent.AgentCatalog
import com.tencent.bkrepo.agent.agent.AgentFactory
import com.tencent.bkrepo.agent.agent.client.ClientAgentDefinition
import com.tencent.bkrepo.agent.agent.discovery.ArtifactDiscoveryAgentDefinition
import com.tencent.bkrepo.agent.agent.transfer.TransferDiagnosticsAgentDefinition
import com.tencent.bkrepo.agent.config.AgentHarnessConfigurer
import com.tencent.bkrepo.agent.config.AgentMemoryConfig
import com.tencent.bkrepo.agent.config.AgentModelConfig
import com.tencent.bkrepo.agent.config.properties.AgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.AgentLlmPropertiesResolver
import com.tencent.bkrepo.agent.config.properties.AgentMemoryProperties
import com.tencent.bkrepo.agent.config.properties.AgentMemoryPropertiesResolver
import com.tencent.bkrepo.agent.config.properties.AgentRuntimeProperties
import com.tencent.bkrepo.agent.config.properties.AgentRuntimePropertiesResolver
import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.RegisteredDomainTools
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import com.tencent.bkrepo.agent.tool.frontend.RegisteredFrontendTools
import com.tencent.bkrepo.agent.tool.local.ExternalLocalTool
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.ToolCallState
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.gateway.SessionIdUtils
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证 [SubagentConfirmResumeExecutor] 能在完全不经过协调者大模型推理的情况下，直接把 `client` 子代理
 * 从持久化的 `ASKING` 状态续跑下去——这是修复"客户端点击确认后，协调者收到空消息、会话不终止"问题
 * （Bug 3）的核心组件，详见类注释。
 *
 * 两个用例共用同一套搭建：先直接调用 `client` 子代理（不经 `agent_spawn`/协调者），让它对
 * `set_download_path` 做出 `ASKING` 决策并持久化状态；再各自验证同意/拒绝两条恢复分支。
 */
@DisplayName("SubagentConfirmResumeExecutor 直接驱动子代理确认恢复")
class SubagentConfirmResumeExecutorTest {

    private lateinit var server: HttpServer
    private val receivedBodies = CopyOnWriteArrayList<String>()
    private val callIndex = AtomicInteger(0)
    private var responder: (Int) -> String = { _ -> textReplyChunks("stub") }

    private val threadId = "thread-resume-1"
    private val runId = "run-resume-2"
    private val userId = "user-resume-1"
    private val downloadPath = "/mnt/new-downloads"
    private val toolCallId = "call_setpath_1"

    @Test
    fun `同意后应再次挂起给客户端执行set_download_path`(@TempDir workspace: Path) {
        withStubServer(workspace) { harnessAgent ->
            responder = { idx ->
                when (idx) {
                    1 -> toolCallChunks(toolCallId, "set_download_path", """{"path":"$downloadPath"}""")
                    else -> error("同意路径不应触发第 $idx 次模型调用：允许的工具调用会直接挂起，不需要模型再次收尾")
                }
            }
            driveClientIntoAsking(harnessAgent)

            val executor = SubagentConfirmResumeExecutor(harnessAgent, SubagentHitlPromoter(FrontendToolCatalog()))
            val confirmResult = ConfirmResult(true, askingToolUseBlock())
            val events = executor.resume(
                threadId,
                runId,
                userId,
                AguiPermissionResumeAdapter.SubagentResumeTarget("client", confirmResult),
            ).collectList().block(Duration.ofSeconds(30)).orEmpty()

            val runFinished = events.filterIsInstance<AguiEvent.RunFinished>().singleOrNull()
            assertNotNull(runFinished) { "应产出唯一的 RunFinished 事件，实际 events=$events" }
            val outcome = runFinished!!.outcome()
            assertTrue(outcome is AguiEvent.RunFinishedInterruptOutcome) {
                "同意后 set_download_path 应再次挂起等待客户端执行，实际 outcome=$outcome"
            }
            val interrupts = (outcome as AguiEvent.RunFinishedInterruptOutcome).interrupts()
            assertTrue(interrupts.any { it.metadata()?.get("toolName") == "set_download_path" }) {
                "促升出的 interrupt 应关联到 set_download_path，实际 interrupts=$interrupts"
            }
            assertTrue(interrupts.none { it.reason() == "permission_confirm" }) {
                "不应重新弹一次确认框（否则说明确认没有真正解除 ASKING，又走回了 permission_confirm 分支），" +
                    "实际 interrupts=$interrupts"
            }
            assertTrue(interrupts.any { it.reason() == "tool_call" }) {
                "应是「请客户端本地执行」形状的 interrupt（reason=tool_call），实际 interrupts=$interrupts"
            }
        }
    }

    @Test
    fun `拒绝后应转发子代理的取消回复且正常结束`(@TempDir workspace: Path) {
        val cancelReply = "已取消，未修改下载目录。"
        withStubServer(workspace) { harnessAgent ->
            responder = { idx ->
                when (idx) {
                    1 -> toolCallChunks(toolCallId, "set_download_path", """{"path":"$downloadPath"}""")
                    2 -> textReplyChunks(cancelReply)
                    else -> error("拒绝路径最多应触发 2 次模型调用，实际第 $idx 次")
                }
            }
            driveClientIntoAsking(harnessAgent)

            val executor = SubagentConfirmResumeExecutor(harnessAgent, SubagentHitlPromoter(FrontendToolCatalog()))
            val confirmResult = ConfirmResult(false, askingToolUseBlock())
            val events = executor.resume(
                threadId,
                runId,
                userId,
                AguiPermissionResumeAdapter.SubagentResumeTarget("client", confirmResult),
            ).collectList().block(Duration.ofSeconds(30)).orEmpty()

            val runFinished = events.filterIsInstance<AguiEvent.RunFinished>().singleOrNull()
            assertNotNull(runFinished) { "应产出唯一的 RunFinished 事件，实际 events=$events" }
            assertTrue(runFinished!!.outcome() is AguiEvent.RunFinishedSuccessOutcome) {
                "拒绝后子代理应给出正常文本回复并结束，而不是再次挂起，实际 outcome=${runFinished.outcome()}"
            }
            val fullText = events.filterIsInstance<AguiEvent.TextMessageContent>().joinToString("") { it.delta() }
            assertTrue(fullText.isNotBlank()) {
                "应转发子代理自己给出的取消说明文本，实际 events=$events"
            }
        }
    }

    private fun askingToolUseBlock(): ToolUseBlock = ToolUseBlock(
        toolCallId,
        "set_download_path",
        mapOf("path" to downloadPath),
        null,
        null,
        ToolCallState.ASKING,
    )

    /** 启动桩模型服务、搭建 coordinator + `client` 子代理，测完自动关闭服务器。 */
    private fun withStubServer(workspace: Path, block: (HarnessAgent) -> Unit) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respondFromStub(exchange) }
        server.start()
        try {
            block(buildCoordinator(workspace))
        } finally {
            server.stop(0)
        }
    }

    /**
     * 直接调用 `client` 子代理（不经 agent_spawn/协调者），让它对 set_download_path 做出 ASKING 决策。
     *
     * sessionId 必须与 [SubagentConfirmResumeExecutor.resume] 重算出来的一致——即
     * `AgentFactory.toSubagentDeclaration` 给 `client` 配置 `persistSession(true)` 后，
     * `AgentSpawnTool` 会使用的 `"sub-" + SessionIdUtils.deterministicHash(threadId, agentId)`——
     * 这里手动复现同一算法，而不是调用真正的 `agent_spawn` 工具，是为了避免搭建协调者的 LLM 决策
     * 这一层，让测试只聚焦在 [SubagentConfirmResumeExecutor] 本身的续跑逻辑上。
     */
    private fun driveClientIntoAsking(harnessAgent: HarnessAgent) {
        val parentRc = RuntimeContext.builder().userId(userId).sessionId(threadId).build()
        val agent = harnessAgent.subagentAgentManager!!.createAgentIfPresent("client", parentRc).orElse(null)
            ?: error("未能创建 client 子代理，检查 AgentCatalog 装配")
        val childSessionId = "sub-" + SessionIdUtils.deterministicHash(threadId, "client")
        val childCtx = RuntimeContext.builder(parentRc).sessionId(childSessionId).userId(userId).build()
        val userMsg = Msg.builder().role(MsgRole.USER).textContent("帮我把下载目录改到 $downloadPath").build()
        val result = when (agent) {
            is ReActAgent -> agent.call(listOf(userMsg), childCtx)
            is HarnessAgent -> agent.call(listOf(userMsg), childCtx)
            else -> error("client 子代理类型不支持直接驱动: ${agent.javaClass}")
        }
        result.block(Duration.ofSeconds(30))
    }

    private fun buildCoordinator(workspace: Path): HarnessAgent {
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties(workspace = workspace.toString(), maxIters = 6),
        )
        val llmProperties = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "stub-api-key",
                modelName = "stub-model",
                stream = true,
            ),
        )
        val memoryProperties = AgentMemoryPropertiesResolver.resolve(
            AgentMemoryProperties(compactionEnabled = false, toolResultEvictionEnabled = false),
        )
        val agentCatalog = AgentCatalog(
            definitions = listOf(
                ClientAgentDefinition(),
                ArtifactDiscoveryAgentDefinition(),
                TransferDiagnosticsAgentDefinition(),
            ),
            runtimeProperties = runtimeProperties,
            agentFactory = AgentFactory(),
            domainToolRegistrar = object : RegisteredDomainTools {
                override val registeredToolNames = setOf(
                    DomainToolNames.LIST_REPOSITORIES,
                    DomainToolNames.GET_REPOSITORY_DETAIL,
                    DomainToolNames.GET_TRANSFER_TASK_STATUS,
                    DomainToolNames.GET_TRANSFER_ERROR_DETAIL,
                )
            },
            frontendToolRegistrar = object : RegisteredFrontendTools {
                override val registeredToolNames = LocalToolDefinitions.allTools().map { it.name }.toSet()
            },
        )
        val agentHarnessConfigurer = AgentHarnessConfigurer(
            agentMemoryConfig = AgentMemoryConfig(),
            agentCatalog = agentCatalog,
            permissionConfirmResumeMiddleware = PermissionConfirmResumeMiddleware(),
        )
        val coordinatorPermissionContext = AgentPermissionRulesConfiguration()
            .agentPermissionContext(runtimeProperties)
        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        LocalToolDefinitions.allTools().forEach { definition ->
            toolkit.registerAgentTool(ExternalLocalTool(definition))
        }
        return agentHarnessConfigurer.configure(
            properties = runtimeProperties,
            memory = memoryProperties,
            model = AgentModelConfig().agentChatModel(llmProperties),
            stateStore = InMemoryAgentStateStore(),
            toolkit = toolkit,
            permissionContext = coordinatorPermissionContext,
        )
    }

    /**
     * 框架在每次 `agent.call()` 结束后会异步触发一次"记忆提炼"模型调用（system 提示词含
     * `memory extraction assistant`），与本测试要精确控制的推理轮次调用共用同一个桩服务器/端口，
     * 且是并发触发、到达顺序不确定——如果照常让它走 [callIndex]，会把后续真正推理调用的序号错位。
     * 这里按请求体内容单独识别并直接兜底为 `NO_REPLY`，不消耗 [callIndex]。
     */
    private fun isMemoryExtractionRequest(requestBody: String): Boolean =
        requestBody.contains("memory extraction assistant")

    private fun respondFromStub(exchange: HttpExchange) {
        exchange.use {
            val requestBody = it.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            receivedBodies.add(requestBody)
            it.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            it.sendResponseHeaders(200, 0)
            val body = if (isMemoryExtractionRequest(requestBody)) {
                textReplyChunks("NO_REPLY")
            } else {
                responder(callIndex.incrementAndGet())
            }
            it.responseBody.write(body.toByteArray(StandardCharsets.UTF_8))
            it.responseBody.flush()
        }
    }

    private fun toolCallChunks(callId: String, toolName: String, argumentsJson: String): String {
        val escapedArgs = escapeJsonString(argumentsJson)
        val toolCallDelta = "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"$callId\"," +
            "\"type\":\"function\",\"function\":{\"name\":\"$toolName\",\"arguments\":\"$escapedArgs\"}}]}"
        return deltaChunk(toolCallDelta) + deltaChunk("{}", finishReason = "tool_calls") + doneChunk()
    }

    private fun textReplyChunks(text: String): String =
        text.mapIndexed { index, char ->
            val contentJson = escapeJsonString(char.toString())
            val delta = if (index == 0) {
                """{"role":"assistant","content":"$contentJson"}"""
            } else {
                """{"content":"$contentJson"}"""
            }
            deltaChunk(delta)
        }
            .plus(deltaChunk("{}", finishReason = "stop"))
            .plus(doneChunk())
            .joinToString("")

    private fun deltaChunk(delta: String, finishReason: String? = null): String {
        val finish = finishReason?.let { "\"$it\"" } ?: "null"
        return "data: {\"id\":\"chatcmpl-resume\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":$delta," +
            "\"finish_reason\":$finish}]}\n\n"
    }

    private fun doneChunk(): String = "data: [DONE]\n\n"

    private fun escapeJsonString(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
