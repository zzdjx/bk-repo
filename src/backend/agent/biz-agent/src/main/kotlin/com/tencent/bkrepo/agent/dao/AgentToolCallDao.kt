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

import com.tencent.bkrepo.agent.model.TAgentToolCall
import com.tencent.bkrepo.agent.pojo.AgentToolCallDecision
import com.tencent.bkrepo.agent.pojo.AgentToolResultState
import com.tencent.bkrepo.common.mongo.dao.simple.SimpleMongoDao
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDateTime

@Repository
class AgentToolCallDao : SimpleMongoDao<TAgentToolCall>() {

    fun insertCalled(record: TAgentToolCall): TAgentToolCall = insert(record)

    /** 权限引擎判定需要询问用户：这一行（这次 run 里的这次尝试）到此为止，没有执行结果。 */
    fun markAsking(runId: String, toolCallId: String, resolvedAt: LocalDateTime) {
        updateTerminal(
            runId = runId,
            toolCallId = toolCallId,
            decision = AgentToolCallDecision.ASKING,
            resultState = null,
            resultDigest = null,
            resolvedAt = resolvedAt,
        )
    }

    /** 命中 DENY 规则或用户在确认卡片上拒绝：这一行到此为止，没有执行结果。 */
    fun markDenied(
        runId: String,
        toolCallId: String,
        decision: AgentToolCallDecision,
        resolvedAt: LocalDateTime,
    ) {
        updateTerminal(
            runId = runId,
            toolCallId = toolCallId,
            decision = decision,
            resultState = AgentToolResultState.DENIED,
            resultDigest = null,
            resolvedAt = resolvedAt,
        )
    }

    /**
     * 工具在服务端真正跑完（域工具/编排工具的最终结果，或本地工具挂起转发给客户端的 [AgentToolResultState.RUNNING]）。
     */
    fun markExecuted(
        runId: String,
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
        resolvedAt: LocalDateTime,
    ) {
        updateTerminal(
            runId = runId,
            toolCallId = toolCallId,
            decision = AgentToolCallDecision.ALLOWED,
            resultState = resultState,
            resultDigest = resultDigest,
            resolvedAt = resolvedAt,
        )
    }

    /**
     * 客户端在后续某次 resume 里真正回传了本地工具的执行结果：把仍停在 [AgentToolResultState.RUNNING]
     * 的那一行（可能是更早某次 run 写下的，不一定是当前 runId）改写为真实终态。找不到匹配行时静默忽略
     * ——理论上不应发生，但审计支路的缺失不应影响主链路。
     */
    fun updateClientReportedResult(
        toolCallId: String,
        resultState: AgentToolResultState,
        resultDigest: String?,
        resolvedAt: LocalDateTime,
    ) {
        val query = Query(
            Criteria.where(TAgentToolCall::toolCallId.name).`is`(toolCallId)
                .and(TAgentToolCall::resultState.name).`is`(AgentToolResultState.RUNNING),
        )
        val existing = findOne(query) ?: return
        val durationMs = Duration.between(existing.calledAt, resolvedAt).toMillis().coerceAtLeast(0)
        val update = Update()
            .set(TAgentToolCall::resultState.name, resultState)
            .set(TAgentToolCall::resultDigest.name, resultDigest)
            .set(TAgentToolCall::resolvedAt.name, resolvedAt)
            .set(TAgentToolCall::durationMs.name, durationMs)
        updateFirst(Query(Criteria.where("_id").`is`(existing.id)), update)
    }

    private fun updateTerminal(
        runId: String,
        toolCallId: String,
        decision: AgentToolCallDecision,
        resultState: AgentToolResultState?,
        resultDigest: String?,
        resolvedAt: LocalDateTime,
    ) {
        val query = Query(
            Criteria.where(TAgentToolCall::runId.name).`is`(runId)
                .and(TAgentToolCall::toolCallId.name).`is`(toolCallId),
        )
        val existing = findOne(query)
        val durationMs = existing?.let { Duration.between(it.calledAt, resolvedAt).toMillis().coerceAtLeast(0) }
        val update = Update()
            .set(TAgentToolCall::decision.name, decision)
            .set(TAgentToolCall::resolvedAt.name, resolvedAt)
        if (resultState != null) {
            update.set(TAgentToolCall::resultState.name, resultState)
        }
        if (resultDigest != null) {
            update.set(TAgentToolCall::resultDigest.name, resultDigest)
        }
        if (durationMs != null) {
            update.set(TAgentToolCall::durationMs.name, durationMs)
        }
        updateFirst(query, update)
    }

    fun removeByThreadId(threadId: String) {
        remove(Query(Criteria.where(TAgentToolCall::threadId.name).`is`(threadId)))
    }
}
