/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.dao

import com.tencent.bkrepo.agent.model.TAgentPendingInterrupt
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

/** 待确认中断现场访问层，对应 Mongo 集合 `agent_pending_interrupt`。 */
@Repository
class AgentPendingInterruptDao : SimpleMongoDao<TAgentPendingInterrupt>() {

    fun savePayload(threadId: String, payload: String, expiresAt: Instant) {
        val now = Instant.now()
        val update = Update()
            .set(TAgentPendingInterrupt::payload.name, payload)
            .set(TAgentPendingInterrupt::updatedAt.name, now)
            .set(TAgentPendingInterrupt::expiresAt.name, expiresAt)
        upsert(byThreadId(threadId), update)
    }

    fun findByThreadId(threadId: String): TAgentPendingInterrupt? = findOne(byThreadId(threadId))

    fun removeByThreadId(threadId: String) {
        remove(byThreadId(threadId))
    }

    private fun byThreadId(threadId: String) =
        Query(Criteria.where(TAgentPendingInterrupt::threadId.name).`is`(threadId))
}
