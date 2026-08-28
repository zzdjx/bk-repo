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

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [MemoryFilesystemAccess] 单测。
 *
 * 数据通过独立构造的 [RemoteFilesystem]（[agentSideRootFs]/[agentSideMemoryFs]）写入同一个
 * [FakeBaseStore]，模拟框架侧 `memory_save`/`memory_get` 走的 `RemoteFilesystemSpec` 命名空间——这样
 * 才能验证 [MemoryFilesystemAccess] 自己复刻的命名空间规则与框架实际使用的命名空间规则一致（这正是这个
 * 类最容易出错、也最需要用测试锁住的地方）。
 */
@DisplayName("MemoryFilesystemAccess单测")
class MemoryFilesystemAccessTest {

    private val agentId = "test-agent"
    private val store = FakeBaseStore()
    private lateinit var access: MemoryFilesystemAccess

    private val rcAlice = RuntimeContext.builder().userId("alice").build()
    private val rcBob = RuntimeContext.builder().userId("bob").build()

    private fun agentSideRootFs(uid: String) = RemoteFilesystem(store, listOf("agents", agentId, "users", uid, "root"))
    private fun agentSideMemoryFs(uid: String) =
        RemoteFilesystem(store, listOf("agents", agentId, "users", uid, "memory"))

    @BeforeEach
    fun setUp() {
        access = MemoryFilesystemAccess(store, agentId)
    }

    @Test
    fun `store为空时isAvailable返回false且所有读写操作安全降级`() {
        val unavailable = MemoryFilesystemAccess(null, agentId)

        assertFalse(unavailable.isAvailable())
        assertTrue(unavailable.listFiles(rcAlice).isEmpty())
        assertNull(unavailable.readFile(rcAlice, "MEMORY.md"))
        assertFalse(unavailable.deleteFile(rcAlice, "MEMORY.md"))
        assertTrue(unavailable.deleteLines(rcAlice, "MEMORY.md", 1, 1) is DeleteLinesResult.Failure)
    }

    @Test
    fun `listFiles能读到Agent侧通过RemoteFilesystem写入的MEMORY_md与memory目录文件`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "- fact one\n- fact two")
        agentSideMemoryFs("alice").write(rcAlice, "/2026-08-08.md", "## note")

        val files = access.listFiles(rcAlice).map { it.path }

        assertEquals(listOf("MEMORY.md", "memory/2026-08-08.md"), files)
    }

    @Test
    fun `readFile能读到两种路由下文件的完整内容`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "hello memory")
        agentSideMemoryFs("alice").write(rcAlice, "/2026-08-08.md", "daily note")

        assertEquals("hello memory", access.readFile(rcAlice, "MEMORY.md"))
        assertEquals("daily note", access.readFile(rcAlice, "memory/2026-08-08.md"))
    }

    @Test
    fun `readFile对不支持的路径或不存在的文件返回null`() {
        assertNull(access.readFile(rcAlice, "not-memory.txt"))
        assertNull(access.readFile(rcAlice, "MEMORY.md"))
    }

    @Test
    fun `不同用户之间的记忆按userId隔离互不可见`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "alice only")

        assertEquals("alice only", access.readFile(rcAlice, "MEMORY.md"))
        assertNull(access.readFile(rcBob, "MEMORY.md"))
        assertTrue(access.listFiles(rcBob).isEmpty())
    }

    @Test
    fun `deleteFile删除单个文件后listFiles不再包含它`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "content")
        agentSideMemoryFs("alice").write(rcAlice, "/2026-08-08.md", "content")

        assertTrue(access.deleteFile(rcAlice, "MEMORY.md"))

        assertEquals(listOf("memory/2026-08-08.md"), access.listFiles(rcAlice).map { it.path })
    }

    @Test
    fun `deleteFile对不支持的路径返回false`() {
        assertFalse(access.deleteFile(rcAlice, "not-memory.txt"))
    }

    @Test
    fun `deleteAll清空该用户全部记忆文件`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "content")
        agentSideMemoryFs("alice").write(rcAlice, "/2026-08-08.md", "content")
        agentSideMemoryFs("alice").write(rcAlice, "/2026-08-09.md", "content")

        access.deleteAll(rcAlice)

        assertTrue(access.listFiles(rcAlice).isEmpty())
    }

    @Test
    fun `deleteAll不影响其它用户的记忆`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "alice content")
        agentSideRootFs("bob").write(rcBob, "/MEMORY.md", "bob content")

        access.deleteAll(rcAlice)

        assertNull(access.readFile(rcAlice, "MEMORY.md"))
        assertEquals("bob content", access.readFile(rcBob, "MEMORY.md"))
    }

    @Test
    fun `deleteLines精确删除中间一行且不影响前后内容`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "line1\nline2\nline3\nline4")

        val result = access.deleteLines(rcAlice, "MEMORY.md", 2, 2)

        assertTrue(result is DeleteLinesResult.Success)
        assertEquals(1, (result as DeleteLinesResult.Success).deletedLineCount)
        assertEquals("line1\nline3\nline4", access.readFile(rcAlice, "MEMORY.md"))
    }

    @Test
    fun `deleteLines删除文件首行不留空行`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "line1\nline2\nline3")

        access.deleteLines(rcAlice, "MEMORY.md", 1, 1)

        assertEquals("line2\nline3", access.readFile(rcAlice, "MEMORY.md"))
    }

    @Test
    fun `deleteLines删除文件末行不留空行`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "line1\nline2\nline3")

        access.deleteLines(rcAlice, "MEMORY.md", 3, 3)

        assertEquals("line1\nline2", access.readFile(rcAlice, "MEMORY.md"))
    }

    @Test
    fun `deleteLines删除多行范围`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "line1\nline2\nline3\nline4\nline5")

        val result = access.deleteLines(rcAlice, "MEMORY.md", 2, 4)

        assertTrue(result is DeleteLinesResult.Success)
        assertEquals(3, (result as DeleteLinesResult.Success).deletedLineCount)
        assertEquals("line1\nline5", access.readFile(rcAlice, "MEMORY.md"))
    }

    @Test
    fun `deleteLines文件不存在时返回失败`() {
        assertTrue(access.deleteLines(rcAlice, "MEMORY.md", 1, 1) is DeleteLinesResult.Failure)
    }

    @Test
    fun `deleteLines行号越界时返回失败`() {
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "line1\nline2")

        assertTrue(access.deleteLines(rcAlice, "MEMORY.md", 5, 6) is DeleteLinesResult.Failure)
    }

    @Test
    fun `deleteLines对不支持的路径返回失败`() {
        assertTrue(access.deleteLines(rcAlice, "not-memory.txt", 1, 1) is DeleteLinesResult.Failure)
    }
}
