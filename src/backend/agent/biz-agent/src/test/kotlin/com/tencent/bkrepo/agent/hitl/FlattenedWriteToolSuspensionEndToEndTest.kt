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
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_PERMISSION_CONFIRM_RESULTS
import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.service.run.AgentRunOutcomeTracker
import com.tencent.bkrepo.agent.session.HarnessAgentResolver
import com.tencent.bkrepo.agent.session.InMemoryAgentPendingInterruptStore
import com.tencent.bkrepo.agent.session.InMemoryAgentResumeIdempotencyStore
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.RegisteredDomainTools
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import com.tencent.bkrepo.agent.tool.frontend.RegisteredFrontendTools
import com.tencent.bkrepo.agent.tool.local.ExternalLocalTool
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import com.tencent.bkrepo.agent.usage.NoopAgentRunRecordService
import com.tencent.bkrepo.agent.usage.UsageTrackingMiddleware
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.adapter.AguiAdapterConfig
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.AguiMessage
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.model.ToolMergeMode
import io.agentscope.core.agui.processor.AguiRequestProcessor
import io.agentscope.core.agui.registry.AguiAgentRegistry
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import io.agentscope.harness.agent.HarnessAgent
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
import java.util.concurrent.atomic.AtomicReference

/**
 * 端到端覆盖"拍平方案"（client 本地写工具直接挂在协调者自己的 live toolkit 上，不再经由独立 `client`
 * 子 Agent）下 `set_download_path` 的两轮挂起/恢复闭环，取代已删除的
 * `AgentSpawnSetDownloadPathSuspensionEndToEndTest` / `SubagentConfirmResumeEndToEndTest`
 * （两者验证的是 `agent_spawn` 委派 + `SubagentHitlPromoter`/`SubagentConfirmResumeExecutor` 旁路，
 * 该旁路已随拍平方案一起删除）。
 *
 * 覆盖：
 * 1. 第一轮：协调者的模型直接决定调用 `set_download_path`（不再有 `agent_spawn` 这一层）。
 *    [ExternalLocalTool.checkPermissions] 自检为 ASK，框架原生触发 `PERMISSION_ASKING`，
 *    产出 `RUN_FINISHED(interrupt, reason=permission_confirm)`——这与今天 domain 工具的 ASK 完全
 *    同构，不需要任何 bk-repo 自定义促升代码。用与生产环境
 *    [com.tencent.bkrepo.agent.service.run.AgentRunEventPipeline.dispatchEvent] 完全一致的调用顺序
 *    （[AguiInterruptTracker] + [AgentRunOutcomeTracker]）重放，把 pending interrupt 真正持久化到
 *    [AgentInterruptStateRepository]。
 * 2. 第二轮：构造真实的 resume `RunAgentInput`（`approved=true`），经真实的
 *    [AguiPermissionResumeAdapter] 从仓库读回 confirmResults，注入
 *    `RUNTIME_CONTEXT_PERMISSION_CONFIRM_RESULTS`（与生产环境
 *    [com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator.buildRunScope] 完全相同的路径），
 *    再次调用同一个 [AguiRequestProcessor] + 同一个协调者 `HarnessAgent`（同一个 threadId/sessionId，
 *    状态由 [InMemoryAgentStateStore] 按 (userId, sessionId) 直接找回，不需要任何确定性哈希/label 计算）。
 *    验证确认后 `set_download_path` 被真正放行调用、抛出 `ToolSuspendException` 挂起，产出第二个
 *    `RUN_FINISHED(interrupt, reason=tool_call)`，而不是 Bug 3 描述的"协调者收到空消息、自我介绍、
 *    会话不终止"。
 */
@DisplayName("拍平方案：协调者直接调用 set_download_path 两轮挂起/恢复端到端回归测试")
class FlattenedWriteToolSuspensionEndToEndTest {

    private lateinit var server: HttpServer
    private val receivedBodies = CopyOnWriteArrayList<String>()
    private val callIndex = AtomicInteger(0)

