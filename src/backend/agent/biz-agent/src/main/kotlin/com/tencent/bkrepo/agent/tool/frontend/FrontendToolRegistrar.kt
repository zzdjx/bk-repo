/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.frontend

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.tool.local.ExternalLocalTool
import com.tencent.bkrepo.agent.tool.local.LocalToolDefinitions
import io.agentscope.core.tool.Toolkit
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 将 BKArtifacts frontend tools 注册为 [ExternalLocalTool]，直接挂在协调者自己的 [Toolkit] 上
 * （不再经由独立的 client 子 Agent）。
 *
 * 用 [ExternalLocalTool] 而不是框架自带的 [io.agentscope.core.tool.SchemaOnlyTool]：后者
 * `checkPermissions` 恒为 PASSTHROUGH，会被静默放行（不弹确认框）；[ExternalLocalTool] 按风险等级
 * 自行 ASK/DENY，与协调者自己的 [io.agentscope.core.permission.PermissionEngine] 规则表相互印证，
 * 详见其类注释。
 *
 * 这些工具会一直留在协调者的 live toolkit 上（见 [com.tencent.bkrepo.agent.config.HarnessAgentConfiguration]），
 * 由协调者自身直接调用、走跟 domain 工具完全同构的两轮挂起/恢复。AG-UI 层使用
 * [io.agentscope.core.agui.model.ToolMergeMode.AGENT_ONLY]，不再 run-scoped 注入。
 */
@Component
class FrontendToolRegistrar(
    private val toolkit: Toolkit,
    private val runtimeProperties: EffectiveAgentRuntimeProperties,
    private val catalog: FrontendToolCatalog,
) : RegisteredFrontendTools {

    /**
     * 服务端 authoritative 工具名 allowlist，用途是给 [com.tencent.bkrepo.agent.agent.AgentCatalog]
     * 校验子 Agent 的 allowedToolNames，以及给恢复校验识别工具名——因此**不随只读模式收缩**：
     * 只读模式下写工具虽然不注册给模型，但工具名本身仍要认得（否则历史挂起调用恢复时会被判为
     * 未知工具，报错信息会误导人）。真正"能不能执行"由 toolkit 注册与 PermissionEngine 决定。
     */
    override val registeredToolNames: Set<String> = catalog.registeredToolNames

    @PostConstruct
    fun register() {
        if (!runtimeProperties.frontendToolsEnabled) {
            logger.info("frontend tools disabled, skipping ExternalLocalTool registration")
            return
        }
        // 只读模式下写工具压根不注册：模型看不到它们，也就不会承诺"我去帮你改配置/删任务"再被
        // PermissionEngine 拒掉（既省一轮无用调用，回答也更自洽）。PermissionEngine 侧还有一层
        // DENY 兜底（见 AgentPermissionRulesConfiguration），防的是别处又把写工具挂回 toolkit。
        val readOnly = runtimeProperties.readOnlyMode
        val registered = LocalToolDefinitions.allTools()
            .filterNot { readOnly && catalog.isWriteTool(it.name) }
            .onEach { toolkit.registerAgentTool(ExternalLocalTool(it)) }
            .map { it.name }
        logger.info(
            "registered {} frontend ExternalLocalTools (readOnlyMode={}, skipped={}): {}",
            registered.size,
            readOnly,
            (registeredToolNames - registered.toSet()).sorted(),
            registered.sorted(),
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FrontendToolRegistrar::class.java)
    }
}
