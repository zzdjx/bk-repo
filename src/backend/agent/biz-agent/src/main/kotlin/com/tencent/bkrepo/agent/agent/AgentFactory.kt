/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.agent

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentTopology
import io.agentscope.harness.agent.subagent.SubagentDeclaration
import org.springframework.stereotype.Component

@Component
class AgentFactory {

    /**
     * 只读子代理（discovery/transfer-diagnostics）没有跨轮确认恢复的需求，每次 `agent_spawn` 用
     * 全新随机 sessionId 即可（`persistSession(false)`）。会触发写工具挂起/恢复的 `client` 本地工具
     * 已拍平到协调者自己身上（见 [com.tencent.bkrepo.agent.config.HarnessAgentConfiguration]），不再
     * 以子代理形式声明，因此这里不需要（也不应该）为任何子代理配置 `persistSession(true)`。
     */
    fun toSubagentDeclaration(
        definition: DomainAgentDefinition,
        binding: EffectiveAgentTopology.AgentBinding,
    ): SubagentDeclaration = SubagentDeclaration.builder()
        .name(definition.agentId)
        .description(definition.description)
        .inlineAgentsBody(definition.sysPrompt)
        .tools(definition.allowedToolNames.toList())
        .steps(binding.maxSteps)
        .maxIters(binding.maxSteps)
        .inheritParentPermissions(true)
        .mode(SubagentDeclaration.Mode.SUBAGENT)
        .persistSession(false)
        .build()
}
