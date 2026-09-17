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
        /**
         * 长期记忆定期整理任务（[com.tencent.bkrepo.agent.memory.AgentMemoryMaintenanceJob]）的执行间隔与
         * daily ledger（`memory/YYYY-MM-DD.md`）保留期。
         *
         * 只清理 daily ledger 文件本身，不处理 `MEMORY.md` 的合并/去重/裁剪——后者是框架
         * `MemoryMaintenanceMiddleware`/`MemoryConsolidator` 的职责，需要另起一次 LLM 调用改写用户长期
         * 记忆，随 [com.tencent.bkrepo.agent.config.AgentHarnessConfigurer] 里的 `disableMemoryHooks()`
         * 被整体关闭（不经过 HITL，产品策略上不能悄悄用 LLM 改写用户保存的内容），这里不重新实现，
         * 属于已知边界，见该处与 [com.tencent.bkrepo.agent.memory.AgentMemoryMaintenanceJob] 的注释。
         *
         * [dailyLedgerRetention] 配成 `0`（或负值）表示不清理，与 [Retention] 的
         * "0/负值=永不过期"约定保持一致；此时任务在 `@PostConstruct` 阶段直接不启动调度。
         */
        var dailyLedgerRetention: Duration = DEFAULT_DAILY_LEDGER_RETENTION,
        var maintenanceInterval: Duration = DEFAULT_MAINTENANCE_INTERVAL,
    ) {
        companion object {
            const val DEFAULT_KEY_PREFIX = "bkrepo:agent:memory-store:"
            val DEFAULT_DAILY_LEDGER_RETENTION: Duration = Duration.ofDays(90)
            val DEFAULT_MAINTENANCE_INTERVAL: Duration = Duration.ofHours(24)
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
        var overrides: List<Override> = emptyList(),
    ) {
        /**
         * 按项目/用户维度覆盖保留期（`agent.runtime.retention.overrides`），用于个别项目需要更长审计留存、
         * 或个别内部测试项目/用户需要更短保留期的场景。
         *
         * 只覆盖 [runEvent]/[run]/[toolCall]/[message]/[session] 这五项——它们都是"写入记录时按 `Instant`
         * 现算现打 `expiresAt` 戳"（见 [com.tencent.bkrepo.agent.retention.AgentRetentionPolicy]），天然支持
         * 按每条记录单独决定过期时刻。[task]/[memory] 是在 Redis key 级别、Bean 构造时一次性配置 TTL（见
         * `AgentTaskRepositoryConfiguration`/`AgentMemoryFilesystemConfiguration`），要做到差异化需要把
         * "构造 store 时固定 TTL"改成"每次写入时动态传入"，改造面完全不同，不在这次范围内。
         *
         * 匹配规则：[projectIds]/[userIds] 是 OR 关系（与 [Gray] 一致）；[overrides] 列表里第一个命中的
         * 生效，不做多个 override 合并；命中的 override 里某一项留空（`null`）表示这一项不覆盖，沿用全局
         * 默认值，而不是"覆盖成永不过期"。
         */
        data class Override(
            /** 仅用于 YAML 里标注这条 override 是给谁配的，不参与匹配逻辑。 */
            var name: String = "",
            var projectIds: Set<String> = emptySet(),
            var userIds: Set<String> = emptySet(),
            var runEvent: Duration? = null,
            var run: Duration? = null,
            var toolCall: Duration? = null,
            var message: Duration? = null,
            var session: Duration? = null,
        )
    }

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
     *
     * ## 运行时热更新
     *
     * [AgentRunOrchestrator][com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator] 直接持有这个
     * 类所属的 [AgentRuntimeProperties] bean（而不是启动期就拍平固化的
     * [EffectiveAgentRuntimeProperties]），每次请求都调用 [isAllowed] 现读当前字段值。`@ConfigurationProperties`
     * bean 本身在配置中心（Consul）推送变更后会被 Spring Cloud Context 的 `ConfigurationPropertiesRebinder`
     * 原地重新绑定（同一个单例实例、字段被新值覆盖），因此改名单不需要重启，也不需要 `@RefreshScope`——
     * 这条链路依赖配置中心的 watch 机制真的把变更同步到了这个副本的 Environment，本地开发不接配置中心时
     * 改配置仍然要重启。只读模式（[Features.readOnlyMode]）没有做同样的处理：它被烘焙进了
     * `PermissionContextState`/`FrontendToolRegistrar` 注册的工具集/`HarnessAgent` 系统提示词这三处启动期
     * 缓存，要热更新还需要额外重建这几个 Bean，改造面明显更大，这次没有做。
     */
    data class Gray(
        var enabled: Boolean = DEFAULT_ENABLED,
        /** 项目名单，主维度——bk-repo 本来就按项目隔离，是运营侧最自然的放量单位。 */
        var allowedProjectIds: Set<String> = emptySet(),
        /** 用户名单，用于内部测试/白名单用户跨项目验证；与项目名单是 OR 关系，任一命中即放行。 */
        var allowedUserIds: Set<String> = emptySet(),
    ) {
        /** 灰度关闭时永远放行；开启时项目或用户命中任一名单即放行（OR 语义），与 [EffectiveAgentGray.isAllowed] 语义一致。 */
        fun isAllowed(userId: String, projectId: String): Boolean {
            if (!enabled) return true
            return projectId in allowedProjectIds || userId in allowedUserIds
        }

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
            var governance: AgentBinding = AgentBinding(enabled = false, maxSteps = 8),
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
        val governance: AgentBinding,
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
                governance = AgentBinding(
                    enabled = topology.agents.governance.enabled,
                    modelProfile = topology.agents.governance.modelProfile,
                    maxSteps = topology.agents.governance.maxSteps,
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
    val overrides: List<Override>,
) {
    /** 见 [AgentRuntimeProperties.Retention.Override]。 */
    data class Override(
        val projectIds: Set<String>,
        val userIds: Set<String>,
        val runEvent: Duration?,
        val run: Duration?,
        val toolCall: Duration?,
        val message: Duration?,
        val session: Duration?,
    )

    /** [projectId]/[userId] 命中 [overrides] 中任一名单即算命中，取第一个命中项；否则返回 null 落回全局默认值。 */
    private fun matchOverride(projectId: String?, userId: String?): Override? = overrides.firstOrNull {
        (projectId != null && projectId in it.projectIds) || (userId != null && userId in it.userIds)
    }

    fun runEventFor(projectId: String? = null, userId: String? = null): Duration =
        matchOverride(projectId, userId)?.runEvent ?: runEvent

    fun runFor(projectId: String? = null, userId: String? = null): Duration =
        matchOverride(projectId, userId)?.run ?: run

    fun toolCallFor(projectId: String? = null, userId: String? = null): Duration =
        matchOverride(projectId, userId)?.toolCall ?: toolCall

    fun messageFor(projectId: String? = null, userId: String? = null): Duration =
        matchOverride(projectId, userId)?.message ?: message

    fun sessionFor(projectId: String? = null, userId: String? = null): Duration =
        matchOverride(projectId, userId)?.session ?: session

    companion object {
        fun from(retention: AgentRuntimeProperties.Retention): EffectiveAgentRetention = EffectiveAgentRetention(
            runEvent = retention.runEvent,
            run = retention.run,
            toolCall = retention.toolCall,
            message = retention.message,
            session = retention.session,
            task = retention.task,
            memory = retention.memory,
            overrides = retention.overrides.map {
                Override(
                    projectIds = it.projectIds,
                    userIds = it.userIds,
                    runEvent = it.runEvent,
                    run = it.run,
                    toolCall = it.toolCall,
                    message = it.message,
                    session = it.session,
                )
            },
        )

        fun defaults(): EffectiveAgentRetention = from(AgentRuntimeProperties.Retention())
    }
}

/**
 * 见 [AgentRuntimeProperties.Gray]。
 *
 * 这是启动期拍平出来的一份不可变快照，随 [EffectiveAgentRuntimeProperties] 一起在 Bean 创建时算一次、
 * 之后不会跟着配置中心的推送变化——真正参与放行判定的是 [AgentRuntimeProperties.Gray] 的同名
 * `isAllowed`（[com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator] 直接持有 live 的
 * [AgentRuntimeProperties]），这里的 [isAllowed] 只保留给需要"启动时那份配置长什么样"的场景（比如
 * 日志/展示），不要用来做热更新相关的放行判定。
 */
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
    val memoryDailyLedgerRetention: Duration,
    val memoryMaintenanceInterval: Duration,
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
            memoryDailyLedgerRetention = runtime.memory.dailyLedgerRetention,
            memoryMaintenanceInterval = runtime.memory.maintenanceInterval,
            frontendToolsEnabled = runtime.features.frontendToolsEnabled,
            readOnlyMode = runtime.features.readOnlyMode,
            retention = EffectiveAgentRetention.from(runtime.retention),
            topology = EffectiveAgentTopology.from(runtime.topology),
            gray = EffectiveAgentGray.from(runtime.gray),
        )
}
