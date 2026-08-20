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
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.adapter.AguiAdapterConfig
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.agui.model.AguiMessage
import io.agentscope.core.agui.model.AguiResume
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.model.ToolMergeMode
import io.agentscope.core.agui.processor.AguiRequestProcessor
import io.agentscope.core.agui.registry.AguiAgentRegistry
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import io.agentscope.harness.agent.HarnessAgent
import org.junit.jupiter.api.Assertions.assertEquals
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
 * 端到端覆盖「子代理确认恢复旁路修复（Bug 3）」的完整两轮闭环，而不仅仅是
 * [AgentSpawnSetDownloadPathSuspensionEndToEndTest] 覆盖的第一轮（挂起促升）或
 * [SubagentConfirmResumeExecutorTest] 覆盖的执行器单元行为（人为手搭 ASKING 状态）：
 *
 * 1. 第一轮：真实 `AguiRequestProcessor` + coordinator + `client` 子 Agent 跑通
 *    `agent_spawn` -> `set_download_path` -> `RequireUserConfirmEvent`，用与生产环境
 *    [com.tencent.bkrepo.agent.service.run.AgentRunEventPipeline] 完全一致的调用顺序
 *    （[AguiInterruptTracker] + [SubagentHitlPromoter] + [AgentRunOutcomeTracker]）重放，
 *    促升出 `RUN_FINISHED(interrupt)` 并把 [com.tencent.bkrepo.agent.session.PendingInterruptSession]
 *    真正落到 [AgentInterruptStateRepository]（而不是手工构造快照）。
 * 2. 第二轮：构造一个真实的 resume `RunAgentInput`（携带 `approved=true`），经
 *    [AguiPermissionResumeAdapter] 从仓库里读回 pending interrupt、解析出
 *    `subagentResumeTargets`，再交给 [SubagentConfirmResumeExecutor] 直接续跑 `client` 子代理——
 *    验证最终确实产出「请客户端本地执行 set_download_path」的新一轮 `RUN_FINISHED(interrupt)`，
 *    而不是 Bug 3 描述的"协调者收到空消息、会话不终止"。
 */
@DisplayName("子代理确认恢复旁路修复端到端闭环：挂起促升->持久化->resume->再次挂起给客户端执行")
class SubagentConfirmResumeEndToEndTest {

    private lateinit var server: HttpServer
    private val receivedBodies = CopyOnWriteArrayList<String>()
    private val callIndex = AtomicInteger(0)

    private val threadId = "thread-e2e-resume-1"
    private val userId = "user-e2e-resume-1"
    private val round1RunId = "run-e2e-resume-1"
    private val round2RunId = "run-e2e-resume-2"
    private val downloadPath = "/mnt/new-downloads"

    private val frontendToolCatalog = FrontendToolCatalog()
    private val interruptStateRepository = com.tencent.bkrepo.agent.hitl.DefaultAgentInterruptStateRepository(
        pendingInterruptStore = InMemoryAgentPendingInterruptStore(),
        resumeIdempotencyStore = InMemoryAgentResumeIdempotencyStore(),
    )

