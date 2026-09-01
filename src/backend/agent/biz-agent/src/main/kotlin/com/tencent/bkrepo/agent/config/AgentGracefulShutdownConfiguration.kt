/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import io.agentscope.core.shutdown.GracefulShutdownConfig
import io.agentscope.core.shutdown.GracefulShutdownManager
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 把框架的停机管理器纳入容器，并给它配上**有限**的停机超时。
 *
 * 框架默认 `GracefulShutdownConfig.DEFAULT` 的 `shutdownTimeout` 是 null（无限），这在 K8s 下是个坑：
 * `AgentScopeJvmShutdownHook` 收到 SIGTERM 后会 `awaitTermination(null)` 一直等所有在跑调用结束，
 * 而 `GracefulShutdownManager` 的强制中断分支又只在配了有限超时时才会触发。也就是说一次卡住的模型
 * 调用（网关无响应、工具长时间挂起）就能让进程永远不退，最后被 SIGKILL 硬杀——反而比有序退出更糟。
 *
 * 停机时序与超时预算（默认值下的最坏情况）：
 *
 * 1. `ContextClosedEvent`：[com.tencent.bkrepo.agent.runtime.AgentRunShutdownHandler] 立即让管理器
 *    进入 SHUTTING_DOWN（新的 Agent 调用直接抛 `AgentShuttingDownException`），并中止本副本在跑的
 *    run、释放 Redis 会话锁——这一步不等待，耗时可忽略；
 * 2. Spring Boot graceful shutdown 排空 HTTP 请求：上限 `spring.lifecycle.timeout-per-shutdown-phase`
 *    （application-agent.yml 配 5s；上一步已经 complete 了 SSE emitter，正常情况下瞬间排空）；
 * 3. bean 销毁：`HarnessAgent` 实现了 AutoCloseable，Spring 推断出 `close()` 并调用，框架顺带停掉
 *    后台任务仓库的 heartbeat/孤儿清扫线程；
 * 4. JVM 停机钩子：等在跑调用收尾，上限 `agent.runtime.shutdown-timeout` + 框架硬编码的 5s
 *    INTERRUPT_GRACE_PERIOD（默认 15s + 5s = 20s），超时则强制中断并保存 AgentState。
 *
 * 合计上限约 25s，留在 K8s 默认 `terminationGracePeriodSeconds=30` 之内。改大 `shutdown-timeout` 时
 * 必须同步放大 terminationGracePeriodSeconds，否则等于白配——进程还在等，Pod 已经被 SIGKILL。
 *
 * `PartialReasoningPolicy.SAVE` 沿用框架默认：被停机打断的调用会把 AgentState 落库并标记
 * `shutdownInterrupted`，用户重发时框架的 `GracefulShutdownMiddleware` 会把重复的用户输入换成
 * "继续"语义，从断点往下走，而不是从头重跑一遍。
 */
@Configuration(proxyBeanMethods = false)
class AgentGracefulShutdownConfiguration {

    @Bean
    fun agentGracefulShutdownManager(properties: EffectiveAgentRuntimeProperties): GracefulShutdownManager {
        val manager = GracefulShutdownManager.getInstance()
        manager.config = GracefulShutdownConfig(
            properties.shutdownTimeout,
            GracefulShutdownConfig.DEFAULT.partialReasoningPolicy(),
        )
        logger.info(
            "agent graceful shutdown configured: shutdownTimeout={}, partialReasoningPolicy={}",
            properties.shutdownTimeout,
            GracefulShutdownConfig.DEFAULT.partialReasoningPolicy(),
        )
        return manager
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentGracefulShutdownConfiguration::class.java)
    }
}
