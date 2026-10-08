/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.model

import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * thread 级待确认中断现场，对应 Mongo 集合 `agent_pending_interrupt`。
 *
 * 每个 thread 最多一条：新一轮 interrupt 覆盖上一轮，resume 处理完即删。
 *
 * [payload] 存的是 [com.tencent.bkrepo.agent.session.PendingInterruptSession] 的 JSON 串，不展开成
 * 子文档。两个原因：一是 `metadata` 的 key 直接来自框架（如 `agentscope.interruptKind`）带点号，
 * 展开后会撞上 Mongo 的字段名限制；二是这份数据只按 threadId 整取整存，从不按内部字段查询，展开只会
 * 平白引入一层映射规则。
 */
@Document("agent_pending_interrupt")
@CompoundIndexes(
    CompoundIndex(
        name = "threadId_idx",
        def = "{'threadId': 1}",
        unique = true,
        background = true,
    ),
)
data class TAgentPendingInterrupt(
    var id: String? = null,
    var threadId: String,
    var payload: String,
    var updatedAt: Instant,
    /** 到期由 Mongo TTL 索引回收，兜住"用户再也不回来点确认"的中断现场。 */
    @Indexed(expireAfter = "0s", background = true)
    var expiresAt: Instant,
)
