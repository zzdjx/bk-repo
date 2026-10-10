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
import io.agentscope.core.tool.ToolCallParam
import org.springframework.stereotype.Component

/**
 * Discovery 专业 Agent：查询单个仓库的详情。
 *
 * ## 出参是白名单，不是把 RepositoryDetail 整个倒出来
 *
 * [com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail] 上挂着两处绝不能进模型上下文的东西：
 * `storageCredentials` 是存储凭证本身，`configuration` 对远程/代理/组合仓库会在 settings 里带上游通道的
 * 用户名口令。工具结果最终会原样进入对话历史并落 `agent_message` 归档，一旦带出去就是长期泄漏，且没有
 * 任何环节会再做脱敏。所以这里逐字段列白名单，而不是写一个"排除某几个字段"的黑名单——黑名单在上游
 * 给 `RepositoryDetail` 加字段时会默默失效。
 *
 * 与 [ListRepositoriesTool] 的取舍不同之处：列表工具连创建人都不回，因为 N 个仓库乘以无关字段会挤占
 * 上下文；详情工具是用户指名问某一个仓库，createdBy / lastModifiedBy 本就在 bk-repo 界面上可见，
 * 属于用户问了就该答得上来的信息，故予保留。
 *
 * 配额同样留在这里，没有推给 governance 的 `GetRepositoryGovernanceInfoTool`——那个 Agent 默认关闭，
 * 而"这仓库用了多少、还剩多少"是最常见的提问之一，只在 governance 里提供等于默认答不出来。重叠是有意的。
 */
@Suppress("DEPRECATION")
@Component
class GetRepositoryDetailTool(
    private val domainToolGateway: DomainToolGateway,
    private val repositoryClient: RepositoryClient,
) : AbstractReadOnlyDomainTool(
    name = DomainToolNames.GET_REPOSITORY_DETAIL,
    description = "查询指定仓库的详情。参数 repoName 必填；返回仓库类型、类别、是否公开、描述、" +
        "配额上限与已用容量、创建人与最后修改时间。仓库不存在时返回 ok=false。",
    inputSchema = DomainToolSchemas.obj(
        "repoName" to DomainToolSchemas.str("仓库名称"),
        required = listOf("repoName"),
    ),
) {
    override fun execute(param: ToolCallParam): String {
        val repoName = param.input["repoName"]?.toString()?.trim().orEmpty()
        require(repoName.isNotEmpty()) { "repoName is required" }
        val projectId = domainToolGateway.currentProjectId(param.runtimeContext)
        domainToolGateway.requireResourcePermission(
            runtimeContext = param.runtimeContext,
            resourceType = ResourceType.REPO,
            action = PermissionAction.READ,
            repoName = repoName,
        )
        // 项目管理员对不存在的仓库也能通过 IAM 校验，所以 null 是正常可达的结果而不是异常：
        // 回一个 ok=false 让模型能直接告诉用户"没有这个仓库"，比抛异常后只剩一句 upstream_error 有用
        val detail = repositoryClient.getRepoDetail(projectId, repoName).data
            ?: return RepositoryNotFound(projectId = projectId, repoName = repoName).toJsonString()
        return RepositoryDetailResult(
            projectId = projectId,
            name = detail.name,
            type = detail.type.name,
            category = detail.category.name,
            public = detail.public,
            description = detail.description,
            displayName = detail.displayName,
            quota = detail.quota,
            used = detail.used,
            createdBy = detail.createdBy,
            createdDate = detail.createdDate,
            lastModifiedBy = detail.lastModifiedBy,
            lastModifiedDate = detail.lastModifiedDate,
        ).toJsonString()
    }
}

private data class RepositoryDetailResult(
    val projectId: String,
    val name: String,
    val type: String,
    val category: String,
    val public: Boolean,
    val description: String?,
    val displayName: String?,
    val quota: Long?,
    val used: Long?,
    val createdBy: String,
    val createdDate: String,
    val lastModifiedBy: String,
    val lastModifiedDate: String,
    val ok: Boolean = true,
)

private data class RepositoryNotFound(
    val projectId: String,
    val repoName: String,
    val error: String = "repository_not_found",
    val ok: Boolean = false,
)
