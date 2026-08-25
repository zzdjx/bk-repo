/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.pojo

/**
 * 一次工具调用的最终执行结果状态。
 *
 * 与框架 `io.agentscope.core.message.ToolResultState` 的取值一一对应，但特意在本项目内单独定义一份
 * 而不是直接把框架类型落进 Mongo 文档——框架枚举跨版本升级时的重命名/增删不应直接影响历史审计记录的
 * 反序列化。
 */
enum class AgentToolResultState {
    SUCCESS,
    ERROR,
    INTERRUPTED,
    DENIED,

    /** 服务端已放行调用，但工具本身挂起等待客户端本地执行（[com.tencent.bkrepo.agent.tool.local.ExternalLocalTool]）。 */
    RUNNING,
}
