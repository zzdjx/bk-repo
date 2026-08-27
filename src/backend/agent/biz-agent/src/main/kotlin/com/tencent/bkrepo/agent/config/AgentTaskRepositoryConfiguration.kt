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
import com.tencent.bkrepo.agent.task.LettuceStore
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import io.agentscope.harness.agent.subagent.task.TaskRepository
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository
import io.agentscope.harness.agent.workspace.WorkspaceManager
import io.lettuce.core.RedisClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.nio.file.Paths

/**
 * 阶段 9（分布式子任务）第一步：让 `agent_spawn` 同步等待超时后被框架 promote 出的后台任务
 * （见 `AgentSpawnTool` 的 `AdoptedTaskRunSpec` 分支）跨副本可查询。
 *
 * 只替换 [io.agentscope.harness.agent.subagent.task.TaskRepository] 落地用的
 * [io.agentscope.harness.agent.filesystem.remote.store.BaseStore]，不改变 `WorkspaceTaskRepository`
 * 本身的 heartbeat/orphan sweeper/JSON 记录格式等逻辑，也完全不影响协调者自己的
 * memory/skills/AGENTS.md 等工作区文件——那些仍然走本地磁盘（[AgentHarnessConfigurer] 已经
 * `disableFilesystemTools()`/`disableDynamicSkills()` 等禁用了协调者自身对工作区文件的读写工具，
 * 这里新增的 [RemoteFilesystem] 只服务于这一个专属的 [WorkspaceManager] 实例，二者互不影响）。
 */
@Configuration(proxyBeanMethods = false)
class AgentTaskRepositoryConfiguration {

    /**
     * 没有 Lettuce Redis 客户端时返回 `null`：[AgentHarnessConfigurer] 会跳过
     * `.taskRepository(...)` 装配，`HarnessAgent.build()` 退回框架默认的本地文件系统实现
     * `WorkspaceTaskRepository(本地 wsManager, ...)`——与升级前行为完全一致，仅限单副本内可查，
     * 不引入任何新的降级复杂度。
     */
    @Bean
    fun agentTaskRepository(
        properties: EffectiveAgentRuntimeProperties,
        connectionFactory: ObjectProvider<RedisConnectionFactory>,
    ): TaskRepository? {
        val client = (connectionFactory.getIfAvailable() as? LettuceConnectionFactory)?.nativeClient as? RedisClient
        if (client == null) {
            logger.warn(
                "No lettuce redis client available, background subagent tasks will use the local " +
                    "filesystem TaskRepository. Task records will be lost on restart and cannot be " +
                    "queried from another replica.",
            )
            return null
        }
        val store = LettuceStore(client, properties.taskStoreKeyPrefix)
        // 固定命名空间（不随 RuntimeContext 变化）：任务记录本身已经按
        // `agents/<parentAgentId>/tasks/<sessionId>.json` 分文件，不需要 store 层再按 user 隔离。
        val filesystem = RemoteFilesystem(store, listOf("agents", properties.name, "tasks"))
        // index=null：任务记录规模小，WorkspaceTaskRepository 的 orphan sweeper 直接用
        // filesystem.glob 做全量扫描即可，没有必要为此再起一个本地 SQLite 索引。
        val workspaceManager = WorkspaceManager(Paths.get(properties.workspace), filesystem, null, null)
        return WorkspaceTaskRepository(workspaceManager, properties.name)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentTaskRepositoryConfiguration::class.java)
    }
}
