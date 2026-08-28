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

package com.tencent.bkrepo.agent.memory

import com.tencent.bkrepo.agent.task.LettuceStore
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory

/**
 * 直接访问长期记忆底层存储（[BaseStore]，生产环境即 [LettuceStore]），供"用户直连查看/删除记忆" REST 接口
 * （`AgentMemoryService`）与 `memory_delete` LLM 工具（[com.tencent.bkrepo.agent.tool.memory.MemoryDeleteTool]）
 * 共用。
 *
 * ## 为什么不直接复用框架的 `WorkspaceManager`/`CompositeFilesystem`
 *
 * `memory_save`/`memory_search`/`memory_get` 使用的 `WorkspaceManager` 是 `HarnessAgent.Builder.build()`
 * 内部构造的私有实例，框架没有暴露任何公开的注入点/getter 能从外部拿到同一个对象引用。这里选择直接构造
 * 两个独立的 [RemoteFilesystem]（`root` 段对应 `MEMORY.md`，`memory` 段对应 `memory` 目录下 `*.md` 文件），命名空间规则
 * 严格照抄框架 `io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec#storeNamespace`/
 * `#remoteForRoute` 对这两个路由的实现：`["agents", <agentId>, "users", <uid>, <段>]`（`IsolationScope.USER`
 * 下的默认命名空间形状）。这样才能读到与协调者 Agent 侧完全同一份 Redis 数据。
 *
 * 这意味着"记忆存储的命名空间规则"这段逻辑目前在代码里存在两份（框架内部一份、这里复刻一份），是刻意接受
 * 的成本——框架没有给"绕开 WorkspaceManager 直接读写记忆存储"这条路径提供公开 API，只能照抄。如果未来框架
 * 升级改变了 `RemoteFilesystemSpec` 的命名空间布局，这里需要同步更新，否则本类会读不到 Agent 侧写入的文件
 * （反之亦然）。
 *
 * ## 为什么只覆盖用户实际保存过的内容
 *
 * 框架把 `MEMORY.md` 与 `memory` 目录下 `*.md` 文件包装成 `OverlayFilesystem`：upper 层是这里复刻的 [RemoteFilesystem]
 * （per-user，持久化在 Redis），lower 层是只读的本地模板文件（用户从未写过时的兜底基线内容）。这里的实现
 * 只直接访问 upper 层，不去处理 lower 层的模板兜底——这符合本类的语义定位："用户自己保存过的记忆"，不应该
 * 把系统模板误当成用户的记忆展示/删除。代价是：如果工作区里确实配置了 `MEMORY.md` 模板文件，且用户从未
 * 调用过 `memory_save`，这里的 [listFiles]/[readFile] 会认为该文件不存在（而不是显示模板兜底内容）——在
 * 当前部署形态下（协调者已 `disableWorkspaceContext()`，服务端工作区不是用户可编辑的文件系统）这个模板
 * 文件通常并不存在，属于可接受的边界行为。
 */
/**
 * 不注解 `@Component`：由
 * [com.tencent.bkrepo.agent.config.AgentMemoryFilesystemConfiguration] 通过 `@Bean` 工厂方法构造
 * （与阶段 9 `LimitedWorkspaceTaskRepository` 的装配方式一致），这样测试可以直接 `new` 一个内存态的
 * [BaseStore] 假实现传进来，不需要真实 Lettuce 连接或 Mockito 驱动的假 Redis。
 */
