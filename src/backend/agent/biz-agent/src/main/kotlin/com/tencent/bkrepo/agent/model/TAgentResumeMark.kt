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
 * resume 重复提交的去重标记，对应 Mongo 集合 `agent_resume_mark`。
 *
 * 用户在确认卡片上连点两下、或者前端重试，都会发来内容完全相同的 resume。这条记录就是"这份 resume 已经
 * 受理过了"的凭证，[markKey] 上的唯一索引是并发重复提交的最终仲裁者。
 */
@Document("agent_resume_mark")
@CompoundIndexes(
    CompoundIndex(
        name = "markKey_idx",
        def = "{'markKey': 1}",
        unique = true,
        background = true,
    ),
)
data class TAgentResumeMark(
    var id: String? = null,
    /** `threadId:interruptId:fingerprint`，fingerprint 覆盖 resume 的内容，改了内容就不算重复提交。 */
    var markKey: String,
    /**
     * 过期即允许再次受理同一份 resume。
     *
     * 和 [TAgentActiveRun.expiresAt] 一样，正确性不依赖 TTL 索引：过期判断在查询条件里自己做，TTL 索引
     * 只负责回收。
     */
    @Indexed(expireAfter = "0s", background = true)
    var expiresAt: Instant,
)
