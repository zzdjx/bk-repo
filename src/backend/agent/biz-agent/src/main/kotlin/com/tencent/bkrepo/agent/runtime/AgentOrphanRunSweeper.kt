/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.dao.AgentRunDao
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 把"持有它的副本已经没了"的 run 记录从 [AgentRunStatus.RUNNING] 收到终态。
 *
 * ## 为什么需要这件事
 *
 * run 走到终态只有一条路径：[com.tencent.bkrepo.agent.service.run.AgentRunLifecycleManager.finish]。它跑在
 * 持有这个 run 的副本进程里，正常结束、用户停止、服务优雅停机都会经过它。但副本被 `SIGKILL`、OOM 杀掉、
 * 节点掉电时，这段代码根本没有机会执行，留下的 run 记录就永远停在 RUNNING。
 *
 * 会话运行权有 TTL，过期后别人还能继续在这个会话上发起新 run，所以用户不会被锁死；真正留下的是一条
 * 永远不会收尾的记录——`getRunStatus` 一直回 RUNNING、客户端带同一个 runId 重连会撞上
 * "Run is already running"、审计和用量统计里多出一条没有结束时间的运行。
 *
 * ## 判定依据是运行权绑定，不是时间
 *
 * 一个 run 还活着的充要条件是：它所在会话的运行权文档还没过期，且绑定的 runId 就是它。心跳
 * （[ActiveRunLockHeartbeat]）每 TTL/3 续一次期，副本还在就一定续得上；副本没了，最多一个 TTL 之后这个
 * 文档就不再是"活的"。
 *
 * [staleAfter] 只是一道便宜的预筛，把绝大多数正在跑的 run 挡在查询之外，而不是判定依据本身——跑过一个
 * TTL 的长对话完全正常，筛出来之后还要再查一次运行权才作数。
 *
 * ## 为什么收成 FAILED 而不是 CANCELLED
 *
 * CANCELLED 的语义是"有人主动停了它"（用户点停止、服务优雅停机），对应 [AgentRunAbortReason]。副本猝死
 * 没有任何人做过这个决定，run 是在半路被打断的，FAILED + [ERROR_CODE] 才说得通；混进 CANCELLED 会让
 * "用户取消率"这类指标凭空变高，掩盖掉真正该被发现的副本异常退出。
 *
 * ## 多副本与幂等
 *
 * 每个副本都在跑这个清扫，不加分布式锁：收尾走的 `AgentRunDao.finishRun` 带 `status = RUNNING` 条件，
 * 并发时只有一个能改成功，其余返回 0 行、当作已被别人处理。
 *
 * 清扫只改 run 记录，不碰运行权文档——那个文档已经过期，下一次 `tryAcquire` 直接覆盖它，TTL 也会回收。
 */
@Component
class AgentOrphanRunSweeper(
    private val agentRunDao: AgentRunDao,
    private val activeRunManager: ActiveRunManager,
    properties: EffectiveAgentRuntimeProperties,
) {

    private val staleAfter: Duration = properties.activeRunTtl

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "agent-orphan-run-sweeper").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        scheduler.scheduleWithFixedDelay(
            ::sweepOnce,
            staleAfter.toMillis(),
            staleAfter.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        logger.info("agent orphan run sweeper started: interval={}", staleAfter)
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
    }

    /**
     * 与 [ActiveRunLockHeartbeat.renewOnce] 同理：调度器一旦收到异常就会静默停掉后续所有执行，
     * 所以这里连 Error 一起兜住。
     */
    fun sweepOnce() {
        try {
            val swept = agentRunDao.findStaleRunning(LocalDateTime.now().minus(staleAfter), BATCH_SIZE)
                .count { abandonIfOrphaned(it) }
            if (swept > 0) {
                logger.warn("swept $swept orphaned run(s) left behind by dead replicas")
            }
        } catch (throwable: Throwable) {
            logger.error("agent orphan run sweep iteration failed", throwable)
        }
    }

    private fun abandonIfOrphaned(run: TAgentRun): Boolean {
        val key = ActiveRunKey(run.userId, run.projectId, run.threadId)
        if (activeRunManager.getActiveRunId(key) == run.runId) {
            return false
        }
        val abandoned = agentRunDao.finishRun(
            runId = run.runId,
            status = AgentRunStatus.FAILED,
            finishedAt = LocalDateTime.now(),
            startedAt = run.startedAt,
            errorCode = ERROR_CODE,
        )
        if (abandoned) {
            logger.warn(
                "run[${run.runId}] on thread[${run.threadId}] lost its owner replica, marked FAILED",
            )
        }
        return abandoned
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentOrphanRunSweeper::class.java)

        /** 落在 run 记录的 `errorCode` 上，用于把副本猝死和真正的业务失败区分开。 */
        const val ERROR_CODE = "OwnerReplicaLost"

        /** 单轮处理上限；历史脏数据可能很多，分轮清掉即可，不需要一次做完。 */
        private const val BATCH_SIZE = 200
    }
}
