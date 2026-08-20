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
 * 将 BKArtifacts frontend tools 注册为 [ExternalLocalTool]，供 client 子 Agent 通过 allowlist 继承。
 *
 * 用 [ExternalLocalTool] 而不是框架自带的 [io.agentscope.core.tool.SchemaOnlyTool]：后者
 * `checkPermissions` 恒为 PASSTHROUGH，在 client 子 Agent 这类没有拿到协调者 ASK 规则表的
 * declared subagent 上会被静默放行（不弹确认框）；[ExternalLocalTool] 按风险等级自行 ASK/DENY，
 * 详见其类注释。
 *
 * Coordinator 在 [HarnessAgent] 构建完成后会从 live toolkit 移除这些工具（子 Agent factory 已持有副本）。
 * AG-UI 层使用 [io.agentscope.core.agui.model.ToolMergeMode.AGENT_ONLY]，不再 run-scoped 注入。
 */
@Component
class FrontendToolRegistrar(
    private val toolkit: Toolkit,
    private val runtimeProperties: EffectiveAgentRuntimeProperties,
    private val catalog: FrontendToolCatalog,
) : RegisteredFrontendTools {

    override val registeredToolNames: Set<String> = catalog.registeredToolNames

    @PostConstruct
    fun register() {
        if (!runtimeProperties.frontendToolsEnabled) {
            logger.info("frontend tools disabled, skipping ExternalLocalTool registration")
            return
        }
        LocalToolDefinitions.allTools().forEach { definition ->
            toolkit.registerAgentTool(ExternalLocalTool(definition))
        }
        logger.info(
            "registered {} frontend ExternalLocalTools: {}",
            registeredToolNames.size,
            registeredToolNames.sorted(),
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FrontendToolRegistrar::class.java)
    }
}
