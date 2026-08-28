/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.resources

import com.tencent.bkrepo.agent.api.user.UserAgentMemoryResource
import com.tencent.bkrepo.agent.constant.LOG_OPERATE_MEMORY_CLEAR
import com.tencent.bkrepo.agent.constant.LOG_OPERATE_MEMORY_CONTENT
import com.tencent.bkrepo.agent.constant.LOG_OPERATE_MEMORY_DELETE
import com.tencent.bkrepo.agent.constant.LOG_OPERATE_MEMORY_LIST
import com.tencent.bkrepo.agent.pojo.AgentMemoryDeleteRequest
import com.tencent.bkrepo.agent.pojo.AgentMemoryFileInfo
import com.tencent.bkrepo.agent.service.AgentMemoryService
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.metadata.annotation.LogOperate
import com.tencent.bkrepo.common.security.permission.Principal
import com.tencent.bkrepo.common.security.permission.PrincipalType
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import org.springframework.web.bind.annotation.RestController

@RestController
@Principal(PrincipalType.GENERAL)
class UserAgentMemoryResourceImpl(
    private val agentMemoryService: AgentMemoryService,
) : UserAgentMemoryResource {

    @LogOperate(type = LOG_OPERATE_MEMORY_LIST)
    override fun listFiles(userId: String): Response<List<AgentMemoryFileInfo>> {
        return ResponseBuilder.success(agentMemoryService.listFiles(userId))
    }

    @LogOperate(type = LOG_OPERATE_MEMORY_CONTENT)
    override fun getContent(userId: String, path: String): Response<String> {
        return ResponseBuilder.success(agentMemoryService.readFile(userId, path))
    }

    @LogOperate(type = LOG_OPERATE_MEMORY_DELETE)
    override fun deleteFile(userId: String, request: AgentMemoryDeleteRequest): Response<Boolean> {
        return ResponseBuilder.success(agentMemoryService.deleteFile(userId, request.path))
    }

    @LogOperate(type = LOG_OPERATE_MEMORY_CLEAR)
    override fun clearAll(userId: String): Response<Boolean> {
        agentMemoryService.clearAll(userId)
        return ResponseBuilder.success(true)
    }
}
