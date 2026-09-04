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
import io.agentscope.core.tool.ToolCallParam
import org.springframework.stereotype.Component

/**
 * 查询指定仓库的治理配置（配额上限、保留策略等）。
 *
 * 与 [com.tencent.bkrepo.agent.tool.domain.discovery.GetRepositoryDetailTool] 同构：先走真实 IAM
 * 校验调用方是否有权查看该仓库，再返回结构化结果。治理配置（配额/保留策略）目前分散在仓库服务的
 * 独立接口里，agent 服务尚未接入对应 client，先返回占位结果并显式标注 "pending backend
 * integration"，后续接入后只需替换 execute() 内的数据来源，不影响工具契约（name/inputSchema/权限）。
 */
@Component
class GetRepositoryGovernanceInfoTool(
    private val domainToolGateway: DomainToolGateway,
) : AbstractReadOnlyDomainTool(
    name = DomainToolNames.GET_REPOSITORY_GOVERNANCE_INFO,
    description = "查询指定仓库的治理配置，包括配额上限与保留策略。参数 repoName 必填；" +
        "返回 projectId、repoName 及治理配置字段。",
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
        return """{"ok":true,"projectId":"$projectId","repoName":"$repoName",""" +
            """"quota":null,"retentionPolicy":null,"note":"repository governance info pending backend integration"}"""
    }
}
