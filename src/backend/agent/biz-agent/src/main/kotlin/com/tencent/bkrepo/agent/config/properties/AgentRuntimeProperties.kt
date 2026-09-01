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
    var reconnectPollInterval: Duration = DEFAULT_RECONNECT_POLL_INTERVAL,
    var reconnectTimeout: Duration = DEFAULT_RECONNECT_TIMEOUT,
    /**
     * 停机时等待在跑的 Agent 调用收尾的上限，透传给框架
     * [io.agentscope.core.shutdown.GracefulShutdownConfig.shutdownTimeout]。
     *
     * 必须是有限值：框架默认 null 表示"无限等待"，此时 JVM 停机钩子会一直等到所有在跑调用结束，
     * 而且 `GracefulShutdownManager` 的强制中断只在配了有限超时时才生效——两者叠加会让 SIGTERM
     * 之后进程迟迟不退，最后被 SIGKILL 硬杀。取值要留在 K8s terminationGracePeriodSeconds 之内，
     * 详见 [com.tencent.bkrepo.agent.config.AgentGracefulShutdownConfiguration] 里的超时预算说明。
     */
    var shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
    var state: State = State(),
    var task: Task = Task(),
    var memory: Memory = Memory(),
    var retention: Retention = Retention(),
    var features: Features = Features(),
    var topology: Topology = Topology(),
    var gray: Gray = Gray(),
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

    /**
     * 数据保留期（`agent.runtime.retention`）：Agent 自己产生的运行数据在多久之后自动清理。
     *
     * 每一项都可以配成 `0`（或负值）表示"永不过期"，用于个别环境需要长期留存审计数据的场景；
     * 默认值都是有限值，因为这些集合/键都是只写不删的——不设上限就会单调增长。
     *
     * ## Mongo 侧（靠 TTL 索引，由 Mongo 自己删）
     *
     * 四张表的过期时刻在写入时算好落到 `expiresAt` 字段上，配置改了只影响之后新写入的文档，
     * 已有文档保持写入当时算出的过期时刻，不会被追溯修正。
     *
     * 各项之间不是独立的，调整时注意这几条约束：
     * - [runEvent] 决定断线重连能回放多久以前的 run，必须小于等于 [run]：run 元数据都没了，
     *   只剩事件流没有任何意义。
     * - [session] 必须大于等于 [message]：会话元数据行先过期的话，剩下的消息就成了列不出来的孤儿数据
     *   （消息只能按 threadId 从会话入口查）。[session] 的过期时刻会在每次 run 开始时随
     *   `touchSession` 一起往后推，因此语义是"最后活跃之后再放多久"，而不是"创建之后多久"；
     *   [message] 则按消息自己的创建时间算，语义是"只保留最近这么久的聊天记录"。
     * - [run] 与 [toolCall] 是同一件事的两面（一次运行 + 这次运行里的工具调用审计），
     *   建议保持一致，否则查审计时会出现"有 run 没有工具调用记录"的空洞。
     *
     * ## Redis 侧（靠 key TTL，由 Redis 自己删）
     *
     * - [task] 是 `agent_spawn` 超时后被 promote 出的后台任务记录，语义是"最后一次写入之后再放多久"。
     *   任务本身活不过一次会话，这里的保留期只影响"事后还能不能查到这条任务记录"。
     * - [memory] 是长期记忆，语义是"最后一次使用之后再放多久"——读（`memory_search`/`memory_get`/
     *   用户直连查看）和写（`memory_save`/`memory_delete`）都会把过期时刻往后推。之所以按"最后使用"
     *   而不是"最后写入"，是因为用户半年前保存、之后一直在用的偏好不应该被静默清掉。
     */
    data class Retention(
        var runEvent: Duration = DEFAULT_RUN_EVENT_RETENTION,
        var run: Duration = DEFAULT_RUN_RETENTION,
        var toolCall: Duration = DEFAULT_TOOL_CALL_RETENTION,
        var message: Duration = DEFAULT_MESSAGE_RETENTION,
        var session: Duration = DEFAULT_SESSION_RETENTION,
        var task: Duration = DEFAULT_TASK_RETENTION,
        var memory: Duration = DEFAULT_MEMORY_RETENTION,
    )

    data class Features(
        var frontendToolsEnabled: Boolean = DEFAULT_FRONTEND_TOOLS_ENABLED,
        /**
         * 只读模式（生产应急开关）：开启后 Agent 的一切写操作能力都不可用——客户端本地写工具
         * （`set_download_path`/`delete_download_tasks` 等）不再注册给模型，长期记忆的
         * `memory_save`/`memory_delete` 由 ASK 收紧为 DENY。
         *
         * 边界：只约束"模型能做什么"，不约束用户自己发起的请求。用户直连的记忆管理接口
         * （[com.tencent.bkrepo.agent.api.user.UserAgentMemoryResource]）与会话删除接口仍然可用——
         * 用户对自己的数据始终有完全控制权，这个开关防的是 Agent 误操作，不是冻结整个服务。
         */
        var readOnlyMode: Boolean = DEFAULT_READ_ONLY_MODE,
    )

    /**
     * 灰度分流总闸（`agent.runtime.gray`）：控制哪些项目/用户能访问 Agent 服务。
     *
     * 这里管的是"能不能进"，判断点只在 [com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator] 入口，
     * 不深入到任何子 Agent 或工具——那需要把 `AgentCatalog`/`PermissionEngine`/`FrontendToolRegistrar`
     * 这些启动期就装配好的全局单例改造成按请求动态计算，成本和风险与之前评估过的"委派硬并发限制"是
     * 同一个量级，窄范围内先不做；等有了"按名单决定能用哪些子 Agent/工具"的真实需求再付这个代价。
     *
     * 默认 [enabled] = false：不开灰度时对现有部署零影响，与 `read-only-mode`/`require-redis` 同样是
     * "限制性功能默认关闭"。
     */
    data class Gray(
        var enabled: Boolean = DEFAULT_ENABLED,
        /** 项目名单，主维度——bk-repo 本来就按项目隔离，是运营侧最自然的放量单位。 */
        var allowedProjectIds: Set<String> = emptySet(),
        /** 用户名单，用于内部测试/白名单用户跨项目验证；与项目名单是 OR 关系，任一命中即放行。 */
        var allowedUserIds: Set<String> = emptySet(),
    ) {
        companion object {
            const val DEFAULT_ENABLED = false
        }
    }

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
        val DEFAULT_RUN_EVENT_RETENTION: Duration = Duration.ofDays(7)
        val DEFAULT_RUN_RETENTION: Duration = Duration.ofDays(90)
        val DEFAULT_TOOL_CALL_RETENTION: Duration = Duration.ofDays(90)
        val DEFAULT_MESSAGE_RETENTION: Duration = Duration.ofDays(180)
        val DEFAULT_SESSION_RETENTION: Duration = Duration.ofDays(180)
        val DEFAULT_TASK_RETENTION: Duration = Duration.ofDays(7)
        val DEFAULT_MEMORY_RETENTION: Duration = Duration.ofDays(180)
        val DEFAULT_RECONNECT_POLL_INTERVAL: Duration = Duration.ofMillis(500)
        val DEFAULT_RECONNECT_TIMEOUT: Duration = Duration.ofMinutes(10)
        val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val DEFAULT_FRONTEND_TOOLS_ENABLED = true
        const val DEFAULT_READ_ONLY_MODE = false
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

