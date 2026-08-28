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
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import com.tencent.bkrepo.agent.task.LettuceStore
import io.agentscope.harness.agent.IsolationScope
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec
import io.lettuce.core.RedisClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

/**
 * 阶段 10（长期记忆）：让 `memory_save`/`memory_search`/`memory_get` 使用的
 * `MEMORY.md` 与 `memory` 目录下 `*.md` 文件跨副本可查、按用户隔离。
 *
 * ## 为什么用 `RemoteFilesystemSpec` 而不是像 [AgentTaskRepositoryConfiguration] 那样另起一个
 * 专属 `WorkspaceManager`
 *
 * 框架的 `MemorySaveTool`/`MemorySearchTool`/`MemoryGetTool`（以及
 * `io.agentscope.harness.agent.middleware.MemoryFlushMiddleware`/`MemoryMaintenanceMiddleware`）
 * 在 `HarnessAgent.Builder.build()` 内部都固定共享同一个 `WorkspaceManager` 实例，没有像
 * `TaskRepository` 那样的 `.memoryFilesystem(...)` 独立注入点，所以要让记忆文件跨副本可查，只能
 * 通过 `HarnessAgent.Builder.filesystem(RemoteFilesystemSpec)` 把协调者的整个工作区换成远程存储。
 *
 * 这在当前装配下是安全的：[AgentHarnessConfigurer] 已经对协调者调用了
 * `disableFilesystemTools()`/`disableDynamicSkills()`/`disableDynamicSubagents()`/
 * `disableWorkspaceContext()`，工作区里除了记忆文件之外没有任何工具会读写其它路径
 * （`skills/`、`subagents/`、`AGENTS.md` 等）；`agent_spawn` 相关的后台任务另有专属的
 * `WorkspaceManager`（见 [AgentTaskRepositoryConfiguration]），不受这里影响。
 *
 * ## 与 [AgentStateConfiguration.agentStateStore] 的一致性
 *
 * `HarnessAgent.Builder.build()` 在配置了 `RemoteFilesystemSpec` 时会校验 `AgentStateStore` 不能是
 * 本地实现（`JsonFileAgentStateStore`/`InMemoryAgentStateStore`），否则直接抛异常。这里与
 * [AgentStateConfiguration] 使用同一个 Lettuce 客户端可用性作为开关信号，两者总是同时为
 * "有 Redis"或同时为"无 Redis"，不会出现该校验失败的组合。
 *
 * 没有 Lettuce 客户端时返回 `null`：[AgentHarnessConfigurer] 据此保持
 * `disableMemoryTools()`，长期记忆能力整体关闭，不退化为单副本本地行为——因为长期记忆的价值就在于
 * 跨会话/跨副本稳定可见，局部可用（只在恰好落到同一个副本时才能看到之前保存的记忆）比完全不可用
 * 更容易让用户困惑"我明明保存过，为什么找不到"。
 *
 * ## `agentMemoryStore` 为什么单独拆成一个 bean
 *
 * 补全阶段 10 时新增了"用户直连查看/删除记忆"的 REST 接口
 * （[MemoryFilesystemAccess]）和 `memory_delete` LLM 工具
 * （[com.tencent.bkrepo.agent.tool.memory.MemoryDeleteTool]），它们都需要绕开
 * `HarnessAgent` 内部私有的 `WorkspaceManager`、直接访问同一份底层存储。把 [LettuceStore] 单独
 * 拆成一个 bean（而不是像最初实现那样只在 `agentMemoryFilesystemSpec` 内部 `new`），
 * 是为了让这几处共用同一条 Redis 连接，而不是各自重新创建连接。
 *
 * ## `memoryFilesystemAccess` 为什么不用 `@Component` 自注册
 *
 * [MemoryFilesystemAccess] 依赖抽象的 `BaseStore` 接口而不是具体的 [LettuceStore]，构造函数只接受
 * `(store: BaseStore?, agentId: String)`。这里像阶段 9 `AgentTaskRepositoryConfiguration` 构造
 * `LimitedWorkspaceTaskRepository` 一样，用 `@Bean` 工厂方法而不是类自身 `@Component` 注解来完成 Spring
 * 装配——这样单测可以绕开 Spring 容器和真实 Lettuce 连接，直接 `new MemoryFilesystemAccess(fakeStore, id)`
 * 传入一个内存态的 `BaseStore` 假实现。
 */
@Configuration(proxyBeanMethods = false)
class AgentMemoryFilesystemConfiguration {

    @Bean
    fun agentMemoryStore(
        properties: EffectiveAgentRuntimeProperties,
        connectionFactory: ObjectProvider<RedisConnectionFactory>,
    ): LettuceStore? {
        val client = (connectionFactory.getIfAvailable() as? LettuceConnectionFactory)?.nativeClient as? RedisClient
        if (client == null) {
            logger.warn(
                "No lettuce redis client available, long-term memory tools (memory_save/memory_search/" +
                    "memory_get/memory_delete) and the direct memory view/delete REST API stay disabled. " +
                    "Without cross-replica storage the feature would silently fragment per replica, so it is " +
                    "kept off entirely instead of falling back to local disk.",
            )
            return null
        }
        return LettuceStore(client, properties.memoryStoreKeyPrefix)
    }

    @Bean
    fun agentMemoryFilesystemSpec(store: ObjectProvider<LettuceStore>): RemoteFilesystemSpec? {
        val memoryStore = store.getIfAvailable() ?: return null
        // IsolationScope.USER（也是 RemoteFilesystemSpec 的默认值）：按 userId 隔离，同一用户跨会话
        // 共享记忆，不同用户之间互不可见；显式写出来是为了让隔离维度的选择在代码里一目了然，不依赖默认值。
        return RemoteFilesystemSpec(memoryStore).isolationScope(IsolationScope.USER)
    }

    @Bean
    fun memoryFilesystemAccess(
        store: ObjectProvider<LettuceStore>,
        properties: EffectiveAgentRuntimeProperties,
    ): MemoryFilesystemAccess = MemoryFilesystemAccess(store.getIfAvailable(), properties.name)

    companion object {
        private val logger = LoggerFactory.getLogger(AgentMemoryFilesystemConfiguration::class.java)
    }
}
