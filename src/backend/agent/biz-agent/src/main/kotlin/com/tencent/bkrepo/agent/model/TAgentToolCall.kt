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

package com.tencent.bkrepo.agent.model

import com.tencent.bkrepo.agent.pojo.AgentToolCallDecision
import com.tencent.bkrepo.agent.pojo.AgentToolResultState
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * 一次工具调用尝试的审计记录，对应 Mongo 集合 `agent_tool_call`。
 *
 * 一个逻辑上的"工具调用"在 HITL 场景下天然横跨多个 run（ASKING 挂起一次、确认后放行执行又挂起一次，
 * 见 [com.tencent.bkrepo.agent.usage.UsageTrackingMiddleware] 同款 "run 内累计、不跨 run 合并" 的设计
 * 取舍），因此这里按 (runId, toolCallId) 而不是单独的 toolCallId 建行——每一行对应"这次 run 里对这个
 * 工具调用做了什么"，同一个 toolCallId 在跨 run 续跑时会有多行，靠 [toolCallId] 直接查询即可拼出完整
 * 时间线，不需要额外的 parentRunId/rootRunId 链式字段。
 */
@Document("agent_tool_call")
@CompoundIndexes(
    CompoundIndex(
        name = "runId_toolCallId_idx",
        def = "{'runId': 1, 'toolCallId': 1}",
        unique = true,
        background = true,
    ),
    CompoundIndex(
        name = "toolCallId_resultState_idx",
        def = "{'toolCallId': 1, 'resultState': 1}",
        background = true,
    ),
    CompoundIndex(
        name = "threadId_calledAt_idx",
        def = "{'threadId': 1, 'calledAt': -1}",
        background = true,
    ),
    CompoundIndex(
        name = "userId_projectId_calledAt_idx",
        def = "{'userId': 1, 'projectId': 1, 'calledAt': -1}",
        background = true,
    ),
)
data class TAgentToolCall(
    var id: String? = null,
    var runId: String,
    var threadId: String,
    var userId: String,
    var projectId: String,
    var toolCallId: String,
    var toolName: String,
    /** [io.agentscope.core.message.ToolUseBlock.getInput] 的截断 JSON 摘要，不保证是合法/完整 JSON。 */
    var argsDigest: String? = null,
    var decision: AgentToolCallDecision,
    var resultState: AgentToolResultState? = null,
    /** 工具执行结果的截断文本摘要；[AgentToolResultState.RUNNING] 时为 null（结果尚未产生）。 */
    var resultDigest: String? = null,
    var calledAt: LocalDateTime,
    var resolvedAt: LocalDateTime? = null,
    var durationMs: Long? = null,
)
