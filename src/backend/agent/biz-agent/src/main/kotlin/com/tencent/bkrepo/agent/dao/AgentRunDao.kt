/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.dao

import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDateTime

@Repository
class AgentRunDao : SimpleMongoDao<TAgentRun>() {

    fun insertRun(run: TAgentRun): TAgentRun = insert(run)

    fun findByRunId(runId: String): TAgentRun? {
        val query = Query(Criteria.where(TAgentRun::runId.name).`is`(runId))
        return findOne(query)
    }

    fun findLatestByThreadId(threadId: String): TAgentRun? {
        val query = Query(Criteria.where(TAgentRun::threadId.name).`is`(threadId))
            .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, TAgentRun::startedAt.name))
            .limit(1)
        return findOne(query)
    }

    /**
     * 将仍处于 [AgentRunStatus.RUNNING] 的记录更新为终态。
     *
     * @return 是否成功写入（重复终态写入返回 false）
     */
    fun finishRun(
        runId: String,
        status: AgentRunStatus,
        finishedAt: LocalDateTime,
        startedAt: LocalDateTime,
        cancelReason: String? = null,
        errorCode: String? = null,
    ): Boolean {
        val query = Query(
            Criteria.where(TAgentRun::runId.name).`is`(runId)
                .and(TAgentRun::status.name).`is`(AgentRunStatus.RUNNING),
        )
        val durationMs = Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(0)
        val update = Update()
            .set(TAgentRun::status.name, status)
            .set(TAgentRun::finishedAt.name, finishedAt)
            .set(TAgentRun::durationMs.name, durationMs)
        if (cancelReason != null) {
            update.set(TAgentRun::cancelReason.name, cancelReason)
        }
        if (errorCode != null) {
            update.set(TAgentRun::errorCode.name, errorCode)
        }
        return updateFirst(query, update).modifiedCount > 0
    }

    fun removeByThreadId(threadId: String) {
        remove(Query(Criteria.where(TAgentRun::threadId.name).`is`(threadId)))
    }

    /**
     * 按 runId 原子累加一次模型调用的用量到对应的 run 记录上。
     *
     * 用 `$inc` 而不是"先查后写"，允许同一 run 内多次模型调用（ReAct 循环、确认后二次调用等）并发安全叠加。
     * 若对应 runId 尚不存在（理论上不应发生，[startRun] 早于任何模型调用）则静默无操作，不抛异常。
     */
    fun incrementUsage(
        runId: String,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        durationMs: Long,
    ) {
        val query = Query(Criteria.where(TAgentRun::runId.name).`is`(runId))
        val update = Update()
            .inc(TAgentRun::modelCallCount.name, 1)
            .inc(TAgentRun::inputTokens.name, inputTokens)
            .inc(TAgentRun::outputTokens.name, outputTokens)
            .inc(TAgentRun::cachedTokens.name, cachedTokens)
            .inc(TAgentRun::totalModelDurationMs.name, durationMs)
        updateFirst(query, update)
    }
}