    @Test
    fun `client子Agent确认恢复后应绕开协调者直接续跑并再次挂起给客户端执行`(@TempDir workspace: Path) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respondFromStub(exchange) }
        server.start()
        try {
            val coordinator = buildCoordinator(workspace)

            // ---------- 第一轮：agent_spawn 挂起促升 + 真正持久化到仓库 ----------
            val round1Events = runCoordinatorAndCollectAguiEvents(coordinator)
            val round1Replay = replayThroughRealHitlPipelineAndPersist(round1Events)

            assertNotNull(round1Replay.promotedRunFinished) {
                "第一轮应促升出 RUN_FINISHED(interrupt)，否则第二轮无从谈起。" +
                    "\n原始事件序列：${round1Events.map { it.describe() }}"
            }
            val round1Interrupts =
                (round1Replay.promotedRunFinished!!.outcome() as AguiEvent.RunFinishedInterruptOutcome).interrupts()
            val permissionInterrupt = round1Interrupts.singleOrNull { it.reason() == "permission_confirm" }
            assertNotNull(permissionInterrupt) {
                "第一轮应促升出唯一的 permission_confirm interrupt，实际 interrupts=$round1Interrupts"
            }

            val persistedSession = interruptStateRepository.getPendingInterrupt(threadId)
            assertNotNull(persistedSession) {
                "AgentRunOutcomeTracker 应已把第一轮的 pending interrupt 真正落到仓库，供第二轮 resume 读取"
            }
            val persistedSnapshot = persistedSession!!.interrupts.singleOrNull()
            assertNotNull(persistedSnapshot) { "持久化的 pending interrupt 快照应唯一，实际=${persistedSession.interrupts}" }
            assertEquals("set_download_path", persistedSnapshot!!.toolName) {
                "持久化快照应关联到 set_download_path，实际=$persistedSnapshot"
            }
            assertEquals(permissionInterrupt!!.id(), persistedSnapshot.id)

            // ---------- 第二轮：真实 resume 请求 -> Adapter 解析目标 -> Executor 直接续跑子代理 ----------
            val resumeInput = RunAgentInput.builder()
                .threadId(threadId)
                .runId(round2RunId)
                .resume(
                    listOf(
                        AguiResume(
                            persistedSnapshot.id,
                            AguiResume.STATUS_RESOLVED,
                            mapOf("approved" to true),
                        ),
                    ),
                )
                .build()

            val adapter = AguiPermissionResumeAdapter(interruptStateRepository)
            val adapted = adapter.adapt(resumeInput)

            assertTrue(adapted.confirmResults.isEmpty()) {
                "子代理级确认不应落到协调者自身的 confirmResults 路径，实际=${adapted.confirmResults}"
            }
            val target = adapted.subagentResumeTargets.singleOrNull()
            assertNotNull(target) {
                "Adapter 应从持久化的 permission_confirm 快照里解析出唯一的子代理续跑目标，" +
                    "实际 subagentResumeTargets=${adapted.subagentResumeTargets}"
            }
            assertEquals("client", target!!.agentId)
            assertTrue(target.confirmResult.isConfirmed)
            assertEquals("set_download_path", target.confirmResult.toolCall.name)

            val executor = SubagentConfirmResumeExecutor(coordinator, SubagentHitlPromoter(frontendToolCatalog))
            val round2Events = executor.resume(threadId, round2RunId, userId, target)
                .collectList()
                .block(Duration.ofSeconds(30))
                .orEmpty()

            val round2RunFinished = round2Events.filterIsInstance<AguiEvent.RunFinished>().singleOrNull()
            assertNotNull(round2RunFinished) {
                "第二轮应产出唯一的 RunFinished 事件（不应像 Bug 3 那样让协调者陷入空消息、会话不终止），" +
                    "实际 round2Events=${round2Events.map { it.describe() }}"
            }
            val round2Outcome = round2RunFinished!!.outcome()
            assertTrue(round2Outcome is AguiEvent.RunFinishedInterruptOutcome) {
                "确认同意后 set_download_path 应真正被放行执行、再次挂起等待客户端本地执行，实际 outcome=$round2Outcome"
            }
            val round2Interrupts = (round2Outcome as AguiEvent.RunFinishedInterruptOutcome).interrupts()
            assertTrue(round2Interrupts.any { it.metadata()?.get("toolName") == "set_download_path" }) {
                "第二轮促升出的 interrupt 应关联到 set_download_path，实际 interrupts=$round2Interrupts"
            }
            assertTrue(round2Interrupts.none { it.reason() == "permission_confirm" }) {
                "不应重新弹一次确认框——否则说明确认没有真正解除 ASKING，Bug 3 没有修复，实际 interrupts=$round2Interrupts"
            }
            assertTrue(round2Interrupts.any { it.reason() == "tool_call" }) {
                "第二轮应是「请客户端本地执行」形状的 interrupt（reason=tool_call），实际 interrupts=$round2Interrupts"
            }
        } finally {
            server.stop(0)
        }
    }

    /** 第一段：真实 AguiRequestProcessor + coordinator + client 子 Agent，跑出原始 AguiEvent 流。 */
    private fun runCoordinatorAndCollectAguiEvents(coordinator: HarnessAgent): List<AguiEvent> {
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
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

        val input = RunAgentInput.builder()
            .threadId(threadId)
            .runId(round1RunId)
            .messages(listOf(AguiMessage.userMessage("msg-user-1", "帮我把下载目录改到 $downloadPath")))
            .build()
        val runtimeContext = RuntimeContext.builder()
            .userId(userId)
            .sessionId(threadId)
            .build()

        val result = processor.process(input, null, null, runtimeContext)
        return result.events()
            .collectList()
            .block(Duration.ofSeconds(60))
            .orEmpty()
    }

    /**
     * 第二段：完全复刻 [com.tencent.bkrepo.agent.service.run.AgentRunEventPipeline.handleEvent] /
     * `dispatchEvent` 的调用顺序（含 [AgentRunOutcomeTracker]），因此促升出的 pending interrupt 会像
     * 生产环境一样真正落到 [interruptStateRepository]，供第二轮 resume 通过真实的
     * [AguiPermissionResumeAdapter] 读取，而不是在测试里手工构造快照。
     */
    private fun replayThroughRealHitlPipelineAndPersist(events: List<AguiEvent>): ReplayResult {
        val interruptNormalizer = AguiInterruptNormalizer(frontendToolCatalog)
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
        val interruptTracker = AguiInterruptTracker(
            interruptNormalizer,
            frontendToolCatalog,
            runtimeProperties,
            ObjectMapper(),
        )
        val promoter = SubagentHitlPromoter(frontendToolCatalog)
        val outcomeTracker = AgentRunOutcomeTracker(interruptTracker, interruptStateRepository)
        val interruptState = AguiInterruptTracker.State()
        val hitlState = SubagentHitlPromoter.State()
        val terminalStatus = AtomicReference(AgentRunStatus.RUNNING)

        val dispatched = mutableListOf<AguiEvent>()
        var promoted: AguiEvent.RunFinished? = null
        for (event in events) {
            interruptTracker.onEvent(event, interruptState)
            promoter.onEvent(event, interruptState, hitlState)
            dispatched.add(dispatchAndPersist(event, interruptTracker, outcomeTracker, interruptState, terminalStatus))

            val promotedNow = promoter.buildRunFinishedIfNeeded(event, threadId, round1RunId, interruptState, hitlState)
            if (promotedNow != null) {
                // AgentRunEventPipeline.promoteSubagentHitlIfNeeded 把促升出的 RunFinished 作为
                // 额外一次 dispatchEvent 调用，紧跟在触发它的原始事件之后派发。
                val enrichedPromoted = dispatchAndPersist(
                    promotedNow,
                    interruptTracker,
                    outcomeTracker,
                    interruptState,
                    terminalStatus,
                ) as AguiEvent.RunFinished
                dispatched.add(enrichedPromoted)
                promoted = enrichedPromoted
                // 促升成功后 lifecycleManager.finish(abortAgent = true)，后续事件不会再派发给客户端。
                break
            }
        }
        return ReplayResult(dispatched, promoted)
    }

    private fun dispatchAndPersist(
        event: AguiEvent,
        interruptTracker: AguiInterruptTracker,
        outcomeTracker: AgentRunOutcomeTracker,
        interruptState: AguiInterruptTracker.State,
        terminalStatus: AtomicReference<AgentRunStatus>,
    ): AguiEvent {
        val outbound = interruptTracker.enrichEvent(event, interruptState)
        outcomeTracker.applyTerminalEvent(outbound, terminalStatus)
        outcomeTracker.capturePendingInterruptIfNeeded(
            threadId = threadId,
            runId = round1RunId,
            event = outbound,
            terminalStatus = terminalStatus.get(),
            interruptState = interruptState,
        )
        return outbound
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
        return "data: {\"id\":\"chatcmpl-e2e-resume\",\"object\":\"chat.completion.chunk\",\"created\":0," +
            "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":$delta," +
            "\"finish_reason\":$finish}]}\n\n"
    }

    private fun doneChunk(): String = "data: [DONE]\n\n"

    private fun escapeJsonString(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
