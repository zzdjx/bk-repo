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
 * Governance 专业 Agent 的核心工具：直接走真实 IAM 判定当前用户对某仓库某操作是否有权限，
 * 并把判定依据整理成结构化结果——不像其它只读工具那样在 DENY 时抛异常拦截调用，因为"解释
 * 权限判定结果"本身就是这个工具存在的意义。
 *
 * 只解释调用方自己的权限（userId 取自 [io.agentscope.core.agent.RuntimeContext]，不接受模型传参
 * 覆盖），避免被诱导查询他人权限造成越权信息泄露。
 */
@Component
class ExplainRepositoryPermissionTool(
    private val domainToolGateway: DomainToolGateway,
) : AbstractReadOnlyDomainTool(
    name = DomainToolNames.EXPLAIN_REPOSITORY_PERMISSION,
    description = "解释当前用户对指定仓库执行某个操作是否有权限。参数 repoName 必填；" +
        "action 可选（READ/WRITE/DELETE/MANAGE/VIEW/UPDATE/DOWNLOAD，默认 READ）；" +
        "path 可选，用于按路径细粒度判定。返回 ALLOW/DENY 及判定所用的资源标识。",
    inputSchema = DomainToolSchemas.obj(
        "repoName" to DomainToolSchemas.str("仓库名称"),
        "action" to DomainToolSchemas.str(
            "待判定的操作，取值 READ/WRITE/DELETE/MANAGE/VIEW/UPDATE/DOWNLOAD，缺省为 READ",
        ),
        "path" to DomainToolSchemas.str("可选的节点路径，用于按路径细粒度判定"),
        required = listOf("repoName"),
    ),
) {
    override fun execute(param: ToolCallParam): String {
        val repoName = param.input["repoName"]?.toString()?.trim().orEmpty()
        require(repoName.isNotEmpty()) { "repoName is required" }
        val path = param.input["path"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val action = resolveAction(param.input["action"]?.toString())

        val explanation = domainToolGateway.explainResourcePermission(
            runtimeContext = param.runtimeContext,
            resourceType = ResourceType.REPO,
            action = action,
            repoName = repoName,
            path = path,
        )
        val decision = if (explanation.allowed) "ALLOW" else "DENY"
        val pathJson = path?.let { "\"$it\"" } ?: "null"
        return """{"ok":true,"userId":"${explanation.userId}","projectId":"${explanation.projectId}",""" +
            """"repoName":"$repoName","action":"${action.name}","path":$pathJson,"decision":"$decision"}"""
    }

    private fun resolveAction(rawAction: String?): PermissionAction {
        val normalized = rawAction?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) {
            return PermissionAction.READ
        }
        return PermissionAction.entries.find { it.name == normalized }
            ?: throw IllegalArgumentException(
                "invalid action '$rawAction', expected one of ${PermissionAction.entries.map { it.name }}",
            )
    }
}
