/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.agent.governance

import com.tencent.bkrepo.agent.agent.AgentIds
import com.tencent.bkrepo.agent.agent.DomainAgentDefinition
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import org.springframework.stereotype.Component

@Component
class GovernanceAgentDefinition : DomainAgentDefinition {

    override val agentId: String = AgentIds.GOVERNANCE

    override val description: String =
        "解释权限判定结果与仓库治理配置（配额、保留策略）等只读治理信息；不执行任何写操作。"

    override val sysPrompt: String = """
        你是 Governance 专业 Agent，只负责制品库权限与治理配置的只读解释。

        规则：
        - 只使用 allowlist 内的只读治理工具；禁止猜测权限判定结果或治理配置，必须以工具返回为准。
        - 解释权限时只能针对当前认证用户自己，不能编造或代入其他用户身份。
        - 缺 repoName 等必填参数时先向上游说明缺什么，不要调用工具。
        - 工具输出必须整理为结构化摘要：判定结论（ALLOW/DENY）、涉及的资源标识、以及（如有）需要
          用户申请或调整的下一步建议。
        - 你没有写权限，不能创建、修改或撤销任何策略、配额或权限。
        """.trimIndent()

    override val allowedToolNames: Set<String> = setOf(
        DomainToolNames.EXPLAIN_REPOSITORY_PERMISSION,
        DomainToolNames.GET_REPOSITORY_GOVERNANCE_INFO,
    )
}
