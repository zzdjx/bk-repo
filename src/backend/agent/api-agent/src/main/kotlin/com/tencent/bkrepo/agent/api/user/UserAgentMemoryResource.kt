/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.api.user

import com.tencent.bkrepo.agent.constant.AGENT_API_PREFIX
import com.tencent.bkrepo.agent.pojo.AgentMemoryDeleteRequest
import com.tencent.bkrepo.agent.pojo.AgentMemoryFileInfo
import com.tencent.bkrepo.common.api.pojo.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Agent 长期记忆 HTTP 契约。
 *
 * 与会话/对话接口不同，长期记忆按 [io.agentscope.harness.agent.IsolationScope.USER] 隔离
 * （见 `AgentMemoryFilesystemConfiguration`），只按 `userId` 归属，不区分 `projectId`，因此这里的
 * 接口都不需要 `projectId` 参数。所有接口均需登录；未开启长期记忆能力（无 Redis）时统一返回资源不存在。
 */
@Tag(name = "Agent长期记忆接口")
@RequestMapping(AGENT_API_PREFIX)
interface UserAgentMemoryResource {

    @Operation(summary = "查询我保存过的全部记忆文件")
    @GetMapping("/memory/files")
    fun listFiles(
        @RequestAttribute userId: String,
    ): Response<List<AgentMemoryFileInfo>>

    @Operation(summary = "查询某个记忆文件的完整内容")
    @GetMapping("/memory/content")
    fun getContent(
        @RequestAttribute userId: String,
        @Parameter(name = "相对路径，例如 MEMORY.md 或 memory/2026-04-01.md", required = true)
        @RequestParam path: String,
    ): Response<String>

    @Operation(summary = "删除单个记忆文件")
    @PostMapping("/memory/delete")
    fun deleteFile(
        @RequestAttribute userId: String,
        @RequestBody request: AgentMemoryDeleteRequest,
    ): Response<Boolean>

    @Operation(summary = "清空我的全部长期记忆")
    @PostMapping("/memory/clear")
    fun clearAll(
        @RequestAttribute userId: String,
    ): Response<Boolean>
}
