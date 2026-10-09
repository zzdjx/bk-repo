/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.session

import java.util.concurrent.ConcurrentHashMap

/**
 * [AgentResumeIdempotencyStore] 的内存替身，仅供测试。
 *
 * 生产实现是 [MongoAgentResumeIdempotencyStore]，不存在内存降级路径。这份实现没有过期语义——标记一旦
 * 落下就永不释放，拿它当生产降级会让用户的确认卡片永久点不动。
 */
class InMemoryAgentResumeIdempotencyStore : AgentResumeIdempotencyStore {

    private val keys = ConcurrentHashMap.newKeySet<String>()

    override fun tryMark(threadId: String, interruptId: String, fingerprint: String): Boolean {
        return keys.add("$threadId:$interruptId:$fingerprint")
    }
}
