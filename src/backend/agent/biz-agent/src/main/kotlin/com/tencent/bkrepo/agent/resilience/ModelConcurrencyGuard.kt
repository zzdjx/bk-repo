/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resilience

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 模型调用并发上限：全局一个维度、按用户一个维度，两个维度分别检查，任一维度打满就拒绝。
 *
 * 与 [com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard] 同样是进程内、非跨副本的
 * 尽力而为限流，不追求精确全局配额——原因也一样：这是一个"防单副本被打爆"的本地保护阀，不是计费或
 * 配额系统，跨副本强一致需要 Redis 原子操作，为这个目的引入额外的分布式协调不划算。
 *
 * 用 [Semaphore] 而不是仿照 [com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard] 手写
 * `AtomicInteger`：这里的"超过上限就拒绝"正是 `Semaphore.tryAcquire()` 的原生语义（原子的
 * check-and-increment），手写等价逻辑只是重新发明它。
 *
 * 每维度上限 ≤ 0 表示不限制该维度，此时**不创建** `Semaphore`（用 null 表示跳过），既省一次对象分配，
 * 也避免"给一个不该存在的限制发放/回收许可"这种要求 [Permit.release] 与 null 检查处处对齐的心智负担。
 */
class ModelConcurrencyGuard(
    maxGlobal: Int,
    private val maxPerUser: Int,
) {

    /** 持有期间代表"占了一个模型调用并发名额"，调用方必须在模型调用结束（成功/失败/取消）后调用 [release]，恰好一次。 */
    class Permit internal constructor(
        private val global: Semaphore?,
        private val user: Semaphore?,
    ) {
        fun release() {
            user?.release()
            global?.release()
        }
    }

    private val global: Semaphore? = maxGlobal.takeIf { it > 0 }?.let { Semaphore(it) }
    private val perUser = ConcurrentHashMap<String, Semaphore>()

    /**
     * @return 拿到名额则返回 [Permit]，否则返回 null（调用方应拒绝这次模型调用，不阻塞等待）。
     *
     * 先占全局名额、再占用户名额：用户维度失败时会把刚占到的全局名额还回去，保证两个维度的占用总是
     * 成对出现、不会泄漏。顺序本身不影响正确性，先占开销更小的维度（全局只有一个 Semaphore 实例，
     * 用户维度还要走一次 `computeIfAbsent`）更符合"快路径优先"的直觉。
     */
    fun tryAcquire(userId: String?): Permit? {
        val globalPermit = global?.let { if (it.tryAcquire()) it else return null }
        val userSemaphore = userSemaphoreFor(userId)
        if (userSemaphore != null && !userSemaphore.tryAcquire()) {
            globalPermit?.release()
            return null
        }
        return Permit(globalPermit, userSemaphore)
    }

    private fun userSemaphoreFor(userId: String?): Semaphore? {
        if (maxPerUser <= 0 || userId.isNullOrBlank()) return null
        return perUser.computeIfAbsent(userId) { Semaphore(maxPerUser) }
    }
}
