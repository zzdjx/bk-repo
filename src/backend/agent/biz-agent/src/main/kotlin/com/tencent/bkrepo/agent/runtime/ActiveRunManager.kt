/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.runtime.store.ActiveRunStateStore
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.state.AgentStateStore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行态唯一入口：共享的运行权/活跃 run/停止信号 + 本机 handle + AgentState 清理。
 */
class ActiveRunManager(
    private val stateStore: ActiveRunStateStore,
    private val agentStateStore: AgentStateStore,
) {

    data class LocalHandle(
        val userId: String,
        val threadId: String,
        val runId: String,
        val runtimeContext: RuntimeContext,
        val abort: (AgentRunAbortReason) -> Unit,
    )

    private val localHandles = ConcurrentHashMap<String, LocalHandle>()

    fun tryAcquire(key: ActiveRunKey): Boolean {
        return stateStore.tryAcquireLock(key.userId, key.threadId)
    }

    fun isRunning(key: ActiveRunKey): Boolean {
        return stateStore.isLockHeld(key.userId, key.threadId)
    }

    fun bindActiveRun(key: ActiveRunKey, runId: String) {
        stateStore.bindActiveRun(key.userId, key.threadId, runId)
    }

    fun getActiveRunId(key: ActiveRunKey): String? {
        return stateStore.getActiveRunId(key.userId, key.threadId)
    }

    /** 释放运行权；带 runId 条件由存储层保证，避免误删后一个 run 刚抢到的锁。 */
    fun releaseRun(key: ActiveRunKey, runId: String) {
        stateStore.releaseRun(key.userId, key.threadId, runId)
    }

    /**
     * 请求停止 [runId]，两条通道各管一边：
     *
     * 共享存储上的停止标志是跨副本的唯一手段——真正在跑这个 run 的副本会轮询到它（见
     * [com.tencent.bkrepo.agent.service.run.AgentRunScope.shouldPollStopFlag]）。而如果 run 就在本副本，
     * [abortLocalHandle] 同步中止，用户感知不到延迟，不必等轮询。
     *
     * run 还没 [registerHandle] 就被停的窗口不需要单独兜底：[registerHandle] 自己会查一次停止标志，
     * 而标志在这里已经先写了。
     *
     * [runId] 来自已过期的运行权（调用方从 run 记录兜底取到的）时，停止标志会落空——没有活文档可写。
     * 这是对的：运行权过期说明续期已经停了，持有它的副本要么已经没了，要么下一轮心跳就会因
     * [AgentRunAbortReason.LOCK_LOST] 自行中止；跨副本通知一个不存在的听众没有意义。真在本副本的话，
     * [abortLocalHandle] 这一条仍然生效。
     */
    fun requestStop(key: ActiveRunKey, runId: String) {
        stateStore.requestStop(key.userId, key.threadId, runId)
        abortLocalHandle(key, runId)
    }

    fun isStopRequested(key: ActiveRunKey, runId: String): Boolean {
        return stateStore.isStopRequested(key.userId, key.threadId, runId)
    }

    fun registerHandle(
        key: ActiveRunKey,
        runId: String,
        runtimeContext: RuntimeContext,
        abort: (AgentRunAbortReason) -> Unit,
    ) {
        localHandles[localKey(key)] = LocalHandle(
            userId = key.userId,
            threadId = key.threadId,
            runId = runId,
            runtimeContext = runtimeContext,
            abort = abort,
        )
        // 兜住"停止请求比 handle 注册先到"：此时 requestStop 找不到本地 handle，只留下了存储上的标志。
        if (stateStore.isStopRequested(key.userId, key.threadId, runId)) {
            abort(AgentRunAbortReason.USER_STOP)
        }
    }

    /**
     * 中止本副本所有在跑的 run，返回实际中止的条数；供停机流程调用（见
     * [com.tencent.bkrepo.agent.runtime.AgentRunShutdownHandler]）。
     *
     * 只处理本进程的 [localHandles]，不去扫共享存储：其它副本的 run 由它们各自停机时处理，跨副本代劳
     * 会把还在正常服务的 run 也一起杀掉。每个 handle 的 abort 回调走的是与用户停止完全相同的收尾路径
     * （中断 Agent、落 CANCELLED 终态、关事件流、释放会话锁、complete SSE），所以这里不需要重复清理。
     */
    fun abortLocalRuns(reason: AgentRunAbortReason): Int {
        var aborted = 0
        localHandles.values.toList().forEach { handle ->
            try {
                handle.abort(reason)
                aborted++
            } catch (exception: Exception) {
                logger.warn(
                    "failed to abort run[${handle.runId}] for user[${handle.userId}] " +
                        "thread[${handle.threadId}] on $reason",
                    exception,
                )
            }
        }
        return aborted
    }

    /**
     * 给本副本所有在跑的 run 续上运行权，返回续期成功的条数；由 [ActiveRunLockHeartbeat] 周期调用。
     *
     * 运行权有固定 TTL，没有续期就意味着"run 必须在 TTL 内结束"，而一个 run 的时长取决于模型和工具，
     * 不受我们控制。锁先于 run 过期的后果不是慢，而是同一会话上能并发起第二个 run，两个 run 交替写
     * 同一份 AgentState。
     *
     * 发现归属已经不在（[ActiveRunStateStore.renewLock] 返回 false）时中止本地这个 run：此时会话已经
     * 被别人接管，继续跑只会污染状态。中止走的是与用户停止同一条收尾路径，其中的 [releaseRun] 带 runId
     * 条件，因此不会误删接管方的锁。
     */
    fun renewLocalRunLocks(): Int {
        var renewed = 0
        localHandles.values.toList().forEach { handle ->
            try {
                if (stateStore.renewLock(handle.userId, handle.threadId, handle.runId)) {
                    renewed++
                } else {
                    logger.warn(
                        "run[${handle.runId}] lost its session lock for user[${handle.userId}] " +
                            "thread[${handle.threadId}], aborting local run",
                    )
                    handle.abort(AgentRunAbortReason.LOCK_LOST)
                }
            } catch (exception: Exception) {
                // 续期失败大多是存储抖动，下一轮还会再试；这里不能把 run 直接中止掉。
                logger.warn("failed to renew session lock for run[${handle.runId}]", exception)
            }
        }
        return renewed
    }

    fun removeHandle(key: ActiveRunKey) {
        localHandles.remove(localKey(key))
    }

    /** 无条件清除会话运行态：删除会话时收尾，以及 run 还没起来就失败时把刚抢到的运行权还回去。 */
    fun clearRunState(key: ActiveRunKey) {
        stateStore.clear(key.userId, key.threadId)
    }

    fun clearAgentRuntimeState(userId: String, threadId: String) {
        try {
            agentStateStore.delete(userId, threadId)
        } catch (exception: Exception) {
            logger.warn("failed to clear agent runtime state for user[$userId] thread[$threadId]", exception)
        }
    }

    private fun abortLocalHandle(key: ActiveRunKey, runId: String) {
        localHandles[localKey(key)]
            ?.takeIf { it.runId == runId }
            ?.abort?.invoke(AgentRunAbortReason.USER_STOP)
    }

    private fun localKey(key: ActiveRunKey): String = "${key.userId}:${key.threadId}"

    companion object {
        private val logger = LoggerFactory.getLogger(ActiveRunManager::class.java)
    }
}
