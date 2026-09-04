/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.domain.governance

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.gateway.DomainToolGateway
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.tool.ToolCallParam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@DisplayName("ExplainRepositoryPermissionTool")
class ExplainRepositoryPermissionToolTest {

    private val gateway = mock<DomainToolGateway>()
    private val tool = ExplainRepositoryPermissionTool(gateway)
    private val objectMapper = ObjectMapper()

    @Test
    fun `repoName缺失时应返回invalid_argument错误`() {
        val result = tool.callAsync(param(mapOf())).block()!!

        assertTrue(resultText(result).startsWith("Error: invalid_argument"))
    }

    @Test
    fun `action非法时应返回invalid_argument错误`() {
        val result = tool.callAsync(param(mapOf("repoName" to "repo-1", "action" to "FOO"))).block()!!

        val text = resultText(result)
        assertTrue(text.startsWith("Error: invalid_argument"))
        assertTrue(text.contains("invalid action"))
    }

    @Test
    fun `未传action时应默认按READ判定并返回ALLOW`() {
        whenever(
            gateway.explainResourcePermission(
                any(),
                eq(ResourceType.REPO),
                eq(PermissionAction.READ),
                eq("repo-1"),
                anyOrNull(),
            ),
        ).thenReturn(
            DomainToolGateway.PermissionExplanation(
                allowed = true,
                userId = "user-1",
                projectId = "project-1",
                resourceType = ResourceType.REPO,
                action = PermissionAction.READ,
                repoName = "repo-1",
                path = null,
            ),
        )

        val result = tool.callAsync(param(mapOf("repoName" to "repo-1"))).block()!!
        val json = objectMapper.readTree(resultText(result))

        assertEquals("ALLOW", json.get("decision").asText())
        assertEquals("project-1", json.get("projectId").asText())
        assertEquals("READ", json.get("action").asText())
    }

    @Test
    fun `IAM拒绝时应返回DENY而不是抛异常`() {
        whenever(
            gateway.explainResourcePermission(any(), any(), any(), any(), anyOrNull()),
        ).thenReturn(
            DomainToolGateway.PermissionExplanation(
                allowed = false,
                userId = "user-1",
                projectId = "project-1",
                resourceType = ResourceType.REPO,
                action = PermissionAction.DELETE,
                repoName = "repo-1",
                path = null,
            ),
        )

        val result = tool.callAsync(param(mapOf("repoName" to "repo-1", "action" to "delete"))).block()!!
        val json = objectMapper.readTree(resultText(result))

        assertEquals("DENY", json.get("decision").asText())
    }

    private fun resultText(result: ToolResultBlock): String = (result.output.first() as TextBlock).text

    private fun param(input: Map<String, Any>): ToolCallParam = ToolCallParam.builder()
        .toolUseBlock(
            ToolUseBlock.builder()
                .id("call-1")
                .name(DomainToolNames.EXPLAIN_REPOSITORY_PERMISSION)
                .build(),
        )
        .input(input)
        .runtimeContext(RuntimeContext.builder().userId("user-1").sessionId("thread-1").build())
        .build()
}
