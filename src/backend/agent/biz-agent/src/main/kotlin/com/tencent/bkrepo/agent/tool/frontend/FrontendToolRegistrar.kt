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
