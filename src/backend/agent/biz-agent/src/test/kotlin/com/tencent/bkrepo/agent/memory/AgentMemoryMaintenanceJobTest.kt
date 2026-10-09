/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
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

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.task.MongoStore
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Duration
import java.time.LocalDate

/**
 * [AgentMemoryMaintenanceJob] 单测。
 *
 * 纯函数（[AgentMemoryMaintenanceJob.dailyLedgerCutoffDate]/[AgentMemoryMaintenanceJob.parseDailyLedgerDate]/
 * [AgentMemoryMaintenanceJob.extractUserId]/[AgentMemoryMaintenanceJob.namespacePattern]）直接调用验证；
 * [AgentMemoryMaintenanceJob.cleanupUser] 用 [FakeBaseStore] 驱动的 [MemoryFilesystemAccess] 验证真实
 * 删除行为。反查所有用户这一步（[com.tencent.bkrepo.agent.task.MongoStore.scanNamespaces]）不在这里
 * 覆盖——它是纯存储交互细节，已经在 `MongoStoreTest` 里独立验证过，这里只需要保证拿到 userId
 * 列表之后的清理逻辑是对的（与 [com.tencent.bkrepo.agent.runtime.ActiveRunLockHeartbeatTest] 同样只测
 * 调度器包装的纯逻辑、不测 `@PostConstruct`/`ScheduledExecutorService` 本身的做法一致）。
 */
@DisplayName("AgentMemoryMaintenanceJob单测")
class AgentMemoryMaintenanceJobTest {

    private val agentId = "test-agent"
    private val store = FakeBaseStore()
    private val memoryFilesystemAccess = MemoryFilesystemAccess(store, agentId)
    private val job = AgentMemoryMaintenanceJob(
        memoryFilesystemAccess,
        mock<MongoStore>(),
        EffectiveAgentRuntimeProperties.defaults(),
    )

    private val rcAlice = RuntimeContext.builder().userId("alice").build()

    private fun agentSideMemoryFs(uid: String) =
        RemoteFilesystem(store, listOf("agents", agentId, "users", uid, "memory"))

    private fun agentSideRootFs(uid: String) = RemoteFilesystem(store, listOf("agents", agentId, "users", uid, "root"))

    @Test
    fun `dailyLedgerCutoffDate在保留期为0或负值时返回LocalDate_MIN表示永不清理`() {
        val now = LocalDate.of(2026, 4, 1)
        assertEquals(LocalDate.MIN, AgentMemoryMaintenanceJob.dailyLedgerCutoffDate(now, Duration.ZERO))
        assertEquals(LocalDate.MIN, AgentMemoryMaintenanceJob.dailyLedgerCutoffDate(now, Duration.ofDays(-1)))
    }

    @Test
    fun `dailyLedgerCutoffDate按保留天数往前推`() {
        val now = LocalDate.of(2026, 4, 1)
        assertEquals(
            LocalDate.of(2026, 1, 1),
            AgentMemoryMaintenanceJob.dailyLedgerCutoffDate(now, Duration.ofDays(90)),
        )
    }

    @Test
    fun `dailyLedgerCutoffDate在保留期不足一天时按一天计算`() {
        val now = LocalDate.of(2026, 4, 1)
        assertEquals(
            LocalDate.of(2026, 3, 31),
            AgentMemoryMaintenanceJob.dailyLedgerCutoffDate(now, Duration.ofHours(12)),
        )
    }

    @Test
    fun `parseDailyLedgerDate能解析合法的daily ledger路径`() {
        assertEquals(LocalDate.of(2026, 1, 1), AgentMemoryMaintenanceJob.parseDailyLedgerDate("memory/2026-01-01.md"))
    }

    @Test
    fun `parseDailyLedgerDate对MEMORY_md或不合法日期返回null`() {
        assertNull(AgentMemoryMaintenanceJob.parseDailyLedgerDate("MEMORY.md"))
        assertNull(AgentMemoryMaintenanceJob.parseDailyLedgerDate("memory/not-a-date.md"))
        assertNull(AgentMemoryMaintenanceJob.parseDailyLedgerDate("memory/2026-13-01.md"))
    }

    @Test
    fun `extractUserId能从合法命名空间中取出userId段`() {
        assertEquals(
            "alice",
            AgentMemoryMaintenanceJob.extractUserId("agents\u0000coordinator\u0000users\u0000alice\u0000memory"),
        )
    }

    @Test
    fun `extractUserId对段数不足的命名空间返回null`() {
        assertNull(AgentMemoryMaintenanceJob.extractUserId("agents\u0000coordinator"))
    }

    @Test
    fun `namespacePattern按固定段拼出users下memory段的通配模式`() {
        assertEquals(
            "agents\u0000coordinator\u0000users\u0000*\u0000memory",
            AgentMemoryMaintenanceJob.namespacePattern("coordinator"),
        )
    }

    @Test
    fun `cleanupUser只删除早于cutoff的daily ledger文件`() {
        agentSideMemoryFs("alice").write(rcAlice, "/2025-12-01.md", "old")
        agentSideMemoryFs("alice").write(rcAlice, "/2026-03-31.md", "recent")
        agentSideRootFs("alice").write(rcAlice, "/MEMORY.md", "should never be touched")

        val deleted = job.cleanupUser("alice", LocalDate.of(2026, 1, 1))

        assertEquals(1, deleted)
        val remaining = memoryFilesystemAccess.listFiles(rcAlice).map { it.path }
        assertEquals(listOf("MEMORY.md", "memory/2026-03-31.md"), remaining)
    }

    @Test
    fun `cleanupUser在没有超期文件时不删除任何内容并返回0`() {
        agentSideMemoryFs("alice").write(rcAlice, "/2026-03-31.md", "recent")

        val deleted = job.cleanupUser("alice", LocalDate.of(2026, 1, 1))

        assertEquals(0, deleted)
        assertTrue(memoryFilesystemAccess.readFile(rcAlice, "memory/2026-03-31.md") != null)
    }

    @Test
    fun `cleanupUser不影响其它用户的记忆`() {
        agentSideMemoryFs("alice").write(rcAlice, "/2026-01-01.md", "old")
        val rcBob = RuntimeContext.builder().userId("bob").build()
        agentSideMemoryFs("bob").write(rcBob, "/2026-01-01.md", "old too")

        job.cleanupUser("alice", LocalDate.of(2026, 4, 1))

        assertTrue(memoryFilesystemAccess.listFiles(rcAlice).isEmpty())
        assertEquals(listOf("memory/2026-01-01.md"), memoryFilesystemAccess.listFiles(rcBob).map { it.path })
    }
}
