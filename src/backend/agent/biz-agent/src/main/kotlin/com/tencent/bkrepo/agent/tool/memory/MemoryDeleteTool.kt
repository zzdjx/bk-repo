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

package com.tencent.bkrepo.agent.tool.memory

import com.tencent.bkrepo.agent.memory.DeleteLinesResult
import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam

/**
 * 框架未提供长期记忆的删除工具（只有 `MemorySaveTool`/`MemorySearchTool`/`MemoryGetTool`），这里补一个。
 *
 * 寻址方式与框架内置的 `memory_get`（`io.agentscope.harness.agent.tool.MemoryGetTool`）完全对齐：LLM
 * 需要先用 `memory_search`/`memory_get` 定位到具体行号，再调用本工具精确删除那几行，而不是提供整篇文件
 * 级别的粗粒度删除——这样即使用户只想撤回某一条记忆，也不会连带清掉同一个文件里其它无关的记忆。
 *
 * 通过 [MemoryFilesystemAccess] 直接操作底层 Redis 存储（原理与命名空间对齐规则见该类 kdoc），不经过
 * 框架内部私有的 `WorkspaceManager`——`memory_save`/`memory_get`/`memory_search` 用的那个实例没有公开
 * 的注入点可以复用。
 *
 * 是一个普通 POJO（不是 `ToolBase` 子类），通过
 * `io.agentscope.core.tool.Toolkit#registerTool(Object)` 反射扫描 `@Tool` 方法注册，与框架自己的
 * `MemorySaveTool`/`MemoryGetTool` 注册方式完全一致；只在 Redis 可用（[MemoryFilesystemAccess.isAvailable]）
 * 时由 [com.tencent.bkrepo.agent.config.AgentHarnessConfigurer] 注册进协调者 toolkit——与 `memory_save`
 * 等工具的可见性开关保持同步。权限侧接入
 * [com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration] 的 ASK 规则，与 `memory_save`
 * 同构，删除前需要用户在前端弹窗确认。
 */
class MemoryDeleteTool(private val memoryFilesystemAccess: MemoryFilesystemAccess) {

    @Tool(
        name = "memory_delete",
        description =
            "Delete specific lines from a long-term memory file (MEMORY.md or memory/<date>.md). " +
                "Use memory_search or memory_get first to find the exact line numbers of the fact you " +
                "want to remove, then call this tool with that path and line range. Use this when the " +
                "user asks you to forget something, or when a previously saved fact turns out to be " +
                "outdated or incorrect.",
    )
    fun memoryDelete(
        runtimeContext: RuntimeContext?,
        @ToolParam(
            name = "path",
            description = "Relative path to the memory file (e.g., MEMORY.md or memory/2026-04-01.md)",
        )
        path: String,
        @ToolParam(name = "startLine", description = "Start line number (1-based, inclusive)")
        startLine: Int,
        @ToolParam(name = "endLine", description = "End line number (1-based, inclusive)")
        endLine: Int,
    ): String {
        if (path.isBlank()) {
            return "Error: path is required"
        }
        val rc = runtimeContext ?: RuntimeContext.empty()
        return when (val result = memoryFilesystemAccess.deleteLines(rc, path, startLine, endLine)) {
            is DeleteLinesResult.Success -> "Deleted ${result.deletedLineCount} line(s) from $path"
            is DeleteLinesResult.Failure -> "Error: ${result.message}"
        }
    }
}
