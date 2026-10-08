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
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 长期记忆定期整理：清理超过保留期的 `memory/YYYY-MM-DD.md` daily ledger 文件。
 *
 * ## 为什么只清 daily ledger，不动 `MEMORY.md` 本身
 *
 * 框架原生的 `MemoryMaintenanceMiddleware` 除了归档 daily 文件，还会另起一次 LLM 调用对 `MEMORY.md` 做
 * 合并/去重/裁剪（`MemoryConsolidator`）。这个能力随
 * [com.tencent.bkrepo.agent.config.AgentHarnessConfigurer] 里的 `disableMemoryHooks()` 被整体关闭
 * （见该处注释）：产品要求"记忆写入需用户明确同意"，同意机制只覆盖 `memory_save` 这条显式工具调用路径，
 * 覆盖不到框架自动 flush/maintenance 这种不经过 Toolkit/PermissionEngine 的后台 LLM 改写。这里不重新
 * 实现 `MemoryConsolidator`——一是"用 LLM 静默改写用户长期记忆"本身需要独立的产品评估（会不会把用户明确
 * 要求保留的内容改丢），二是 daily ledger 清理是确定性、无损的操作（只删过期文件，不改内容），风险和
 * 实现成本都低一个量级，符合"先补最小、最安全的那块缺口"的取舍。`MEMORY.md` 无上限增长（`memory_save`
 * 是追加写入，见其工具描述）的风险因此仍然存在，留给后续评估是否需要、以及需要哪种非 LLM 的兜底策略
 * （比如按大小归档而不是语义合并），不在本类范围内。
 *
 * ## 为什么要反查所有用户
 *
 * 长期记忆按 userId 隔离（[MemoryFilesystemAccess]），常规读写路径都是"调用方已经知道自己是谁"。清理
 * 任务是唯一需要"这个 agent 一共有哪些用户存过 daily ledger"的场景，[MemoryFilesystemAccess] 没有为此
 * 设计索引，因此这里在 [MongoStore.scanNamespaces] 里用 Redis `SCAN` 反查——只在这一条路径上使用，
 * 且是低频（默认每 [EffectiveAgentRuntimeProperties.memoryMaintenanceInterval] 一次，默认 24 小时）
 * 后台任务，不影响正常读写的性能特征。
 *
 * ## 调度方式
 *
 * 和 [com.tencent.bkrepo.agent.runtime.ActiveRunLockHeartbeat] 一样用自己的单线程
 * `ScheduledExecutorService`：本模块没有开 `@EnableScheduling`，没必要为了一个任务引入；单线程足够，
 * 清理只是低频批处理，不需要并发。
 *
 * ## 何时不启动
 *
 * 长期记忆整体关闭（没有 Lettuce Redis 客户端，[MemoryFilesystemAccess.isAvailable] 为 `false`）或
 * [EffectiveAgentRuntimeProperties.memoryDailyLedgerRetention] 配成 `0`/负值（"永不清理"）时，
 * `@PostConstruct` 阶段直接不启动调度，避免空跑。
 */
