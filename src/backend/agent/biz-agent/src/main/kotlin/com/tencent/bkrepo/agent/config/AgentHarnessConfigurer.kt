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
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.agent.AgentCatalog
import com.tencent.bkrepo.agent.agent.client.ClientAgentPrompt
import com.tencent.bkrepo.agent.audit.ToolAuditMiddleware
import com.tencent.bkrepo.agent.hitl.PermissionConfirmResumeMiddleware
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentMemoryProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import com.tencent.bkrepo.agent.subagent.DelegationBudgetMiddleware
import com.tencent.bkrepo.agent.tool.memory.MemoryDeleteTool
import com.tencent.bkrepo.agent.usage.UsageTrackingMiddleware
import io.agentscope.core.model.Model
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.tool.Toolkit
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec
import io.agentscope.harness.agent.subagent.task.TaskRepository
import org.springframework.stereotype.Component
import java.nio.file.Paths

/**
 * 集中装配 [HarnessAgent]，显式固定自定义 Middleware 与框架内置 Middleware 的相对顺序。
 *
 * 消息归档已迁移至 AG-UI 事件层（[com.tencent.bkrepo.agent.agui.AguiMessageArchiveHandler]），
 * 不再经由 AgentScope Middleware 双写。
 */
@Component
class AgentHarnessConfigurer(
    private val agentMemoryConfig: AgentMemoryConfig,
    private val agentCatalog: AgentCatalog,
    private val permissionConfirmResumeMiddleware: PermissionConfirmResumeMiddleware,
    private val usageTrackingMiddleware: UsageTrackingMiddleware,
    private val toolAuditMiddleware: ToolAuditMiddleware,
    private val delegationBudgetMiddleware: DelegationBudgetMiddleware,
    private val memoryFilesystemAccess: MemoryFilesystemAccess,
) {

    fun configure(
        properties: EffectiveAgentRuntimeProperties,
        memory: EffectiveAgentMemoryProperties,
        model: Model,
        stateStore: AgentStateStore,
        toolkit: Toolkit,
        permissionContext: PermissionContextState,
        taskRepository: TaskRepository? = null,
        memoryFilesystemSpec: RemoteFilesystemSpec? = null,
    ): HarnessAgent {
        var builder = HarnessAgent.builder()
            .name(properties.name)
            .sysPrompt(effectiveSysPrompt(properties))
            .model(model)
            .maxIters(properties.maxIters)
            .stateStore(stateStore)
            .workspace(Paths.get(properties.workspace))
            .toolkit(toolkit)
            .permissionContext(permissionContext)
            .enablePendingToolRecovery(true)
            .disableFilesystemTools()
            .disableShellTool()
            .disableDynamicSkills()
            .disableDynamicSubagents()
            .disableWorkspaceContext()
            // 框架自带的自动 flush（每轮对话结束后另起一次 LLM 调用，把它认为重要的内容静默写进
            // MEMORY.md/memory/*.md）不经过 Toolkit/PermissionEngine，没有 HITL 钩子可挂。产品上要求
            // "记忆写入需用户明确同意"，而同意机制选择的是 memory_save 工具走 ASK 确认——这个前提只对
            // 显式工具调用成立，覆盖不到自动 flush，所以这里无条件关闭自动 flush/maintenance 钩子，
            // 长期记忆只能通过 memory_save 显式写入。副作用：暂时没有 MemoryMaintenanceMiddleware 提供
            // 的每日文件归档/MEMORY.md 定期整理，阶段 10 窄范围内先不补，后续如需要再补一个独立的定时任务。
            .disableMemoryHooks()
            .middleware(permissionConfirmResumeMiddleware)
            .middleware(usageTrackingMiddleware)
            .middleware(toolAuditMiddleware)
            .middleware(delegationBudgetMiddleware)

        // 没有 Redis 时 taskRepository 为 null：交给框架退回默认的本地文件系统实现（见
        // AgentTaskRepositoryConfiguration 的 kdoc），与升级前行为一致。
        if (taskRepository != null) {
            builder = builder.taskRepository(taskRepository)
        }

        // 没有 Redis 时 memoryFilesystemSpec 为 null：长期记忆能力整体关闭（见
        // AgentMemoryFilesystemConfiguration 的 kdoc，为什么这里不像 taskRepository 一样退回本地实现）。
        builder = if (memoryFilesystemSpec != null) {
            // memory_delete 是框架没有的补充工具（只有 save/search/get），用 MemoryFilesystemAccess
            // 直接读写同一份 Redis 存储；可见性开关跟其它 memory_* 工具保持同步，只在这个分支注册。
            toolkit.registerTool(MemoryDeleteTool(memoryFilesystemAccess))
            builder.filesystem(memoryFilesystemSpec)
        } else {
            builder.disableMemoryTools()
        }

        if (properties.topology.coordinator.enabled) {
            builder = builder.enableTaskList(properties.topology.coordinator.taskListEnabled)
            val subagents = agentCatalog.resolveSubagentDeclarations()
            if (subagents.isNotEmpty()) {
                builder = builder.subagents(subagents)
            }
        } else {
            builder = builder.disableSubagents()
        }

        builder = agentMemoryConfig.apply(builder, memory)

        return builder.build()
    }

    /**
     * client 本地工具（`set_download_path` 等）已拍平到协调者自身，不再是独立子 Agent，因此原来
     * `client` 子 Agent 的系统提示词（[ClientAgentPrompt.DEFAULT]，排查方式/能力边界等域内行为规则）
     * 需要拼接进协调者自己的系统提示词——仅在 `frontendToolsEnabled` 时拼接，与历史上只有开启该
     * 开关时才会材料化 `client` 子 Agent 的行为保持一致。
     *
     * 只读模式下再追加 [AgentSystemPrompts.READ_ONLY_MODE]，且必须放在最后：[ClientAgentPrompt.DEFAULT]
     * 里仍描述着 `set_download_path` 等写工具的用法，靠后面这段兜底纠偏（工具本身已不注册，见
     * [com.tencent.bkrepo.agent.tool.frontend.FrontendToolRegistrar]）。
     */
    private fun effectiveSysPrompt(properties: EffectiveAgentRuntimeProperties): String {
        val parts = mutableListOf(properties.sysPrompt)
        if (properties.frontendToolsEnabled) {
            parts += ClientAgentPrompt.DEFAULT
        }
        if (properties.readOnlyMode) {
            parts += AgentSystemPrompts.READ_ONLY_MODE
        }
        return parts.joinToString("\n\n")
    }
}
