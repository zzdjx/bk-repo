/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config.properties

import com.tencent.bkrepo.agent.config.AgentSystemPrompts
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Agent 运行时配置（`agent.runtime`）。
 *
 * 收敛 run 生命周期、输入限制、state 与 feature 开关。
 */
@ConfigurationProperties("agent.runtime")
data class AgentRuntimeProperties(
    var name: String = DEFAULT_NAME,
    var sysPrompt: String = AgentSystemPrompts.DEFAULT,
    var maxIters: Int = DEFAULT_MAX_ITERS,
    var workspace: String = DEFAULT_WORKSPACE,
    var sseTimeout: Duration = DEFAULT_SSE_TIMEOUT,
    var maxMessageLength: Int = DEFAULT_MAX_MESSAGE_LENGTH,
    var maxThreadIdLength: Int = DEFAULT_MAX_THREAD_ID_LENGTH,
    var sessionTtl: Duration = DEFAULT_SESSION_TTL,
    var activeRunTtl: Duration = DEFAULT_ACTIVE_RUN_TTL,
    var runEventTtl: Duration = DEFAULT_RUN_EVENT_TTL,
    var reconnectPollInterval: Duration = DEFAULT_RECONNECT_POLL_INTERVAL,
    var reconnectTimeout: Duration = DEFAULT_RECONNECT_TIMEOUT,
    var state: State = State(),
    var task: Task = Task(),
    var memory: Memory = Memory(),
    var features: Features = Features(),
    var topology: Topology = Topology(),
) {
    data class State(
        var keyPrefix: String = DEFAULT_KEY_PREFIX,
        var requireRedis: Boolean = DEFAULT_REQUIRE_REDIS,
    ) {
        companion object {
            const val DEFAULT_KEY_PREFIX = "bkrepo:agent:state:"
        }
    }

    /**
     * 分布式后台任务（[io.agentscope.harness.agent.subagent.task.TaskRepository]）存储配置。
     *
     * 只影响 `agent_spawn` 同步等待超时后被框架 promote 出的后台任务记录的持久化位置：
     * 有 Lettuce Redis 客户端时落 Redis（跨副本可查），否则退回框架默认的本地文件系统实现
     * （与升级前行为一致，仅限单副本内可查）。
     */
    data class Task(
        var keyPrefix: String = DEFAULT_KEY_PREFIX,
    ) {
        companion object {
            const val DEFAULT_KEY_PREFIX = "bkrepo:agent:task-store:"
        }
    }

    /**
     * 长期记忆（`memory_save`/`memory_search`/`memory_get`，见
     * [com.tencent.bkrepo.agent.config.AgentMemoryFilesystemConfiguration]）存储配置。
     *
     * 只影响 `MEMORY.md` 与 `memory` 目录下 `*.md` 文件的持久化位置：有 Lettuce Redis 客户端时落 Redis
     * （跨副本可查、按用户隔离），否则整个长期记忆能力保持关闭——不退回单副本本地文件系统，
     * 因为长期记忆需要跨会话/跨副本稳定可见，局部可用比完全不可用更容易造成"记忆丢失"的用户困惑。
     */
    data class Memory(
        var keyPrefix: String = DEFAULT_KEY_PREFIX,
    ) {
        companion object {
            const val DEFAULT_KEY_PREFIX = "bkrepo:agent:memory-store:"
        }
    }

    data class Features(
        var frontendToolsEnabled: Boolean = DEFAULT_FRONTEND_TOOLS_ENABLED,
    )

    data class Topology(
        var coordinator: Coordinator = Coordinator(),
        var agents: Agents = Agents(),
    ) {
        data class Coordinator(
            var enabled: Boolean = true,
            var maxDelegations: Int = DEFAULT_MAX_DELEGATIONS,
            var maxParallelDelegations: Int = DEFAULT_MAX_PARALLEL_DELEGATIONS,
            var taskListEnabled: Boolean = true,
        )

        data class Agents(
            var client: AgentBinding = AgentBinding(enabled = true, maxSteps = 10),
            var discovery: AgentBinding = AgentBinding(enabled = true),
            var transferDiagnostics: AgentBinding = AgentBinding(enabled = false, maxSteps = 10),
        )

        data class AgentBinding(
            var enabled: Boolean = false,
            var modelProfile: String = DEFAULT_MODEL_PROFILE,
            var maxSteps: Int = DEFAULT_MAX_STEPS,
        )
    }

    companion object {
        const val DEFAULT_NAME = "bkrepo-assistant"
        const val DEFAULT_MAX_ITERS = 10
        const val DEFAULT_WORKSPACE = "/data/workspace/agent"
        val DEFAULT_SSE_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val DEFAULT_MAX_MESSAGE_LENGTH = 32 * 1024
        const val DEFAULT_MAX_THREAD_ID_LENGTH = 128
        val DEFAULT_SESSION_TTL: Duration = Duration.ofDays(30)
        val DEFAULT_ACTIVE_RUN_TTL: Duration = Duration.ofMinutes(11)
        val DEFAULT_RUN_EVENT_TTL: Duration = Duration.ofDays(7)
        val DEFAULT_RECONNECT_POLL_INTERVAL: Duration = Duration.ofMillis(500)
        val DEFAULT_RECONNECT_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val DEFAULT_FRONTEND_TOOLS_ENABLED = true
        const val DEFAULT_REQUIRE_REDIS = false
        const val DEFAULT_MAX_DELEGATIONS = 8
        const val DEFAULT_MAX_PARALLEL_DELEGATIONS = 1
        const val DEFAULT_MODEL_PROFILE = "default"
        const val DEFAULT_MAX_STEPS = 8
    }
}

