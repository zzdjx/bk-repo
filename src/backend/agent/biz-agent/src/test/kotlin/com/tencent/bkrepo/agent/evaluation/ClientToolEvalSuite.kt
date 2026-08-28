/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.evaluation

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.agent.AgentCatalog
import com.tencent.bkrepo.agent.agent.AgentFactory
import com.tencent.bkrepo.agent.agent.discovery.ArtifactDiscoveryAgentDefinition
import com.tencent.bkrepo.agent.agent.transfer.TransferDiagnosticsAgentDefinition
import com.tencent.bkrepo.agent.audit.RecordingAgentToolCallRecordService
import com.tencent.bkrepo.agent.audit.ToolAuditMiddleware
import com.tencent.bkrepo.agent.config.AgentHarnessConfigurer
import com.tencent.bkrepo.agent.config.AgentMemoryConfig
import com.tencent.bkrepo.agent.config.AgentModelConfig
import com.tencent.bkrepo.agent.config.properties.AgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.AgentLlmPropertiesResolver
import com.tencent.bkrepo.agent.config.properties.AgentMemoryProperties
import com.tencent.bkrepo.agent.config.properties.AgentMemoryPropertiesResolver
import com.tencent.bkrepo.agent.config.properties.AgentRuntimeProperties
import com.tencent.bkrepo.agent.config.properties.AgentRuntimePropertiesResolver
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.hitl.PermissionConfirmResumeMiddleware
import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import com.tencent.bkrepo.agent.subagent.DelegationBudgetMiddleware
import com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard
import com.tencent.bkrepo.agent.subagent.NoopTaskRepositoryProvider
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.RegisteredDomainTools
import com.tencent.bkrepo.agent.tool.frontend.RegisteredFrontendTools
import com.tencent.bkrepo.agent.tool.local.ExternalLocalTool
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import com.tencent.bkrepo.agent.usage.NoopAgentRunRecordService
import com.tencent.bkrepo.agent.usage.UsageTrackingMiddleware
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.event.TextBlockDeltaEvent
import io.agentscope.core.message.UserMessage
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 阶段 11 离线评估集：BKArtifacts 下载客户端场景的工具选择/越权防护评估。
 *
 * ## 为什么默认跳过，需要显式配置环境变量才会运行
 *
 * 这个套件调用**真实模型网关**（不是像 [com.tencent.bkrepo.agent.HarnessAgentSmokeTest] 那样的桩服务器），
 * 目的是真正评估"模型会不会做出正确决策"，而不是"框架管线接不接得住脚本化的固定响应"——桩模型模式测的是
 * 后者，测不出前者。真实模型调用意味着需要网络、真实凭据、有成本，且同一个用例偶尔会因为模型输出的
 * 非确定性而不稳定，因此**不适合作为每次 PR 都必须跑通过的 CI 门禁**（公开的 GitHub Actions CI 也没有配置
 * 内部模型网关凭据、沙箱本身对外网访问受限）。
 *
 * 用 [EnabledIfEnvironmentVariable] 让这个类在没有配置真实模型网关时被 JUnit 直接跳过（`SKIPPED`，
 * 不是 `FAILED`），因此接入常规 `./gradlew test`/CI 完全零风险——不设置环境变量时行为与这个类不存在一样。
 * 需要真正跑一遍评估集时（例如改动了系统提示词、切换了模型、调整了工具目录），在有网络访问内部模型网关的
 * 环境里设置：
 *
 * ```
 * AGENT_EVAL_LLM_BASE_URL=https://xxx/v1
 * AGENT_EVAL_LLM_MODEL_NAME=xxx
 * # 二选一鉴权方式：
 * AGENT_EVAL_LLM_API_KEY=xxx
 * # 或
 * AGENT_EVAL_LLM_BK_APP_CODE=xxx
 * AGENT_EVAL_LLM_BK_APP_SECRET=xxx
 * ```
 *
 * 然后执行 `./gradlew :agent:biz-agent:test --tests "com.tencent.bkrepo.agent.evaluation.*"`，
 * 每个用例会作为一个独立的 [DynamicTest] 出现在标准 JUnit 测试报告里（`build/reports/tests/test/`），
 * 不需要额外的报告生成器；失败信息里会附带实际调用的工具与最终回复文本，方便判断是模型真的退化了还是
 * 断言本身对措辞过于敏感。这是一个**人工在改动前后各跑一遍、对比结果**的流程性门禁，不是自动化门禁。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_EVAL_LLM_BASE_URL", matches = ".+")
@DisplayName("阶段11离线评估集：BKArtifacts客户端工具选择与越权防护（需要真实模型网关，默认跳过）")
class ClientToolEvalSuite {

    @TestFactory
    fun `客户端工具选择与越权防护评估用例`(@TempDir workspace: Path): List<DynamicTest> {
        val llmProperties = resolveEvalLlmPropertiesFromEnv()
        logger.info(
            "阶段11评估集使用的模型: baseUrl={}, modelName={}, authMode={}",
            llmProperties.baseUrl,
            llmProperties.modelName,
            llmProperties.authMode,
        )
        return ClientToolEvalCases.ALL.map { case -> buildDynamicTest(case, llmProperties, workspace) }
    }

