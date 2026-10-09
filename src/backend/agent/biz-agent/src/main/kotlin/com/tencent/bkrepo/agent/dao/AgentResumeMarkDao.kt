/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.dao

import com.tencent.bkrepo.agent.model.TAgentResumeMark
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

/** resume 去重标记访问层，对应 Mongo 集合 `agent_resume_mark`。 */
@Repository
class AgentResumeMarkDao : SimpleMongoDao<TAgentResumeMark>() {

    /**
     * 登记一次 resume，首次登记返回 true，重复提交返回 false。
     *
     * 和 [AgentActiveRunDao.tryAcquire] 同一个惯用法：`findAndModify(upsert)` 一次搞定"没有就插入、
     * 过期就改写、还活着就撞唯一索引失败"三种情形。不用基类的 `upsert`，它会吞掉 [DuplicateKeyException]
     * 并重试，而撞键正是这里判定"重复提交"的信号。
     */
    fun tryMark(markKey: String, expiresAt: Instant): Boolean {
        val query = Query(
            Criteria.where(TAgentResumeMark::markKey.name).`is`(markKey)
                .and(TAgentResumeMark::expiresAt.name).lt(Instant.now()),
        )
        val update = Update()
            .set(TAgentResumeMark::markKey.name, markKey)
            .set(TAgentResumeMark::expiresAt.name, expiresAt)
        val options = FindAndModifyOptions().upsert(true).returnNew(true)
        return try {
            findAndModify(query, update, options, TAgentResumeMark::class.java) != null
        } catch (ignored: DuplicateKeyException) {
            false
        }
    }
}