data class EffectiveAgentTopology(
    val coordinator: Coordinator,
    val agents: Agents,
) {
    data class Coordinator(
        val enabled: Boolean,
        val maxDelegations: Int,
        val maxParallelDelegations: Int,
        val taskListEnabled: Boolean,
    )

    data class AgentBinding(
        val enabled: Boolean,
        val modelProfile: String,
        val maxSteps: Int,
    )

    data class Agents(
        val client: AgentBinding,
        val discovery: AgentBinding,
        val transferDiagnostics: AgentBinding,
    )

    companion object {
        fun from(topology: AgentRuntimeProperties.Topology): EffectiveAgentTopology = EffectiveAgentTopology(
            coordinator = Coordinator(
                enabled = topology.coordinator.enabled,
                maxDelegations = topology.coordinator.maxDelegations,
                maxParallelDelegations = topology.coordinator.maxParallelDelegations,
                taskListEnabled = topology.coordinator.taskListEnabled,
            ),
            agents = Agents(
                client = AgentBinding(
                    enabled = topology.agents.client.enabled,
                    modelProfile = topology.agents.client.modelProfile,
                    maxSteps = topology.agents.client.maxSteps,
                ),
                discovery = AgentBinding(
                    enabled = topology.agents.discovery.enabled,
                    modelProfile = topology.agents.discovery.modelProfile,
                    maxSteps = topology.agents.discovery.maxSteps,
                ),
                transferDiagnostics = AgentBinding(
                    enabled = topology.agents.transferDiagnostics.enabled,
                    modelProfile = topology.agents.transferDiagnostics.modelProfile,
                    maxSteps = topology.agents.transferDiagnostics.maxSteps,
                ),
            ),
        )

        fun defaults(): EffectiveAgentTopology = from(AgentRuntimeProperties.Topology())
    }
}

data class EffectiveAgentRuntimeProperties(
    val name: String,
    val sysPrompt: String,
    val maxIters: Int,
    val workspace: String,
    val sseTimeout: Duration,
    val maxMessageLength: Int,
    val maxThreadIdLength: Int,
    val sessionTtl: Duration,
    val activeRunTtl: Duration,
    val runEventTtl: Duration,
    val reconnectPollInterval: Duration,
    val reconnectTimeout: Duration,
    val stateKeyPrefix: String,
    val requireRedis: Boolean,
    val taskStoreKeyPrefix: String,
    val memoryStoreKeyPrefix: String,
    val frontendToolsEnabled: Boolean,
    val topology: EffectiveAgentTopology,
) {
    companion object {
        fun defaults(): EffectiveAgentRuntimeProperties = AgentRuntimePropertiesResolver.resolve(AgentRuntimeProperties())
    }
}

object AgentRuntimePropertiesResolver {

    fun resolve(runtime: AgentRuntimeProperties): EffectiveAgentRuntimeProperties =
        EffectiveAgentRuntimeProperties(
            name = runtime.name,
            sysPrompt = runtime.sysPrompt.takeIf { it.isNotBlank() } ?: AgentSystemPrompts.DEFAULT,
            maxIters = runtime.maxIters,
            workspace = runtime.workspace,
            sseTimeout = runtime.sseTimeout,
            maxMessageLength = runtime.maxMessageLength,
            maxThreadIdLength = runtime.maxThreadIdLength,
            sessionTtl = runtime.sessionTtl,
            activeRunTtl = runtime.activeRunTtl,
            runEventTtl = runtime.runEventTtl,
            reconnectPollInterval = runtime.reconnectPollInterval,
            reconnectTimeout = runtime.reconnectTimeout,
            stateKeyPrefix = runtime.state.keyPrefix,
            requireRedis = runtime.state.requireRedis,
            taskStoreKeyPrefix = runtime.task.keyPrefix,
            memoryStoreKeyPrefix = runtime.memory.keyPrefix,
            frontendToolsEnabled = runtime.features.frontendToolsEnabled,
            topology = EffectiveAgentTopology.from(runtime.topology),
        )
}
