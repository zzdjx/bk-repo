/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

/**
 * 「同一会话同时只能有一个 run」这条约束的键：userId + projectId + threadId。
 *
 * 只是个标识，不带任何运行状态——单次 run 的上下文在
 * [com.tencent.bkrepo.agent.service.run.AgentRunScope]（emitter、事件流、中断现场等），
 * 两者不要混。跨副本那一层只用得到 userId + threadId（见
 * [com.tencent.bkrepo.agent.runtime.store.ActiveRunStateStore] 的 `scopeKey`），
 * projectId 带在这里是为了日志和审计能直接看出是哪个项目，不参与键的区分。
 */
data class ActiveRunKey(
    val userId: String,
    val projectId: String,
    val threadId: String,
)
