/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.domain.governance

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
 * Governance 专业 Agent：查询单个仓库的治理配置（配额上限与清理策略）。
 *
 * ## 数据来源
 *
 * 配额与已用容量是 `RepositoryDetail` 上的一等字段；清理策略挂在 `configuration.settings` 的
 * `cleanupStrategy` 键下，是一个自由形态的 map。两者同在一次 `getRepoDetail` 里返回，不需要额外调用。
 *
 * ## 为什么原样透传 cleanupType 而不翻译成枚举
 *
 * 取值（`retentionDays` / `retentionDate` / `retentionNums`）的权威定义是 `job` 模块里
 * `ArtifactCleanupJob.CleanupStrategyEnum` 这个嵌套枚举，agent 既够不着也不该为此依赖 `biz-job`。
 * 在这里照抄一份映射，等于埋一个在上游加清理类型时会静默过时的分支——届时新类型会被我们翻译成"未知"，
 * 比直接给出原值更糟。所以原值照出，含义写在工具描述里交给模型解释。
 *
 * ## 出参白名单
 *
 * 同 [com.tencent.bkrepo.agent.tool.domain.discovery.GetRepositoryDetailTool]：`configuration` 整体
 * 绝不能进模型上下文（远程/代理仓库会在 settings 里带上游凭证），这里只按键取 `cleanupStrategy` 一项。
 */
@Suppress("DEPRECATION")
@Component
class GetRepositoryGovernanceInfoTool(
    private val domainToolGateway: DomainToolGateway,
    private val repositoryClient: RepositoryClient,
) : AbstractReadOnlyDomainTool(
    name = DomainToolNames.GET_REPOSITORY_GOVERNANCE_INFO,
    description = "查询指定仓库的治理配置。参数 repoName 必填；返回配额上限 quota 与已用容量 used" +
        "（单位字节，null 表示未设配额），以及清理策略 cleanupStrategy：enable 是否启用，" +
        "cleanupType 为 retentionDays（保留最近若干天）、retentionDate（保留某时间点之后）或 " +
        "retentionNums（保留最近若干个版本），cleanupValue 为对应的天数/时间/个数，" +
        "cleanTargets 为限定的路径或包名。未配置清理策略时 cleanupStrategy 为 null。",
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
        val detail = repositoryClient.getRepoDetail(projectId, repoName).data
            ?: return GovernanceNotFound(projectId = projectId, repoName = repoName).toJsonString()
        return RepositoryGovernanceResult(
            projectId = projectId,
            repoName = detail.name,
            quota = detail.quota,
            used = detail.used,
            cleanupStrategy = detail.configuration.getSetting<Map<String, Any>>(CLEANUP_STRATEGY)
                ?.let { CleanupStrategyView(it) },
        ).toJsonString()
    }

    companion object {
        /** 与 `RepositoryServiceHelper` / `ArtifactCleanupJob` 约定的 settings 键名。 */
        private const val CLEANUP_STRATEGY = "cleanupStrategy"
    }
}

private data class RepositoryGovernanceResult(
    val projectId: String,
    val repoName: String,
    val quota: Long?,
    val used: Long?,
    val cleanupStrategy: CleanupStrategyView?,
    val ok: Boolean = true,
)

/**
 * 清理策略的只读视图。按键取值而不是把 settings 整个序列化出去，免得上游往同一个 map 里塞了别的东西时
 * 跟着漏出来。
 */
private data class CleanupStrategyView(
    val enable: Boolean,
    val cleanupType: String?,
    val cleanupValue: String?,
    val cleanTargets: List<String>?,
) {
    @Suppress("UNCHECKED_CAST")
    constructor(settings: Map<String, Any>) : this(
        enable = settings["enable"] as? Boolean ?: false,
        cleanupType = settings["cleanupType"] as? String,
        cleanupValue = settings["cleanupValue"]?.toString(),
        cleanTargets = settings["cleanTargets"] as? List<String>,
    )
}

private data class GovernanceNotFound(
    val projectId: String,
    val repoName: String,
    val error: String = "repository_not_found",
    val ok: Boolean = false,
)
