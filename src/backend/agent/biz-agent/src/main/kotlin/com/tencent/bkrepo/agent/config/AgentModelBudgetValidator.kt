/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentLlmProperties
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.runtime.ActiveRunLockHeartbeat
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 启动期把"一次模型调用的最坏耗时"与会话锁 TTL、停机窗口的关系算出来记进日志。
 *
 * 起因是框架默认值与本项目的锁设计撞车：`ExecutionConfig.MODEL_DEFAULTS` 是每次尝试超时 5 分钟、
 * 最多 3 次尝试（超时按每次尝试各自计时），最坏一次模型调用 15 分钟；而会话锁默认 TTL 11 分钟。
 * 这几个数字散落在框架常量、`agent.llm` 与 `agent.runtime` 三处，谁调谁都看不到对面，所以在启动时
 * 集中算一遍。
 *
 * **只告警、不阻断启动**，因为真正兜住正确性的是 [ActiveRunLockHeartbeat]：锁由独立线程按 TTL/3 续期，
 * 与模型调用多久无关，所以"预算超过 TTL"已经不再直接意味着锁会在 run 存活期间过期。剩下的仍是值得知道
 * 的信号——看门狗本身被拖住时，TTL 就是最后的宽限期，此时单次调用越长、裸奔窗口越大；何况让用户干等
 * 十几分钟本身就不合理。把它做成致命错误只会在没有真实故障的情况下卡住发布。
 *
 * 顺带把停机等待窗口也打进日志：它与单次超时的关系（默认 15s vs 90s，即在途模型调用一定会被硬中断）
 * 是刻意的取舍，所以只记录、不告警，理由见 [findings]。
 */
@Component
class AgentModelBudgetValidator(
    private val llm: EffectiveAgentLlmProperties,
    private val runtime: EffectiveAgentRuntimeProperties,
) {

    @PostConstruct
    fun validate() {
        val findings = findings()
        findings.forEach { logger.warn(it) }
        logger.info(
            "agent model budget: worstCaseCallBudget={}, activeRunTtl={}, lockRenewInterval={}, " +
                "shutdownWait={}, fallbackEnabled={}, findings={}",
            llm.worstCaseModelCallBudget(),
            runtime.activeRunTtl,
            ActiveRunLockHeartbeat.renewInterval(runtime.activeRunTtl),
            runtime.shutdownTimeout,
            llm.fallbackEnabled(),
            findings.size,
        )
    }

    /**
     * 返回所有不自洽之处，空表示这几个时间参数彼此协调。抽成纯函数便于单测直接断言，不用去抓日志。
     *
     * 刻意**不**检查"单次超时是否长于停机等待窗口"：默认值（90s vs 15s）本来就是超的，停机时硬中断
     * 在途模型调用是我们选定并接受的行为（run 可恢复）。对着刻意选的默认值每次启动报一条告警，只会
     * 训练所有人忽略日志。
     */
    fun findings(): List<String> = buildList {
        val budget = llm.worstCaseModelCallBudget()
        if (budget >= runtime.activeRunTtl) {
            add(
                "Worst-case model call budget ($budget) is not shorter than the session run lock TTL " +
                    "(${runtime.activeRunTtl}). The lock is kept alive by a heartbeat so this is not " +
                    "immediately unsafe, but if the heartbeat stalls a single model call can outlive the " +
                    "lock. Consider lowering $REQUEST_TIMEOUT_PROPERTY / $MAX_ATTEMPTS_PROPERTY, " +
                    "or raising $ACTIVE_RUN_TTL_PROPERTY."
            )
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentModelBudgetValidator::class.java)

        const val REQUEST_TIMEOUT_PROPERTY = "agent.llm.request-timeout"
        const val MAX_ATTEMPTS_PROPERTY = "agent.llm.max-attempts"
        const val ACTIVE_RUN_TTL_PROPERTY = "agent.runtime.active-run-ttl"
    }
}
