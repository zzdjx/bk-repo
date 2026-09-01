/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import io.agentscope.core.shutdown.GracefulShutdownManager
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 副本停机时的 run 收尾：拒绝新调用 + 中止本副本在跑的 run + 释放会话锁。
 *
 * 不做这件事的后果很具体：会话锁（`ActiveRunStateStore`）只在 run 自己的收尾逻辑里释放，进程被杀时
 * 那段逻辑随进程一起消失，锁只能等 `agent.runtime.active-run-ttl`（默认 11 分钟）自然过期。也就是说
 * 每次滚动发布，正在对话的用户重发消息都会撞上"上一次运行还在进行中"，最长要等 11 分钟——用户视角
 * 是"这个助手坏了"，而不是"服务在发布"。
 *
 * 挂钟在 [ContextClosedEvent] 而不是 `@PreDestroy`：Spring 关闭时先发 ContextClosedEvent，再走
 * Lifecycle 停止（Web 容器排空请求），最后才销毁 bean。选最早的这个点，既能在 HTTP 排空之前就把 SSE
 * emitter complete 掉（排空不用干等），也确保释放锁时 Redis 相关 bean 还完全可用。
 *
 * 时序上先 [GracefulShutdownManager.performGracefulShutdown] 再中止 run：前者把管理器切到
 * SHUTTING_DOWN，新的 Agent 调用会直接抛 `AgentShuttingDownException`（K8s 摘流量是异步的，这段窗口
 * 里仍可能有请求打进来），同时启动框架的强制中断计时；顺序反过来的话，刚中止的会话可能又被新请求占上。
 */
@Component
class AgentRunShutdownHandler(
    private val activeRunManager: ActiveRunManager,
    private val shutdownManager: GracefulShutdownManager,
) {

    private val handled = AtomicBoolean(false)

    @EventListener
    fun onContextClosed(event: ContextClosedEvent) {
        shutdown()
    }

    /** 幂等：父子上下文都会各发一次 ContextClosedEvent，重复执行只会多打日志。 */
    fun shutdown() {
        if (!handled.compareAndSet(false, true)) {
            return
        }
        shutdownManager.performGracefulShutdown()
        val aborted = activeRunManager.abortLocalRuns(AgentRunAbortReason.SERVER_SHUTDOWN)
        logger.info(
            "agent shutdown: rejecting new agent calls, aborted {} in-flight run(s) and released session lock(s)",
            aborted,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentRunShutdownHandler::class.java)
    }
}
