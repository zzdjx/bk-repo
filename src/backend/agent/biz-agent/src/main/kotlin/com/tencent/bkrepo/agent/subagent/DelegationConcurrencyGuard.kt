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

package com.tencent.bkrepo.agent.subagent

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内、按 session 统计的"当前正在同步执行的委派调用数"（`agent_spawn`/`agent_send` 走
 * `timeout_seconds > 0` 同步路径、尚未返回的调用）。
 *
 * ## 为什么进程内计数就够用，不需要跨副本（Redis）
 * [com.tencent.bkrepo.agent.runtime.ActiveRunManager] 已经保证同一个 (userId, threadId) 在任意
 * 时刻只有一个副本持有其 run 锁，所以某个 session 的同步委派调用只可能发生在"当前持锁"的那一个副本
 * 进程内——本地内存计数天然准确，不像后台任务（[com.tencent.bkrepo.agent.task
 * .LimitedWorkspaceTaskRepository] 覆盖的那部分）那样需要跨副本共享状态。
 *
 * ## 用途
 * - [DelegationBudgetMiddleware.onActing] 在批量执行 `agent_spawn`/`agent_send` 前累加计数、执行
 *   完成后（无论成功/失败/取消）递减；
 * - [DelegationBudgetMiddleware.onReasoning] 与 [LimitedWorkspaceTaskRepository] 都会读取当前计数，
 *   与"未终态的后台任务数"相加，得到该 session 当前的总活跃委派数。
 *
 * 计数只做尽力而为的收敛（见 [release] 的实现说明），不追求绝对精确——这是一个预算提醒/软限流的
 * 辅助信号，不是需要强一致性的计费或配额系统。
 */
@Component
class DelegationConcurrencyGuard {

    private val inFlightBySession = ConcurrentHashMap<String, AtomicInteger>()

    fun acquire(sessionId: String, count: Int) {
        if (count <= 0) return
        inFlightBySession.computeIfAbsent(sessionId) { AtomicInteger(0) }.addAndGet(count)
    }

    /**
     * 递减计数。故意不在计数归零时把 entry 从 map 里移除——并发场景下"读取旧 AtomicInteger 引用 ->
     * 判断归零 -> 删除"这几步之间可能被另一次并发 [acquire] 抢先，删除会把那次 acquire 的结果一并
     * 丢弃。留着值为 0 的 entry 没有实际代价（session 数量有限，AtomicInteger 本身很小），比引入一次
     * 容易出错的收缩逻辑更划算。
     */
    fun release(sessionId: String, count: Int) {
        if (count <= 0) return
        inFlightBySession[sessionId]?.addAndGet(-count)
    }

    fun inFlight(sessionId: String): Int = inFlightBySession[sessionId]?.get()?.coerceAtLeast(0) ?: 0
}
