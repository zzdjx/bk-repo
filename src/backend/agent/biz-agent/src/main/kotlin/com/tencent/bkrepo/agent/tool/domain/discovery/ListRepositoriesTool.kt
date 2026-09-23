/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.domain.discovery

import com.tencent.bkrepo.agent.tool.domain.AbstractReadOnlyDomainTool
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.domain.DomainToolSchemas
import com.tencent.bkrepo.agent.tool.gateway.DomainToolGateway
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.repository.pojo.repo.RepoListOption
import io.agentscope.core.tool.ToolCallParam
import org.springframework.stereotype.Component

/**
 * Discovery 专业 Agent 的入口工具：列出当前项目下调用方有权访问的仓库。
 *
 * 只回传模型回答问题真正需要的字段，仓库配置、存储凭证 key、创建修改人等一概不出现在工具结果里，
 * 既避免把存储侧标识透给模型，也避免无关字段挤占上下文。
 */
@Suppress("DEPRECATION")
@Component
class ListRepositoriesTool(
    private val domainToolGateway: DomainToolGateway,
    private val repositoryClient: RepositoryClient,
) : AbstractReadOnlyDomainTool(
    name = DomainToolNames.LIST_REPOSITORIES,
    description = "列出当前项目下用户有权访问的制品仓库。无需参数；" +
        "返回 projectId、仓库总数，以及每个仓库的名称、类型、类别、是否公开与描述。",
    inputSchema = DomainToolSchemas.NO_ARGS,
) {
    override fun execute(param: ToolCallParam): String {
        val projectId = domainToolGateway.currentProjectId(param.runtimeContext)
        val userId = domainToolGateway.currentUserId(param.runtimeContext)
        domainToolGateway.requireResourcePermission(
            runtimeContext = param.runtimeContext,
            resourceType = ResourceType.PROJECT,
            action = PermissionAction.READ,
        )
        // repository 服务的该接口内部已合并「项目管理员放行 + IAM 有权限仓库名单 + 严格模式过滤」。
        // 不能换成 listRepo，那会把当前用户无权访问的仓库名一并暴露给模型
        val repositories = repositoryClient.listPermissionRepo(userId, projectId, RepoListOption()).data.orEmpty()
        return ListRepositoriesResult(
            projectId = projectId,
            total = repositories.size,
            repositories = repositories.map {
                RepositorySummary(
                    name = it.name,
                    type = it.type.name,
                    category = it.category.name,
                    public = it.public,
                    description = it.description,
                )
            },
        ).toJsonString()
    }
}

private data class ListRepositoriesResult(
    val projectId: String,
    val total: Int,
    val repositories: List<RepositorySummary>,
    val ok: Boolean = true,
)

private data class RepositorySummary(
    val name: String,
    val type: String,
    val category: String,
    val public: Boolean,
    val description: String?,
)
