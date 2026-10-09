/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.session

import com.tencent.bkrepo.agent.dao.AgentResumeMarkDao
import java.time.Duration
import java.time.Instant

/**
 * resume 去重标记落 MongoDB。
 *
 * Mongo 的唯一索引提供的"插入即占位、撞键即失败"和 Redis 的 `SET NX` 是同一种原子性，而这份数据写入
 * 频率只和用户点确认卡片的次数挂钩，没有理由为它单独引入一套存储。
 */
class MongoAgentResumeIdempotencyStore(
    private val dao: AgentResumeMarkDao,
    private val ttl: Duration,
) : AgentResumeIdempotencyStore {

    override fun tryMark(threadId: String, interruptId: String, fingerprint: String): Boolean {
        return dao.tryMark("$threadId:$interruptId:$fingerprint", Instant.now().plus(ttl))
    }
}
