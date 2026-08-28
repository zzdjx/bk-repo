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

package com.tencent.bkrepo.agent.service.impl

import com.tencent.bkrepo.agent.memory.FakeBaseStore
import com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.exception.NotFoundException
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AgentMemoryServiceImpl单测")
class AgentMemoryServiceImplTest {

    private val agentId = "test-agent"
    private val store = FakeBaseStore()
    private val service = AgentMemoryServiceImpl(MemoryFilesystemAccess(store, agentId))

    private fun seedMemoryMd(userId: String, content: String) {
        RemoteFilesystem(store, listOf("agents", agentId, "users", userId, "root"))
            .write(RuntimeContext.builder().userId(userId).build(), "/MEMORY.md", content)
    }

    @Test
    fun `未启用长期记忆时所有操作抛出NotFoundException`() {
        val unavailableService = AgentMemoryServiceImpl(MemoryFilesystemAccess(null, agentId))

        assertThrows(NotFoundException::class.java) { unavailableService.listFiles("alice") }
        assertThrows(NotFoundException::class.java) { unavailableService.readFile("alice", "MEMORY.md") }
        assertThrows(NotFoundException::class.java) { unavailableService.deleteFile("alice", "MEMORY.md") }
        assertThrows(NotFoundException::class.java) { unavailableService.clearAll("alice") }
    }

    @Test
    fun `listFiles返回该用户已保存的记忆文件`() {
        seedMemoryMd("alice", "hello")

        val files = service.listFiles("alice")

        assertEquals(listOf("MEMORY.md"), files.map { it.path })
    }

    @Test
    fun `readFile返回文件内容`() {
        seedMemoryMd("alice", "hello memory")

        assertEquals("hello memory", service.readFile("alice", "MEMORY.md"))
    }

    @Test
    fun `readFile文件不存在时抛出NotFoundException`() {
        assertThrows(NotFoundException::class.java) { service.readFile("alice", "MEMORY.md") }
    }

    @Test
    fun `path不合法时抛出参数校验异常`() {
        assertThrows(ErrorCodeException::class.java) { service.readFile("alice", "../etc/passwd") }
        assertThrows(ErrorCodeException::class.java) { service.deleteFile("alice", "not-memory.txt") }
    }

    @Test
    fun `deleteFile删除后无法再读取到该文件`() {
        seedMemoryMd("alice", "hello")

        assertTrue(service.deleteFile("alice", "MEMORY.md"))
        assertThrows(NotFoundException::class.java) { service.readFile("alice", "MEMORY.md") }
    }

    @Test
    fun `clearAll后该用户记忆文件列表为空`() {
        seedMemoryMd("alice", "hello")

        service.clearAll("alice")

        assertTrue(service.listFiles("alice").isEmpty())
    }

    @Test
    fun `不同用户互不影响`() {
        seedMemoryMd("alice", "alice content")
        seedMemoryMd("bob", "bob content")

        service.clearAll("alice")

        assertTrue(service.listFiles("alice").isEmpty())
        assertEquals("bob content", service.readFile("bob", "MEMORY.md"))
    }
}
