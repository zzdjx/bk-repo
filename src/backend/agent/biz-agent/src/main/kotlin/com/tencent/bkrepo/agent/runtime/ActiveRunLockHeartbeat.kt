/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 会话锁续期看门狗：周期性给本副本在跑的 run 续上会话锁。
 *
 * 不做这件事的后果是"run 的时长上限被锁 TTL 悄悄限定了"。锁（`RedisActiveRunStateStore`）是一把 TTL
 * 固定的 Redis 锁，默认 `agent.runtime.active-run-ttl` = 11 分钟；而一个 run 有多轮推理与工具调用，
 * 总时长取决于模型和工具，不受我们控制。超过 TTL 后锁自己消失，同一会话上就能并发起第二个 run，两个
 * run 交替写同一份 AgentState——不是变慢，是状态被写坏。压缩单次模型调用的超时预算（见
 * [com.tencent.bkrepo.agent.config.AgentModelBudgetValidator]）只能让单次调用不越界，管不住累计时长。
 *
 * 用自己的单线程调度器而不是 `@Scheduled`：本模块没有开 `@EnableScheduling`，而这件事必须一直跑、
 * 不该受别处调度配置影响；单线程足够，续期只是每个在跑 run 两次 EXPIRE。
 *
 * 续期间隔由锁 TTL 推导（TTL 的三分之一，下限 5 秒），不额外开配置项：这两个值必须联动，暴露成两个
 * 独立配置只会制造"间隔比 TTL 还长"这类无意义的错配。取三分之一是为了容忍连续两次续期失败（Redis
 * 抖动、线程被拖住）仍不至于丢锁。
 */
@Component
class ActiveRunLockHeartbeat(
    private val activeRunManager: ActiveRunManager,
    properties: EffectiveAgentRuntimeProperties,
) {

    private val interval: Duration = renewInterval(properties.activeRunTtl)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "agent-run-lock-heartbeat").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        scheduler.scheduleWithFixedDelay(
            ::renewOnce,
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        logger.info("agent run lock heartbeat started: interval={}", interval)
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
    }

    /**
     * 调度器只要抛出异常就会静默停掉后续所有执行，所以这里必须兜住一切——包括 Error，否则一次偶发的
     * OOM 之后看门狗就永久失效了，而且没有任何迹象。
     */
    private fun renewOnce() {
        try {
            val renewed = activeRunManager.renewLocalRunLocks()
            if (renewed > 0) {
                logger.debug("renewed session lock for {} active run(s)", renewed)
            }
        } catch (throwable: Throwable) {
            logger.error("agent run lock heartbeat iteration failed", throwable)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ActiveRunLockHeartbeat::class.java)

        private val MIN_INTERVAL: Duration = Duration.ofSeconds(5)

        fun renewInterval(activeRunTtl: Duration): Duration =
            maxOf(activeRunTtl.dividedBy(3), MIN_INTERVAL)
    }
}
