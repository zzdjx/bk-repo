/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.permission

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
import com.tencent.bkrepo.agent.hitl.PermissionConfirmResumeMiddleware
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.RegisteredDomainTools
import com.tencent.bkrepo.agent.tool.frontend.RegisteredFrontendTools
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.ToolkitConfig
import io.agentscope.harness.agent.HarnessAgent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 复现「client 子 Agent 的 set_download_path 从不触发 HITL 确认」问题的根因。
 *
 * 结论（详见断言注释）：[AgentPermissionRulesConfiguration] 构造的 [PermissionContextState] 只挂在
 * 顶层协调者 Agent 上；client/discovery/transfer-diagnostics 这类通过
 * `HarnessAgent.builder().subagents(declarations)` 声明的固定子 Agent，由框架内部
 * `HarnessAgentBuilderSupport.buildDeclaredFactory` 材料化时**不会**把父级 PermissionContextState
 * 传给子 Agent 的 builder（`SubagentDeclaration.inheritParentPermissions` 按其 Javadoc 明确说明只
 * 转发 DENY 规则）。子 Agent 因此拿到一个 trivial 的 PermissionContextState，触发
 * `ReActAgent.evaluatePermissions` 里的 legacy 兜底路径（`useEngine = false`），直接绕过我们配置的
 * ASK 规则表，只要工具自身的 `checkPermissions` 不主动要求确认就会静默放行——这正是
 * set_download_path 从不产生 RequireUserConfirmEvent 的根因，而不是 AG-UI 事件转发链路的问题。
 *
 * ## 这个框架限制本身没有被修复，但已经不再影响用户可见行为
 *
 * `agentscope-harness` 2.0.1 没有给 `subagents(declarations)` 声明的固定子 Agent 暴露任何传递完整
 * [io.agentscope.core.permission.PermissionContextState]（含 ASK/ALLOW 规则表）的公开扩展点——
 * 本测试证明的 trivial context 现状依然成立，且预计短期内不会有上游修复。真正堵住这个漏洞的是
 * [com.tencent.bkrepo.agent.tool.local.ExternalLocalTool.checkPermissions]：它按
 * [com.tencent.bkrepo.agent.tool.local.LocalToolDefinition.riskLevel] 直接自检出 ASK/DENY 决策，
 * 不依赖 `PermissionContextState` 是否 trivial——legacy 兜底路径本来就会尊重工具自身返回的 ASK/DENY，
 * 只是之前用的是框架自带的 [io.agentscope.core.tool.SchemaOnlyTool]（恒 PASSTHROUGH）才被静默放行。
 * 详见 [com.tencent.bkrepo.agent.tool.local.ExternalLocalToolTest] 与
 * `AgentSpawnSetDownloadPathSuspensionEndToEndTest` 的端到端回归。
 */
@DisplayName("子 Agent 权限上下文继承回归测试")
class SubagentPermissionInheritanceTest {

    @Test
    fun `client 子 Agent 材料化后权限上下文应包含 set_download_path 的 ASK 规则但实际是 trivial`(
        @TempDir workspace: Path,
    ) {
        val runtimeProperties = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
        val llmProperties = AgentLlmPropertiesResolver.resolve(
            AgentLlmProperties(
                baseUrl = "http://127.0.0.1:1/v1",
                apiKey = "unused-stub-key",
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

        // 完全复刻 AgentPermissionRulesConfiguration.agentPermissionContext() 的生产逻辑：
        // set_download_path 是 WRITE_REVERSIBLE，应当落入 ASK 规则表。
        val coordinatorPermissionContext = AgentPermissionRulesConfiguration()
            .agentPermissionContext(runtimeProperties)
        assertTrue(
            coordinatorPermissionContext.askRules.containsKey("set_download_path"),
            "前置条件：协调者自己的权限上下文必须包含 set_download_path 的 ASK 规则",
        )

        val toolkit = Toolkit(ToolkitConfig.builder().parallel(false).build())
        val coordinator = agentHarnessConfigurer.configure(
            properties = runtimeProperties,
            memory = memoryProperties,
            model = AgentModelConfig().agentChatModel(llmProperties),
            stateStore = InMemoryAgentStateStore(),
            toolkit = toolkit,
            permissionContext = coordinatorPermissionContext,
        )

        val agentManager = coordinator.getSubagentAgentManager()
        assertTrue(agentManager != null, "协调者应注册了固定子 Agent 工厂（client/discovery/transfer-diagnostics）")

        val clientAgent = agentManager!!.createAgentIfPresent("client", RuntimeContext.empty())
        assertTrue(clientAgent.isPresent, "client 子 Agent 工厂应能材料化出实例")

        val delegate: ReActAgent = (clientAgent.get() as HarnessAgent).delegate
        val childPermissionContext = delegate.permissionContext

        // 关键断言：子 Agent 材料化后拿到的权限上下文是 trivial 的（没有任何 ASK/ALLOW/DENY 规则），
        // 说明 AgentPermissionRulesConfiguration 里为 set_download_path 配置的 ASK 规则根本没有
        // 传导到 client 子 Agent。这会让 ReActAgent.evaluatePermissions 里的
        // `useEngine = !state.getPermissionContext().isTrivial()` 判定为 false，
        // 从而走 legacy 兜底路径（只信工具自身 checkPermissions 的 ASK，绕过我们配置的规则表）。
        assertTrue(
            childPermissionContext.isTrivial,
            "复现 Bug：client 子 Agent 的权限上下文当前是 trivial 的，" +
                "证明父级 ASK 规则未被传导，set_download_path 会被 legacy 兜底路径静默放行",
        )
        assertFalse(
            childPermissionContext.askRules.containsKey("set_download_path"),
            "复现 Bug：client 子 Agent 权限上下文里没有 set_download_path 的 ASK 规则",
        )
    }
}