    private val threadId = "thread-flatten-e2e-1"
    private val userId = "user-flatten-e2e-1"
    private val round1RunId = "run-flatten-e2e-1"
    private val round2RunId = "run-flatten-e2e-2"
    private val downloadPath = "/mnt/new-downloads"

    private val frontendToolCatalog = FrontendToolCatalog()
    private val interruptStateRepository = DefaultAgentInterruptStateRepository(
        pendingInterruptStore = InMemoryAgentPendingInterruptStore(),
        resumeIdempotencyStore = InMemoryAgentResumeIdempotencyStore(),
    )

    @Test
    fun `协调者直接挂起set_download_path确认后应真正放行执行而不是重新自我介绍`(@TempDir workspace: Path) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respondFromStub(exchange) }
        server.start()
        try {
            val runtimeProperties = AgentRuntimePropertiesResolver.resolve(
                AgentRuntimeProperties(workspace = workspace.toString(), maxIters = 6),
            )
            val coordinator = buildCoordinator(runtimeProperties)
            val registry = AguiAgentRegistry()
            registry.register(runtimeProperties.name, coordinator)
            val agentResolver = HarnessAgentResolver(registry, runtimeProperties)
            val adapterConfig = AguiAdapterConfig.builder()
                .defaultAgentId(runtimeProperties.name)
                .runTimeout(runtimeProperties.sseTimeout)
                .enableReasoning(false)
                .emitTokenUsage(false)
                .emitToolCallArgs(true)
                .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                .build()
            val processor = AguiRequestProcessor.builder()
                .agentResolver(agentResolver)
                .config(adapterConfig)
                .build()

            // ---------- 第一轮：协调者直接调用 set_download_path，原生 ASK 挂起 ----------
            val round1Events = runRoundAndCollectEvents(
                processor = processor,
                runId = round1RunId,
                messages = listOf(AguiMessage.userMessage("msg-user-1", "帮我把下载目录改到 $downloadPath")),
                resume = emptyList(),
            )
            val round1Replay = replayThroughRealPipelineAndPersist(round1Events, round1RunId)

            val round1RunFinished = round1Replay.dispatched.filterIsInstance<AguiEvent.RunFinished>().lastOrNull()
            assertNotNull(round1RunFinished) {
                "第一轮应产出 RUN_FINISHED(interrupt)，实际事件序列：${round1Events.map { it.describe() }}"
            }
            val round1Outcome = round1RunFinished!!.outcome()
            assertTrue(round1Outcome is AguiEvent.RunFinishedInterruptOutcome) {
                "协调者直接调用写工具时应原生触发 PERMISSION_ASKING 挂起，而不是直接把结果当成普通回复，" +
                    "实际 outcome=$round1Outcome，events=${round1Events.map { it.describe() }}"
            }
            val round1Interrupts = (round1Outcome as AguiEvent.RunFinishedInterruptOutcome).interrupts()
            assertTrue(round1Interrupts.any { it.metadata()?.get("toolName") == "set_download_path" }) {
                "第一轮挂起的 interrupt 应关联到 set_download_path，实际 interrupts=$round1Interrupts"
            }
            assertTrue(round1Interrupts.any { it.reason() == "permission_confirm" }) {
                "第一轮应是权限确认形态（reason=permission_confirm），实际 interrupts=$round1Interrupts"
            }
            assertTrue(round1Interrupts.all { !it.message().isNullOrBlank() }) {
                "@ag-ui/client 的 Zod schema 要求 interrupt.message 为非空字符串（回归：拍平方案上线后" +
                    "真实客户端报过 'outcome.interrupts[0].message: Expected string, received null'），" +
                    "实际 interrupts=$round1Interrupts"
            }

            val persistedSession = interruptStateRepository.getPendingInterrupt(threadId)
            val persistedSnapshot = persistedSession?.interrupts?.singleOrNull()
            assertNotNull(persistedSnapshot) {
                "AgentRunOutcomeTracker 应已把第一轮的 pending interrupt 真正落到仓库，实际=${persistedSession?.interrupts}"
            }
            assertTrue(!persistedSnapshot!!.message.isNullOrBlank()) {
                "持久化快照的 message 同样不能为空——reconnect 时 AgentRunStreamOrchestrator.pendingInterrupts " +
                    "会把它原样重放给客户端，实际 snapshot=$persistedSnapshot"
            }

            // ---------- 第二轮：真实 resume -> Adapter 解析 confirmResults -> 同一协调者/同一 session 续跑 ----------
            val resumeInput = RunAgentInput.builder()
                .threadId(threadId)
                .runId(round2RunId)
                .resume(
                    listOf(
                        AguiResume(
                            persistedSnapshot!!.id,
                            AguiResume.STATUS_RESOLVED,
                            mapOf("approved" to true),
                        ),
                    ),
                )
                .build()
            val adapted = AguiPermissionResumeAdapter(interruptStateRepository).adapt(resumeInput)
            assertTrue(adapted.confirmResults.isNotEmpty()) {
                "拍平后写工具的 ASKING 应始终挂在协调者自己身上，Adapter 应解析出非空的 confirmResults，" +
                    "实际=${adapted.confirmResults}"
            }

            val round2Events = runRoundAndCollectEvents(
                processor = processor,
                runId = round2RunId,
                messages = adapted.input.messages,
                resume = adapted.input.resume,
                confirmResults = adapted.confirmResults,
            )
            val round2Replay = replayThroughRealPipelineAndPersist(round2Events, round2RunId)
            val round2RunFinished = round2Replay.dispatched.filterIsInstance<AguiEvent.RunFinished>().singleOrNull()
            assertNotNull(round2RunFinished) {
                "第二轮应产出唯一的 RunFinished 事件（不应像 Bug 3 那样让协调者陷入空消息重新自我介绍、会话不终止），" +
                    "实际 round2Events=${round2Events.map { it.describe() }}"
            }
            val round2Outcome = round2RunFinished!!.outcome()
            assertTrue(round2Outcome is AguiEvent.RunFinishedInterruptOutcome) {
                "确认同意后 set_download_path 应真正被放行调用、再次挂起等待客户端本地执行，实际 outcome=$round2Outcome"
            }
            val round2Interrupts = (round2Outcome as AguiEvent.RunFinishedInterruptOutcome).interrupts()
            assertTrue(round2Interrupts.any { it.metadata()?.get("toolName") == "set_download_path" }) {
                "第二轮挂起的 interrupt 应关联到 set_download_path，实际 interrupts=$round2Interrupts"
            }
            assertTrue(round2Interrupts.none { it.reason() == "permission_confirm" }) {
                "不应重新弹一次确认框——否则说明确认没有真正解除 ASKING，实际 interrupts=$round2Interrupts"
            }
            assertTrue(round2Interrupts.any { it.reason() == "tool_call" }) {
                "第二轮应是「请客户端本地执行」形状的 interrupt（reason=tool_call），实际 interrupts=$round2Interrupts"
            }
            assertTrue(round2Interrupts.all { !it.message().isNullOrBlank() }) {
                "第二轮 interrupt 的 message 同样不能为空，实际 interrupts=$round2Interrupts"
            }
        } finally {
            server.stop(0)
        }
    }

    private fun buildCoordinator(runtimeProperties: EffectiveAgentRuntimeProperties): HarnessAgent {
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
            usageTrackingMiddleware = UsageTrackingMiddleware(NoopAgentRunRecordService()),
        )
        val coordinatorPermissionContext = AgentPermissionRulesConfiguration()
            .agentPermissionContext(runtimeProperties)
        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        // 复刻拍平后的生产装配：frontend tools 作为 ExternalLocalTool 直接留在协调者自己的 live toolkit 上
        // （见 FrontendToolRegistrar.register() / HarnessAgentConfiguration），不再剥离给 client 子 Agent。
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

    private fun runRoundAndCollectEvents(
        processor: AguiRequestProcessor,
        runId: String,
        messages: List<AguiMessage>,
        resume: List<AguiResume>,
        confirmResults: List<ConfirmResult> = emptyList(),
    ): List<AguiEvent> {
        val input = RunAgentInput.builder()
            .threadId(threadId)
            .runId(runId)
            .messages(messages)
            .resume(resume)
            .build()
        var runtimeContext = RuntimeContext.builder()
            .userId(userId)
            .sessionId(threadId)
            .build()
        if (confirmResults.isNotEmpty()) {
            runtimeContext = RuntimeContext.builder(runtimeContext)
                .put(RUNTIME_CONTEXT_PERMISSION_CONFIRM_RESULTS, confirmResults)
                .build()
        }
        val result = processor.process(input, null, null, runtimeContext)
        return result.events()
            .collectList()
            .block(Duration.ofSeconds(60))
            .orEmpty()
    }

    /**
     * 完全复刻 [com.tencent.bkrepo.agent.service.run.AgentRunEventPipeline.dispatchEvent] 的调用顺序
     * （含 [AgentRunOutcomeTracker]），因此 pending interrupt 会像生产环境一样真正落到
     * [interruptStateRepository]，供后续 resume 通过真实的 [AguiPermissionResumeAdapter] 读取，
     * 而不是在测试里手工构造快照。拍平后不再需要 `SubagentHitlPromoter` 促升——协调者的 ASK/
     * TOOL_SUSPENDED 已经是顶层原生 `RunFinished(interrupt)`，`AguiInterruptTracker` 直接就能识别。
     */
    private fun replayThroughRealPipelineAndPersist(events: List<AguiEvent>, runId: String): ReplayResult {
        val interruptNormalizer = AguiInterruptNormalizer(frontendToolCatalog)
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
        val interruptTracker = AguiInterruptTracker(
            interruptNormalizer,
            runtimeProperties,
            ObjectMapper(),
        )
        val outcomeTracker = AgentRunOutcomeTracker(interruptTracker, interruptStateRepository)
        val interruptState = AguiInterruptTracker.State()
        val terminalStatus = AtomicReference(AgentRunStatus.RUNNING)

        val dispatched = mutableListOf<AguiEvent>()
        for (event in events) {
            interruptTracker.onEvent(event, interruptState)
            val outbound = interruptTracker.enrichEvent(event, interruptState)
            outcomeTracker.applyTerminalEvent(outbound, terminalStatus)
            outcomeTracker.capturePendingInterruptIfNeeded(
                threadId = threadId,
                runId = runId,
                event = outbound,
                terminalStatus = terminalStatus.get(),
                interruptState = interruptState,
            )
            dispatched.add(outbound)
        }
        return ReplayResult(dispatched)
    }

    private data class ReplayResult(val dispatched: List<AguiEvent>)

    private fun AguiEvent.describe(): String = when (this) {
        is AguiEvent.Custom -> "Custom(name=${name()}, value=${value()})"
        is AguiEvent.Raw -> "Raw(source=${source()}, event=${event()})"
        is AguiEvent.RunFinished -> "RunFinished(outcome=${outcome()})"
        else -> this::class.simpleName ?: this.toString()
    }

    /**
     * 框架在每次 `agent.call()` 结束后会异步触发一次"记忆提炼"模型调用（system 提示词含
     * `memory extraction assistant`），与本测试要精确控制的推理轮次调用共用同一个桩服务器/端口，
     * 且是并发触发、到达顺序不确定——如果照常让它消耗 [callIndex]，会把后续真正推理调用的序号错位。
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
                when (val idx = callIndex.incrementAndGet()) {
                    1 -> toolCallChunks(
                        callId = "call_setpath_1",
                        toolName = "set_download_path",
                        argumentsJson = """{"path":"$downloadPath"}""",
                    )
                    // 拍平方案下，第二轮 resume 是纯状态续跑（ASKING -> callAsync），理论上不需要
                    // 再调模型；这里保留一个安全兜底，避免上游框架行为变化时测试因收不到响应而超时挂死。
                    else -> textReplyChunks("(第 $idx 次模型调用，超出预期的兜底收尾发言)")
                }
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
        return "data: {\"id\":\"chatcmpl-flatten-e2e\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":$delta," +
            "\"finish_reason\":$finish}]}\n\n"
    }

    private fun doneChunk(): String = "data: [DONE]\n\n"

    private fun escapeJsonString(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