class MemoryFilesystemAccess(
    private val store: BaseStore?,
    private val agentId: String,
) {

    /** 没有 Lettuce Redis 客户端时长期记忆能力整体关闭，见 [com.tencent.bkrepo.agent.config.AgentMemoryFilesystemConfiguration]。 */
    fun isAvailable(): Boolean = store != null

    /** 列出某用户已保存的全部记忆文件（`MEMORY.md` 若存在 + `memory/` 下所有日志文件），按路径排序。 */
    fun listFiles(runtimeContext: RuntimeContext): List<MemoryFileEntry> {
        val activeStore = store ?: return emptyList()
        val result = mutableListOf<MemoryFileEntry>()
        if (rootFs(activeStore).exists(runtimeContext, ROOT_KEY)) {
            result.add(MemoryFileEntry(MEMORY_MD, null))
        }
        val ls = memoryFs(activeStore).ls(runtimeContext, "/")
        if (ls.isSuccess) {
            ls.entries().orEmpty().filter { !it.isDirectory }.forEach { info ->
                val name = info.path().removePrefix("/")
                val modifiedAt = info.modifiedAt().takeIf { it.isNotBlank() }
                result.add(MemoryFileEntry("$MEMORY_DIR/$name", modifiedAt))
            }
        }
        return result.sortedBy { it.path }
    }

    /** 读取某个记忆文件的完整内容；文件不存在或路径不合法（既不是 `MEMORY.md` 也不在 `memory/` 下）时返回 `null`。 */
    fun readFile(runtimeContext: RuntimeContext, path: String): String? {
        val activeStore = store ?: return null
        val (fs, key) = route(activeStore, path) ?: return null
        val result = fs.read(runtimeContext, key, 0, 0)
        if (!result.isSuccess) {
            return null
        }
        return result.fileData()?.content().orEmpty()
    }

    /**
     * 删除单个记忆文件。[RemoteFilesystem.delete] 是幂等操作（文件本就不存在也算成功），因此返回值只在
     * 路径不合法（既不是 `MEMORY.md` 也不在 `memory/` 下）时为 `false`。
     */
    fun deleteFile(runtimeContext: RuntimeContext, path: String): Boolean {
        val activeStore = store ?: return false
        val (fs, key) = route(activeStore, path) ?: return false
        return fs.delete(runtimeContext, key).isSuccess
    }

    /** 清空某用户的全部长期记忆（`MEMORY.md` + `memory/` 下所有文件）。 */
    fun deleteAll(runtimeContext: RuntimeContext) {
        val activeStore = store ?: return
        rootFs(activeStore).delete(runtimeContext, ROOT_KEY)
        val memoryFs = memoryFs(activeStore)
        val ls = memoryFs.ls(runtimeContext, "/")
        if (ls.isSuccess) {
            ls.entries().orEmpty().filter { !it.isDirectory }.forEach { memoryFs.delete(runtimeContext, it.path()) }
        }
    }

    /**
     * 按 1-based 闭区间行号删除文件中的若干行，供 `memory_delete` 工具使用（寻址方式与框架内置的
     * `memory_get`——[io.agentscope.harness.agent.tool.MemoryGetTool]——完全对齐）。
     *
     * 通过 [RemoteFilesystem.edit] 的 CAS 字符串替换（内部自带最多 5 次版本冲突重试）实现整篇覆盖式更新，
     * 而不是自己实现"读取版本号 -> 拼装新内容 -> CAS 写回"，因为 [RemoteFilesystem] 没有对外暴露底层
     * [io.agentscope.harness.agent.filesystem.remote.store.BaseStore] 或版本号，这条能力只能通过
     * `edit` 间接借用。极端情况下如果待删除的这段文本在文件里不是唯一的（比如用户保存过两条一模一样的
     * 记忆），`edit` 的精确字符串匹配可能失败——此时把错误原样返回给 LLM，由它调整行号范围重试。
     */
    fun deleteLines(runtimeContext: RuntimeContext, path: String, startLine: Int, endLine: Int): DeleteLinesResult {
        val activeStore = store ?: return DeleteLinesResult.Failure("长期记忆未启用")
        val (fs, key) = route(activeStore, path) ?: return DeleteLinesResult.Failure("不支持的记忆文件路径: $path")
        val readResult = fs.read(runtimeContext, key, 0, 0)
        val content = if (readResult.isSuccess) readResult.fileData()?.content() else null
        if (content.isNullOrEmpty()) {
            return DeleteLinesResult.Failure("文件不存在或为空: $path")
        }
        val lines = content.split("\n")
        val startIdx0 = (startLine - 1).coerceAtLeast(0)
        val endIdxExclusive0 = endLine.coerceAtMost(lines.size)
        if (startIdx0 >= lines.size || startIdx0 >= endIdxExclusive0) {
            return DeleteLinesResult.Failure("行号范围无效: $startLine-$endLine（文件共 ${lines.size} 行）")
        }
        val toDelete = lines.subList(startIdx0, endIdxExclusive0)
        // 连带吃掉相邻的一个换行符，避免删除后留下一个空行；起始为文件首行时改吃后面那个换行符。
        val oldString = if (startIdx0 == 0) {
            toDelete.joinToString("\n") + if (endIdxExclusive0 < lines.size) "\n" else ""
        } else {
            "\n" + toDelete.joinToString("\n")
        }
        val editResult = fs.edit(runtimeContext, key, oldString, "", false)
        return if (editResult.isSuccess) {
            DeleteLinesResult.Success(toDelete.size)
        } else {
            DeleteLinesResult.Failure(editResult.error() ?: "编辑冲突，请重试")
        }
    }

    private fun route(activeStore: BaseStore, path: String): Pair<RemoteFilesystem, String>? {
        val normalized = path.trim().removePrefix("/")
        return when {
            normalized == MEMORY_MD -> rootFs(activeStore) to ROOT_KEY
            normalized.startsWith("$MEMORY_DIR/") -> {
                val suffix = normalized.removePrefix("$MEMORY_DIR/")
                if (suffix.isBlank()) null else memoryFs(activeStore) to "/$suffix"
            }
            else -> null
        }
    }

    private fun rootFs(activeStore: BaseStore): RemoteFilesystem =
        RemoteFilesystem(activeStore, namespaceFactory(ROOT_SEGMENT))

    private fun memoryFs(activeStore: BaseStore): RemoteFilesystem =
        RemoteFilesystem(activeStore, namespaceFactory(MEMORY_DIR))

    private fun namespaceFactory(segment: String): NamespaceFactory = NamespaceFactory { rc ->
        val uid = rc?.userId?.takeIf { it.isNotBlank() } ?: ANONYMOUS_USER_ID
        listOf("agents", agentId, "users", uid, segment)
    }

    companion object {
        const val MEMORY_MD = "MEMORY.md"
        const val MEMORY_DIR = "memory"
        private const val ROOT_SEGMENT = "root"
        private const val ROOT_KEY = "/MEMORY.md"

        // 与框架 RemoteFilesystemSpec 默认的 anonymousUserId 保持一致，理论上不会触发（REST/工具调用
        // 场景下 userId 总是来自已登录用户），保留只是为了行为对齐。
        private const val ANONYMOUS_USER_ID = "_default"
    }
}

/** [MemoryFilesystemAccess.listFiles] 的返回项：记忆文件相对路径 + 最后修改时间（可能未知）。 */
data class MemoryFileEntry(val path: String, val modifiedAt: String?)

/** [MemoryFilesystemAccess.deleteLines] 的结果。 */
sealed class DeleteLinesResult {
    data class Success(val deletedLineCount: Int) : DeleteLinesResult()
    data class Failure(val message: String) : DeleteLinesResult()
}
