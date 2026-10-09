/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service

import com.tencent.bkrepo.agent.pojo.AgentMessageInfo
import com.tencent.bkrepo.agent.pojo.AgentSessionCreateResult
import com.tencent.bkrepo.agent.pojo.AgentSessionDeleteRequest
import com.tencent.bkrepo.agent.pojo.AgentSessionInfo
import com.tencent.bkrepo.agent.pojo.AgentSessionUpdateRequest
import com.tencent.bkrepo.common.api.pojo.Page

/**
 * 会话与消息业务服务。
 *
 * 对外入口校验项目 [project_view] 权限；[userId] 来自已认证请求上下文。
 */
interface AgentSessionService {

    /** 创建新会话。 */
    fun createSession(userId: String, projectId: String): AgentSessionCreateResult

    /** 分页列出用户在项目下的 ACTIVE 会话。 */
    fun listSessions(userId: String, projectId: String, pageNumber: Int, pageSize: Int): Page<AgentSessionInfo>

    /** 分页列出会话消息归档。 */
    fun listMessages(
        userId: String,
        projectId: String,
        threadId: String,
        pageNumber: Int,
        pageSize: Int,
    ): Page<AgentMessageInfo>

    /** 更新会话标题。 */
    fun updateSession(userId: String, projectId: String, request: AgentSessionUpdateRequest)

    /** 删除会话及关联的运行态与持久化数据。 */
    fun deleteSession(userId: String, projectId: String, request: AgentSessionDeleteRequest)

    /**
     * 校验会话归当前用户在本项目下所有且处于 ACTIVE，会话不存在直接 404。
     *
     * 查询、改标题、停止、重连、删除这些路径都该用它：拿着一个打错的 threadId 调进来，正确的回答是
     * "没这个会话"，而不是凭空造一条空会话出来。
     */
    fun assertSessionAccessible(userId: String, projectId: String, threadId: String)

    /**
     * 同 [assertSessionAccessible]，但会话不存在时按 [threadId] 先补一条再校验（run 链路内部调用）。
     *
     * run 接口允许客户端自己生成 threadId 直接发起对话，不强制先调 `createSession`，所以这条路径上
     * "会话不存在"是正常的首轮情形而不是错误。
     */
    fun ensureSessionAccessible(userId: String, projectId: String, threadId: String)

    /**
     * 记录最近一次 runId，并刷新会话 updatedAt（run 链路内部调用）。
     *
     * [userId]/[projectId] 可选：传入时用于按 `agent.runtime.retention.overrides` 匹配会话保留期覆盖，
     * 不传（`null`）时落回全局默认保留期，不影响 touchSession 的核心行为。
     */
    fun touchSession(threadId: String, runId: String, userId: String? = null, projectId: String? = null)
}
