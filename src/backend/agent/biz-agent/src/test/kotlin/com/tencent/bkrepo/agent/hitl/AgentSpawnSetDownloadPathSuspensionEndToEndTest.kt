/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.fasterxml.jackson.databind.ObjectMapper
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
import com.tencent.bkrepo.agent.session.HarnessAgentResolver
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.RegisteredDomainTools
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import com.tencent.bkrepo.agent.tool.frontend.RegisteredFrontendTools
import com.tencent.bkrepo.agent.tool.local.ExternalLocalTool
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.adapter.AguiAdapterConfig
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.AguiMessage
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.model.ToolMergeMode
import io.agentscope.core.agui.processor.AguiRequestProcessor
import io.agentscope.core.agui.registry.AguiAgentRegistry
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
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
 * 端到端验证「client 子 Agent 执行 `set_download_path` 时，客户端没收到确认弹窗、目录也没被真正
 * 修改」这个问题在两处修复落地之后已经被解决：
 *
 * 1. [ExternalLocalTool.checkPermissions] 按风险等级自检 ASK（修复
 *    `SubagentPermissionInheritanceTest` 记录的"子 Agent 拿不到协调者 ASK 规则表"问题）——这是本测试
 *    实际观测到的促升路径：写操作在真正 `callAsync` 之前就先在 `client` 子 Agent 内部被拦成
 *    `RequireUserConfirmEvent`，经 [SubagentHitlPromoter.buildFromRequireConfirm] 促升为
 *    `RUN_FINISHED(interrupt)`。
 * 2. [SubagentHitlPromoter.buildFromSuspendedToolResult]（见下方"已确认的根因"）兜底覆盖用户已经
 *    确认之后（`ToolCallState.ALLOWED` 跳过第 1 步）真正调用 `callAsync` 触发的
 *    [io.agentscope.core.tool.ToolSuspendException] 挂起——同一条 `Custom(subagent.tool_result)`
 *    信号，两条路径共用一套促升代码。
 *
 * 覆盖两段真实链路：
 * 1. 用桩模型服务跑通真实的 [AguiRequestProcessor] + coordinator(`HarnessAgent`) + `client` 子 Agent
 *    （并复刻生产 `FrontendToolRegistrar.register()`，在 `configure()` 之前把 frontend tools 注册成
 *    [ExternalLocalTool]，否则 client 子 Agent 会把 `set_download_path` 当成"未注册工具"而不是真正
 *    走到权限自检/挂起路径）。coordinator 收到用户请求后调用 `agent_spawn` 委派给 `client`，`client`
 *    决定调用 `set_download_path`，收集 AguiRequestProcessor 产出的原始 `Flux<AguiEvent>`。
 * 2. 用真实的 [AguiInterruptTracker] + [SubagentHitlPromoter]（与生产环境
 *    [com.tencent.bkrepo.agent.service.run.AgentRunEventPipeline.handleEvent] 完全相同的调用顺序）
 *    重放这份事件流，观察最终是否促升出 `RUN_FINISHED(interrupt)`。
 *
 * ## 已确认的根因 + 已落地的变通方案（见 [SubagentHitlPromoter] 类注释）
 *
 * `agent_spawn` 走的是同步 `call()` 路径：`AgentSpawnTool.execLocalSync` →
 * `DefaultAgentManager.invokeAgent` → `ReActAgent.call()` → `callInternal()`。`callInternal` 内部对
 * `buildAgentStream(...)` 做的是局部订阅，真正携带 `GenerateReason.TOOL_SUSPENDED` 的
 * `AgentResultEvent` 是直接调用该 Flux 自己局部捕获的 `sink`，完全不经过
 * `AgentEventEmitter.fromForwardingContext(ctx)`——因此它**永远不会**出现在转发给 coordinator 的事件
 * 流里。这是上游开源库 `io.agentscope:agentscope-harness`/`agentscope-core`
 * （`agentscope-ai/agentscope-java`）的框架行为，bk-repo 无法直接修改其字节码。
 *
 * 但 reasoning/acting 内部的细粒度事件（`ModelCallStart`/`ToolCall*`/`ToolResult*`，以及
 * `RequireUserConfirmEvent`）走的是另一条正确转发的路径：前者会被降级为
 * `Custom(subagent.tool_result, {type=TOOL_RESULT_END, state=RUNNING, ...})` 送达 coordinator——
 * `state=RUNNING` 是框架对"挂起"结果的唯一编码；后者会被降级为
 * `Custom(subagent.require_confirm, ...)`。[SubagentHitlPromoter] 因此直接监听这两个已存在、不依赖
 * `AgentResultEvent` 的信号来促升 `RUN_FINISHED(interrupt)`，完全在 bk-repo 自己的代码里解决，不必
 * 等待上游修复。
 */
