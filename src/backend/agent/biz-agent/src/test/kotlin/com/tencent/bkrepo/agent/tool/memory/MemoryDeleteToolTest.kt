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

import com.tencent.bkrepo.agent.memory.FakeBaseStore
import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MemoryDeleteTool单测")
class MemoryDeleteToolTest {

    private val agentId = "test-agent"
    private val store = FakeBaseStore()
    private val access = MemoryFilesystemAccess(store, agentId)
    private val tool = MemoryDeleteTool(access)
    private val rc = RuntimeContext.builder().userId("alice").build()

    @Test
    fun `path为空白时直接返回错误不触碰存储`() {
        val result = tool.memoryDelete(rc, "  ", 1, 1)

        assertEquals("Error: path is required", result)
    }

    @Test
    fun `runtimeContext为null时退化为empty上下文而不抛异常`() {
        RemoteFilesystem(store, listOf("agents", agentId, "users", "_default", "root"))
            .write(RuntimeContext.empty(), "/MEMORY.md", "line1\nline2")

        val result = tool.memoryDelete(null, "MEMORY.md", 1, 1)

        assertEquals("Deleted 1 line(s) from MEMORY.md", result)
    }

    @Test
    fun `删除成功时返回包含删除行数与路径的提示`() {
        RemoteFilesystem(store, listOf("agents", agentId, "users", "alice", "root"))
            .write(rc, "/MEMORY.md", "line1\nline2\nline3")

        val result = tool.memoryDelete(rc, "MEMORY.md", 2, 3)

        assertEquals("Deleted 2 line(s) from MEMORY.md", result)
        assertEquals("line1", access.readFile(rc, "MEMORY.md"))
    }

    @Test
    fun `文件不存在时返回Error前缀的错误信息`() {
        val result = tool.memoryDelete(rc, "MEMORY.md", 1, 1)

        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `不支持的路径返回Error前缀的错误信息`() {
        val result = tool.memoryDelete(rc, "not-memory.txt", 1, 1)

        assertTrue(result.startsWith("Error:"))
    }
}
