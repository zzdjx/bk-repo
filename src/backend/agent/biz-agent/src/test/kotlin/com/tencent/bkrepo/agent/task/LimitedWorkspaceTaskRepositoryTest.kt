/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.task

import com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem
import io.agentscope.harness.agent.subagent.task.TaskRunSpec
import io.agentscope.harness.agent.subagent.task.TaskStatus
import io.agentscope.harness.agent.workspace.WorkspaceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * 覆盖 [LimitedWorkspaceTaskRepository] 的预算检查逻辑：未超限时行为与父类
 * [io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository] 完全一致（真实提交、真实执行），
 * 超限时不调用 `super.putTask(...)`（真正的子 Agent 执行体不会被跑到）。
 */
class LimitedWorkspaceTaskRepositoryTest {

    private fun newRepository(
        workspace: Path,
        maxParallelDelegations: Int,
        guard: DelegationConcurrencyGuard = DelegationConcurrencyGuard(),
    ): LimitedWorkspaceTaskRepository {
        val filesystem = LocalFilesystem(workspace)
        val workspaceManager = WorkspaceManager(workspace, filesystem, null, null)
        return LimitedWorkspaceTaskRepository(workspaceManager, "test-parent-agent", maxParallelDelegations, guard)
    }

    @Test
    fun `未超预算时任务正常提交并执行完成`(@TempDir workspace: Path) {
        val repo = newRepository(workspace, maxParallelDelegations = 2)

        val task = repo.putTask(
            RuntimeContext.empty(),
            "task-1",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(Supplier { "ok" }),
        )

        assertTrue(task.waitForCompletion(5_000))
        assertEquals(TaskStatus.COMPLETED, task.taskStatus)
        assertEquals("ok", task.result)
    }

    @Test
    fun `达到并发预算时新任务被直接拒绝且不会真正执行`(@TempDir workspace: Path) {
        val repo = newRepository(workspace, maxParallelDelegations = 1)
        val blockingLatch = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)

        val first = repo.putTask(
            RuntimeContext.empty(),
            "task-1",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(
                Supplier {
                    firstStarted.countDown()
                    blockingLatch.await()
                    "first-done"
                },
            ),
        )

        val secondExecuted = AtomicBoolean(false)
        val second = repo.putTask(
            RuntimeContext.empty(),
            "task-2",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(
                Supplier {
                    secondExecuted.set(true)
                    "second-done"
                },
            ),
        )

        assertTrue(second.waitForCompletion(5_000))
        assertEquals(TaskStatus.FAILED, second.taskStatus)
        assertTrue(second.error?.message?.contains("Max parallel delegations") == true)
        assertFalse(secondExecuted.get()) { "超出预算的任务不应该被真正执行" }

        blockingLatch.countDown()
        assertTrue(first.waitForCompletion(5_000))
        assertEquals(TaskStatus.COMPLETED, first.taskStatus)
    }

    @Test
    fun `同步在跑的委派数也计入预算`(@TempDir workspace: Path) {
        val guard = DelegationConcurrencyGuard()
        guard.acquire("session-1", 1)
        val repo = newRepository(workspace, maxParallelDelegations = 1, guard = guard)

        val executed = AtomicBoolean(false)
        val task = repo.putTask(
            RuntimeContext.empty(),
            "task-1",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(
                Supplier {
                    executed.set(true)
                    "done"
                },
            ),
        )

        assertTrue(task.waitForCompletion(5_000))
        assertEquals(TaskStatus.FAILED, task.taskStatus)
        assertFalse(executed.get())
    }

    @Test
    fun `旧任务结束释放名额后新任务可以正常提交`(@TempDir workspace: Path) {
        val repo = newRepository(workspace, maxParallelDelegations = 1)

        val first = repo.putTask(
            RuntimeContext.empty(),
            "task-1",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(Supplier { "first-done" }),
        )
        assertTrue(first.waitForCompletion(5_000))
        assertEquals(TaskStatus.COMPLETED, first.taskStatus)

        val second = repo.putTask(
            RuntimeContext.empty(),
            "task-2",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(Supplier { "second-done" }),
        )
        assertTrue(second.waitForCompletion(5_000))
        assertEquals(TaskStatus.COMPLETED, second.taskStatus)
        assertEquals("second-done", second.result)
    }

    @Test
    fun `不同session的预算互不影响`(@TempDir workspace: Path) {
        val repo = newRepository(workspace, maxParallelDelegations = 1)
        val blockingLatch = CountDownLatch(1)

        val sessionOneTask = repo.putTask(
            RuntimeContext.empty(),
            "task-1",
            "discovery",
            "session-1",
            TaskRunSpec.LocalTaskRunSpec(
                Supplier {
                    blockingLatch.await()
                    "session-1-done"
                },
            ),
        )

        val sessionTwoTask = repo.putTask(
            RuntimeContext.empty(),
            "task-2",
            "discovery",
            "session-2",
            TaskRunSpec.LocalTaskRunSpec(Supplier { "session-2-done" }),
        )

        assertTrue(sessionTwoTask.waitForCompletion(5_000))
        assertEquals(TaskStatus.COMPLETED, sessionTwoTask.taskStatus)

        blockingLatch.countDown()
        assertTrue(sessionOneTask.waitForCompletion(5_000))
        assertEquals(TaskStatus.COMPLETED, sessionOneTask.taskStatus)
    }
}