@DisplayName("agent_spawn 委派 set_download_path 挂起后 RUN_FINISHED(interrupt) 端到端回归测试")
class AgentSpawnSetDownloadPathSuspensionEndToEndTest {

    private lateinit var server: HttpServer
    private val receivedBodies = CopyOnWriteArrayList<String>()
    private val callIndex = AtomicInteger(0)

    private val threadId = "thread-e2e-1"
    private val runId = "run-e2e-1"
    private val downloadPath = "/mnt/new-downloads"

    @Test
    fun `client子Agent的set_download_path被挂起后应促升为顶层RunFinished interrupt`(@TempDir workspace: Path) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respondFromStub(exchange) }
        server.start()
        try {
            val events = runCoordinatorAndCollectAguiEvents(workspace)

            assertTrue(receivedBodies.size >= 2) {
                "桩模型服务应至少收到 2 次请求（coordinator 的 agent_spawn 决策 + client 的 " +
                    "set_download_path 决策），实际收到 ${receivedBodies.size} 次"
            }

            // 根因仍然成立：转发给 coordinator 的事件流里，client 子 Agent 一侧永远不会出现携带
            // TOOL_SUSPENDED 的 AgentResultEvent —— 这是上游框架行为，本测试同时钉住这一点，
            // 避免未来升级 agentscope 版本后此断言静默失效。
            val forwardedAgentResultEvents = events.filterIsInstance<AguiEvent.Raw>()
                .filter { it.source()?.contains("client") == true }
                .filter { it.event()?.javaClass?.simpleName == "AgentResultEvent" }
            assertTrue(forwardedAgentResultEvents.isEmpty()) {
                "上游框架行为已变化：期望 client 子 Agent 的 AgentResultEvent(TOOL_SUSPENDED) 不会被转发到" +
                    " coordinator 的事件流，实际观测到 $forwardedAgentResultEvents" +
                    "（如果这条断言开始失败，说明可以走 buildFromToolSuspended 这条原生路径了，" +
                    "SubagentHitlPromoter 里的 tool_result workaround 可以考虑收敛）"
            }

            val replay = replayThroughRealHitlPipeline(events)

            assertNotNull(replay.promotedRunFinished) {
                "SubagentHitlPromoter 未能促升出 RUN_FINISHED(interrupt)，" +
                    "客户端将既不会弹出确认框也不会执行工具。" +
                    "\n原始事件序列：${events.map { it.describe() }}" +
                    "\n重放后事件序列：${replay.dispatched.map { it.describe() }}"
            }

            val outcome = replay.promotedRunFinished!!.outcome()
            assertTrue(outcome is AguiEvent.RunFinishedInterruptOutcome) {
                "促升出的 RunFinished 应携带 interrupt outcome，实际为 $outcome"
            }
            val interrupts = (outcome as AguiEvent.RunFinishedInterruptOutcome).interrupts()
            assertTrue(interrupts.isNotEmpty()) { "促升出的 interrupt 列表不应为空" }
            assertTrue(interrupts.any { it.metadata()?.get("toolName") == "set_download_path" }) {
                "促升出的 interrupt 应关联到 set_download_path 工具调用，实际 interrupts=$interrupts"
            }
            // 锁定 ExternalLocalTool 权限自检修复后的真实促升路径：应该是"执行前先确认"
            // （permission_confirm，来自 RequireUserConfirmEvent），而不是回退到旧 Bug 下"先静默放行、
            // 挂起后才提示"的 tool_call 路径——避免这条断言退化成只测 buildFromSuspendedToolResult。
            assertTrue(interrupts.any { it.reason() == "permission_confirm" }) {
                "促升出的 interrupt 应来自权限确认（permission_confirm），说明 ExternalLocalTool 的" +
                    "风险自检没有生效、又退化回了执行后才挂起的旧路径，实际 interrupts=$interrupts"
            }

            assertTrue(replay.dispatched.last() === replay.promotedRunFinished) {
                "生产环境 AgentRunEventPipeline 在促升出 interrupt 后会立即 lifecycleManager.finish(abortAgent=true)，" +
                    "不应再向客户端派发后续事件；这里最后一个派发事件应就是促升出的 RunFinished"
            }
        } finally {
            server.stop(0)
        }
    }

    /** 第一段：真实 AguiRequestProcessor + coordinator + client 子 Agent，跑出原始 AguiEvent 流。 */
    private fun runCoordinatorAndCollectAguiEvents(workspace: Path): List<AguiEvent> {
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

        // 与生产环境 AgentPermissionRulesConfiguration 完全一致：coordinator 自身拿到 set_download_path
        // 的 ASK 规则，但该规则不会传导给 client 子 Agent（PermissionContextState 层面的独立框架限制，
        // 见 SubagentPermissionInheritanceTest）。这里保留生产配置，不去刻意规避——本测试真正验证的是
        // ExternalLocalTool.checkPermissions 在 client 子 Agent 拿到的 trivial context 下仍能独立
        // 自检出 ASK，不依赖这张规则表。
        val coordinatorPermissionContext = AgentPermissionRulesConfiguration()
            .agentPermissionContext(runtimeProperties)
        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        // 复刻生产 FrontendToolRegistrar.register()：必须在 configure() 之前把 frontend tools 注册成
        // ExternalLocalTool，subagent 工厂在 configure() 材料化时才能拿到 set_download_path 的工具实例
        // (否则 client 子 Agent 调用它时会落到 Toolkit 的"未注册工具"兜底路径，而不是真正的挂起路径)。
        LocalToolDefinitions.allTools().forEach { definition ->
            toolkit.registerAgentTool(ExternalLocalTool(definition))
        }
        val coordinator = agentHarnessConfigurer.configure(
            properties = runtimeProperties,
            memory = memoryProperties,
            model = AgentModelConfig().agentChatModel(llmProperties),
            stateStore = InMemoryAgentStateStore(),
            toolkit = toolkit,
            permissionContext = coordinatorPermissionContext,
        )

        val registry = AguiAgentRegistry()
        registry.register(runtimeProperties.name, coordinator)
        val agentResolver = HarnessAgentResolver(registry, runtimeProperties)
        val adapterConfig = AguiAdapterConfig.builder()
            .defaultAgentId(runtimeProperties.name)
            .runTimeout(runtimeProperties.sseTimeout)
            .enableReasoning(false)
            .emitTokenUsage(false)
            .emitToolCallArgs(true)
            // 与生产环境 AguiAgentConfiguration.aguiAdapterConfig 一致：保持默认 false，
            // 子 Agent 事件走 SubagentEventConverter 降级为 Custom(subagent.*) / Raw。
            .toolMergeMode(ToolMergeMode.AGENT_ONLY)
            .build()
        val processor = AguiRequestProcessor.builder()
            .agentResolver(agentResolver)
            .config(adapterConfig)
            .build()

        val input = RunAgentInput.builder()
            .threadId(threadId)
            .runId(runId)
            .messages(listOf(AguiMessage.userMessage("msg-user-1", "帮我把下载目录改到 $downloadPath")))
            .build()
        val runtimeContext = RuntimeContext.builder()
            .userId("user-e2e-1")
            .sessionId(threadId)
            .build()

        val result = processor.process(input, null, null, runtimeContext)
        return result.events()
            .collectList()
            .block(Duration.ofSeconds(60))
            .orEmpty()
    }

    /** 第二段：完全复刻 AgentRunEventPipeline.handleEvent 的调用顺序与提前终止逻辑。 */
    private fun replayThroughRealHitlPipeline(events: List<AguiEvent>): ReplayResult {
        val frontendToolCatalog = FrontendToolCatalog()
        val interruptNormalizer = AguiInterruptNormalizer(frontendToolCatalog)
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
        val interruptTracker = AguiInterruptTracker(
            interruptNormalizer,
            frontendToolCatalog,
            runtimeProperties,
            ObjectMapper(),
        )
        val promoter = SubagentHitlPromoter(frontendToolCatalog)
        val interruptState = AguiInterruptTracker.State()
        val hitlState = SubagentHitlPromoter.State()

        val dispatched = mutableListOf<AguiEvent>()
        var promoted: AguiEvent.RunFinished? = null
        for (event in events) {
            interruptTracker.onEvent(event, interruptState)
            promoter.onEvent(event, interruptState, hitlState)

            val outbound = interruptTracker.enrichEvent(event, interruptState)
            dispatched.add(outbound)

            val promotedNow = promoter.buildRunFinishedIfNeeded(event, threadId, runId, interruptState, hitlState)
            if (promotedNow != null) {
                // AgentRunEventPipeline.promoteSubagentHitlIfNeeded 把促升出的 RunFinished 作为
                // 额外一次 dispatchEvent 调用，紧跟在触发它的原始事件之后派发。
                val enrichedPromoted =
                    interruptTracker.enrichEvent(promotedNow, interruptState) as AguiEvent.RunFinished
                dispatched.add(enrichedPromoted)
                promoted = enrichedPromoted
                // 促升成功后 lifecycleManager.finish(abortAgent = true)，后续事件不会再派发给客户端。
                break
            }
        }
        return ReplayResult(dispatched, promoted)
    }

    private data class ReplayResult(
        val dispatched: List<AguiEvent>,
        val promotedRunFinished: AguiEvent.RunFinished?,
    )

    private fun AguiEvent.describe(): String = when (this) {
        is AguiEvent.Custom -> "Custom(name=${name()}, value=${value()})"
        is AguiEvent.Raw -> "Raw(source=${source()}, event=${event()})"
        is AguiEvent.RunFinished -> "RunFinished(outcome=${outcome()})"
        else -> this::class.simpleName ?: this.toString()
    }

    private fun respondFromStub(exchange: HttpExchange) {
        exchange.use {
            receivedBodies.add(it.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            it.responseHeaders.add("Content-Type", "text/event-stream")
            it.sendResponseHeaders(200, 0)
            val idx = callIndex.incrementAndGet()
            val body = when (idx) {
                1 -> toolCallChunks(
                    callId = "call_spawn_1",
                    toolName = "agent_spawn",
                    argumentsJson = """{"agent_id":"client","task":"帮用户把全局下载目录改到 $downloadPath",""" +
                        """"timeout_seconds":30}""",
                )
                2 -> toolCallChunks(
                    callId = "call_setpath_1",
                    toolName = "set_download_path",
                    argumentsJson = """{"path":"$downloadPath"}""",
                )
                else -> textReplyChunks("(coordinator 收到 agent_spawn 结果后的收尾发言，第 $idx 次模型调用)")
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
        text.map { char -> deltaChunk("""{"content":"${escapeJsonString(char.toString())}"}""") }
            .plus(deltaChunk("{}", finishReason = "stop"))
            .plus(doneChunk())
            .joinToString("")

    private fun deltaChunk(delta: String, finishReason: String? = null): String {
        val finish = finishReason?.let { "\"$it\"" } ?: "null"
        return "data: {\"id\":\"chatcmpl-e2e\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":$delta," +
            "\"finish_reason\":$finish}]}\n\n"
    }

    private fun doneChunk(): String = "data: [DONE]\n\n"

    private fun escapeJsonString(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