    private fun buildDynamicTest(
        case: EvalCase,
        llmProperties: EffectiveAgentLlmProperties,
        workspace: Path,
    ): DynamicTest = DynamicTest.dynamicTest("[${case.id}] ${case.description}") {
        val caseWorkspace = Files.createDirectories(workspace.resolve(case.id))
        val transcript = runCase(case, llmProperties, caseWorkspace)
        val failures = case.expectations.mapNotNull { it.check(transcript) }
        assertTrue(failures.isEmpty()) {
            buildString {
                appendLine("用例 '${case.id}' 未通过（${failures.size}/${case.expectations.size} 条断言失败）：")
                failures.forEach { appendLine("  - $it") }
                appendLine(
                    "实际调用工具序列：${transcript.calledTools.map { "${it.toolName}(${it.argsDigest})" }}",
                )
                appendLine("实际最终回复文本：${transcript.finalText}")
            }
        }
    }

    private fun runCase(case: EvalCase, llmProperties: EffectiveAgentLlmProperties, workspace: Path): EvalTranscript {
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(
            AgentRuntimeProperties(workspace = workspace.toString(), maxIters = EVAL_MAX_ITERS),
        )
        val recordingService = RecordingAgentToolCallRecordService()
        val coordinator = buildCoordinator(runtimeProperties, llmProperties, recordingService)
        val runtimeContext = RuntimeContext.builder()
            .userId(EVAL_USER_ID)
            .sessionId("eval-${case.id}-${System.nanoTime()}")
            .build()

        val events: List<AgentEvent> = coordinator
            .streamEvents(UserMessage(EVAL_USER_ID, case.userMessage), runtimeContext)
            .collectList()
            .block(EVAL_CASE_TIMEOUT)
            .orEmpty()

        val finalText = events.filterIsInstance<TextBlockDeltaEvent>().joinToString("") { it.delta }
        val askedToolCallIds = events.filterIsInstance<RequireUserConfirmEvent>()
            .flatMap { it.toolCalls }
            .map { it.id }
            .toSet()

        return EvalTranscript(
            calledTools = recordingService.calledEvents.map {
                EvalTranscript.CalledTool(it.toolCallId, it.toolName, it.argsDigest)
            },
            askedToolCallIds = askedToolCallIds,
            finalText = finalText,
        )
    }

    private fun buildCoordinator(
        runtimeProperties: EffectiveAgentRuntimeProperties,
        llmProperties: EffectiveAgentLlmProperties,
        recordingService: RecordingAgentToolCallRecordService,
    ) = run {
        val agentCatalog = AgentCatalog(
            definitions = listOf(ArtifactDiscoveryAgentDefinition(), TransferDiagnosticsAgentDefinition()),
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
            toolAuditMiddleware = ToolAuditMiddleware(recordingService, ObjectMapper()),
            delegationBudgetMiddleware = DelegationBudgetMiddleware(
                runtimeProperties,
                DelegationConcurrencyGuard(),
                NoopTaskRepositoryProvider(),
            ),
            memoryFilesystemAccess = MemoryFilesystemAccess(null, runtimeProperties.name),
        )
        val permissionContext: PermissionContextState =
            AgentPermissionRulesConfiguration().agentPermissionContext(runtimeProperties)
        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        // 只注册客户端本地工具（评估范围限定在 BKArtifacts 下载客户端场景，见 ClientToolEvalCases 类注释）；
        // discovery/transfer 领域工具需要真实服务端依赖或专门的假实现，留给后续增量按需补充。
        LocalToolDefinitions.allTools().forEach { definition ->
            toolkit.registerAgentTool(ExternalLocalTool(definition))
        }
        agentHarnessConfigurer.configure(
            properties = runtimeProperties,
            memory = AgentMemoryPropertiesResolver.resolve(
                AgentMemoryProperties(compactionEnabled = false, toolResultEvictionEnabled = false),
            ),
            model = AgentModelConfig().agentChatModel(llmProperties),
            stateStore = InMemoryAgentStateStore(),
            toolkit = toolkit,
            permissionContext = permissionContext,
        )
    }

    private fun resolveEvalLlmPropertiesFromEnv(): EffectiveAgentLlmProperties {
        val baseUrl = requireEnv("AGENT_EVAL_LLM_BASE_URL")
        val modelName = requireEnv("AGENT_EVAL_LLM_MODEL_NAME")
        val apiKey = System.getenv("AGENT_EVAL_LLM_API_KEY").orEmpty()
        val bkAppCode = System.getenv("AGENT_EVAL_LLM_BK_APP_CODE").orEmpty()
        val bkAppSecret = System.getenv("AGENT_EVAL_LLM_BK_APP_SECRET").orEmpty()
        require(apiKey.isNotBlank() || (bkAppCode.isNotBlank() && bkAppSecret.isNotBlank())) {
            "评估套件需要配置 AGENT_EVAL_LLM_API_KEY，或者同时配置 " +
                "AGENT_EVAL_LLM_BK_APP_CODE + AGENT_EVAL_LLM_BK_APP_SECRET 之一"
        }
        return AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(
                baseUrl = baseUrl,
                apiKey = apiKey,
                bkAppCode = bkAppCode,
                bkAppSecret = bkAppSecret,
                modelName = modelName,
                stream = true,
            ),
        )
    }

    private fun requireEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("missing required env var: $name")

    companion object {
        private val logger = LoggerFactory.getLogger(ClientToolEvalSuite::class.java)
        private const val EVAL_USER_ID = "eval-user"
        private const val EVAL_MAX_ITERS = 4
        private val EVAL_CASE_TIMEOUT: Duration = Duration.ofSeconds(90)
    }
}