@Component
class AgentMemoryMaintenanceJob(
    private val memoryFilesystemAccess: MemoryFilesystemAccess,
    private val memoryStore: MongoStore,
    private val properties: EffectiveAgentRuntimeProperties,
) {

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "agent-memory-maintenance").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        if (!memoryFilesystemAccess.isAvailable()) {
            logger.info("long-term memory disabled, memory maintenance job not started")
            return
        }
        val retention = properties.memoryDailyLedgerRetention
        if (retention.isZero || retention.isNegative) {
            logger.info("memory daily ledger retention configured as never-expire, maintenance job not started")
            return
        }
        val interval = properties.memoryMaintenanceInterval
        scheduler.scheduleWithFixedDelay(::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
        logger.info(
            "agent memory maintenance job started: interval={}, dailyLedgerRetention={}",
            interval,
            retention,
        )
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
    }

    /**
     * 调度器只要抛出异常就会静默停掉后续所有执行，所以这里必须兜住一切——包括 Error，否则一次偶发失败
     * 之后清理就永久停了，而且没有任何迹象（参考 [com.tencent.bkrepo.agent.runtime.ActiveRunLockHeartbeat]
     * 同样的做法）。
     */
    private fun runOnce() {
        try {
            val cutoff = dailyLedgerCutoffDate(LocalDate.now(), properties.memoryDailyLedgerRetention)
            val userIds = memoryStore.scanNamespaces(namespacePattern(properties.name))
                .mapNotNull { namespace -> extractUserId(namespace) }
                .distinct()
            var deletedFiles = 0
            userIds.forEach { userId -> deletedFiles += cleanupUser(userId, cutoff) }
            logger.info(
                "agent memory maintenance finished: users={}, deletedDailyLedgerFiles={}",
                userIds.size,
                deletedFiles,
            )
        } catch (throwable: Throwable) {
            logger.error("agent memory maintenance iteration failed", throwable)
        }
    }

    /** 清理单个用户超过 [cutoff]（不含）的 daily ledger 文件，返回实际删除的文件数。 */
    fun cleanupUser(userId: String, cutoff: LocalDate): Int {
        val runtimeContext = RuntimeContext.builder().userId(userId).build()
        var deleted = 0
        memoryFilesystemAccess.listFiles(runtimeContext).forEach { entry ->
            val date = parseDailyLedgerDate(entry.path) ?: return@forEach
            if (date.isBefore(cutoff) && memoryFilesystemAccess.deleteFile(runtimeContext, entry.path)) {
                deleted++
            }
        }
        return deleted
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentMemoryMaintenanceJob::class.java)

        private const val NAMESPACE_SEPARATOR = "\u0000"

        /** [namespacePattern] 里 userId 段的位置：`["agents", agentId, "users", uid, "memory"]`。 */
        private const val USER_SEGMENT_INDEX = 3

        /** 匹配所有用户在 `memory` 段下命名空间的通配模式，交给 [MongoStore.scanNamespaces]。 */
        fun namespacePattern(agentId: String): String =
            listOf("agents", agentId, "users", "*", MemoryFilesystemAccess.MEMORY_DIR)
                .joinToString(NAMESPACE_SEPARATOR)

        /** 从 [namespacePattern] 匹配到的具体命名空间里取出 userId 段；格式不符时返回 `null`。 */
        fun extractUserId(namespace: String): String? =
            namespace.split(NAMESPACE_SEPARATOR).getOrNull(USER_SEGMENT_INDEX)?.takeIf { it.isNotBlank() }

        /**
         * [retention] 为 `0` 或负值表示"永不清理"，返回 [LocalDate.MIN] 使得任何文件的日期都不早于它
         * （调用方在这种配置下本就不会启动调度，这里只是让纯函数本身在任意输入下都有合理的返回值）。
         *
         * 保留期不足一天时按一天计算：daily ledger 本身是按天归档的，允许配出"今天写的文件下一刻就被
         * 清掉"没有实际意义。
         */
        fun dailyLedgerCutoffDate(now: LocalDate, retention: Duration): LocalDate {
            if (retention.isZero || retention.isNegative) {
                return LocalDate.MIN
            }
            return now.minusDays(retention.toDays().coerceAtLeast(1))
        }

        /** 把 [MemoryFilesystemAccess.listFiles] 返回的路径解析成日期；不是 `memory/YYYY-MM-DD.md` 形状时返回 `null`。 */
        fun parseDailyLedgerDate(path: String): LocalDate? {
            val prefix = "${MemoryFilesystemAccess.MEMORY_DIR}/"
            if (!path.startsWith(prefix)) {
                return null
            }
            val name = path.removePrefix(prefix).removeSuffix(".md")
            return try {
                LocalDate.parse(name)
            } catch (exception: DateTimeParseException) {
                null
            }
        }
    }
}
