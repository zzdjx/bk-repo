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

package com.tencent.bkrepo.agent.service

import com.tencent.bkrepo.agent.pojo.AgentToolCallDecision
import com.tencent.bkrepo.agent.pojo.AgentToolResultState

/**
 * 工具级审计记录服务：任何方法的异常都不应向上抛出（实现参考 [AgentRunRecordService.recordModelCallUsage]
 * 的容错风格）——审计留痕失败绝不能影响 Agent 主链路。
 */
interface AgentToolCallRecordService {

    /** 一次工具调用即将进入 acting 阶段（PRE_ACTING）时落一行初始记录。 */
    fun recordCalled(
        runId: String,
        threadId: String,
        userId: String,
        projectId: String,
        toolCallId: String,
        toolName: String,
        argsDigest: String?,
        initialDecision: AgentToolCallDecision,
    )

    /** 权限引擎判定需要询问用户，本次 run 内该工具调用到此挂起，等待用户在确认卡片上作出选择。 */
    fun recordAsking(runId: String, toolCallId: String)

    /** 命中 DENY 规则，未询问用户即被拒绝。 */
    fun recordRuleDenied(runId: String, toolCallId: String)

    /** 用户在确认卡片上点了拒绝（[com.tencent.bkrepo.agent.hitl.AguiPermissionResumeAdapter]）。 */
    fun recordUserDenied(originRunId: String, toolCallId: String)

    /** 工具在服务端真正执行完成（域工具/编排工具的终态，或本地工具挂起转发客户端的 RUNNING 态）。 */
    fun recordExecuted(runId: String, toolCallId: String, resultState: AgentToolResultState, resultDigest: String?)

    /** 客户端在后续某次 resume 里真正回传了本地工具的执行结果，补齐此前停在 RUNNING 的那一行。 */
    fun recordClientReportedResult(toolCallId: String, resultState: AgentToolResultState, resultDigest: String?)

    fun removeByThreadId(threadId: String)
}
