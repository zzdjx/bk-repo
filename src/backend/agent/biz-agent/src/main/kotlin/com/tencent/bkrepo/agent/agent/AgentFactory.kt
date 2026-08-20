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
        .persistSession(requiresPersistentSession(definition))
        .build()

    /**
     * `client` 子代理必须用确定性 sessionId（`persistSession(true)`），否则
     * [com.tencent.bkrepo.agent.hitl.SubagentConfirmResumeExecutor] 无法在用户点击确认后重新定位到
     * 它挂起的会话状态——见该类类注释。
     *
     * `AgentSpawnTool` 在 `persistSession(false)`（其它子代理的默认值）时，每次 `agent_spawn` 都会生成
     * 一个新的随机 `sessionId`（`"sub-"+UUID`），而 [io.agentscope.core.ReActAgent] 的状态是按
     * `RuntimeContext.getSessionId()` 寻址持久化的（`activateSlotForContext`）——也就是说，随机
     * sessionId 一旦这次调用结束就无法再从 (userId, threadId) 反推出来，写操作确认恢复也就无从查起。
     * `persistSession(true)` 则让 `AgentSpawnTool` 改用
     * `"sub-" + SessionIdUtils.deterministicHash(threadId, agentId)`（未使用 label 时）——纯函数、可在
     * resume 时独立重算，这正是 [com.tencent.bkrepo.agent.hitl.SubagentConfirmResumeExecutor] 依赖的前提。
     * 其余只读子代理（discovery/transfer-diagnostics）没有跨轮确认恢复的需求，维持默认的按次全新会话。
     */
    private fun requiresPersistentSession(definition: DomainAgentDefinition): Boolean =
        definition.agentId == AgentIds.CLIENT
}