/** 见 [AgentRuntimeProperties.Retention]。 */
data class EffectiveAgentRetention(
    val runEvent: Duration,
    val run: Duration,
    val toolCall: Duration,
    val message: Duration,
    val session: Duration,
    val task: Duration,
    val memory: Duration,
) {
    companion object {
        fun from(retention: AgentRuntimeProperties.Retention): EffectiveAgentRetention = EffectiveAgentRetention(
            runEvent = retention.runEvent,
            run = retention.run,
            toolCall = retention.toolCall,
            message = retention.message,
            session = retention.session,
            task = retention.task,
            memory = retention.memory,
        )

        fun defaults(): EffectiveAgentRetention = from(AgentRuntimeProperties.Retention())
    }
}

/** 见 [AgentRuntimeProperties.Gray]。 */
data class EffectiveAgentGray(
    val enabled: Boolean,
    val allowedProjectIds: Set<String>,
    val allowedUserIds: Set<String>,
) {
    /** 灰度关闭时永远放行；开启时项目或用户命中任一名单即放行（OR 语义）。 */
    fun isAllowed(userId: String, projectId: String): Boolean {
        if (!enabled) return true
        return projectId in allowedProjectIds || userId in allowedUserIds
    }

    companion object {
        fun from(gray: AgentRuntimeProperties.Gray): EffectiveAgentGray = EffectiveAgentGray(
            enabled = gray.enabled,
            allowedProjectIds = gray.allowedProjectIds,
            allowedUserIds = gray.allowedUserIds,
        )

        fun defaults(): EffectiveAgentGray = from(AgentRuntimeProperties.Gray())
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
    val reconnectPollInterval: Duration,
    val reconnectTimeout: Duration,
    val shutdownTimeout: Duration,
    val stateKeyPrefix: String,
    val requireRedis: Boolean,
    val taskStoreKeyPrefix: String,
    val memoryStoreKeyPrefix: String,
    val frontendToolsEnabled: Boolean,
    val readOnlyMode: Boolean,
    val retention: EffectiveAgentRetention,
    val topology: EffectiveAgentTopology,
    val gray: EffectiveAgentGray,
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
            reconnectPollInterval = runtime.reconnectPollInterval,
            reconnectTimeout = runtime.reconnectTimeout,
            shutdownTimeout = runtime.shutdownTimeout,
            stateKeyPrefix = runtime.state.keyPrefix,
            requireRedis = runtime.state.requireRedis,
            taskStoreKeyPrefix = runtime.task.keyPrefix,
            memoryStoreKeyPrefix = runtime.memory.keyPrefix,
            frontendToolsEnabled = runtime.features.frontendToolsEnabled,
            readOnlyMode = runtime.features.readOnlyMode,
            retention = EffectiveAgentRetention.from(runtime.retention),
            topology = EffectiveAgentTopology.from(runtime.topology),
            gray = EffectiveAgentGray.from(runtime.gray),
        )
}
