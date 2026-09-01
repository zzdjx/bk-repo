/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

/**
 * run 被提前中止的原因，最终落到 `agent_run.cancelReason`。
 *
 * 之前只有"用户点了停止"一种情况，原因是硬编码的字符串常量；加入停机中止后必须能区分两者：
 * 同样是 CANCELLED 终态，用户主动停止是正常操作，副本停机中止则意味着这次对话是被发布/扩缩容
 * 打断的，排障和统计口径都不一样。
 */
enum class AgentRunAbortReason(val value: String) {

    /** 用户在前端点击停止，或跨副本广播过来的停止请求。 */
    USER_STOP("user_stop"),

    /**
     * 本副本正在停机（滚动发布、缩容、重启）。用户可以立即重发，框架已把 AgentState 标记为
     * `shutdownInterrupted`，重发会从断点继续而不是从头重跑。
     */
    SERVER_SHUTDOWN("server_shutdown"),
}
