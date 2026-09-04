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
import com.tencent.bkrepo.common.security.exception.PermissionException
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@DisplayName("GetRepositoryGovernanceInfoTool")
class GetRepositoryGovernanceInfoToolTest {

    private val gateway = mock<DomainToolGateway>()
    private val tool = GetRepositoryGovernanceInfoTool(gateway)
    private val objectMapper = ObjectMapper()

    @Test
    fun `repoName缺失时应返回invalid_argument错误`() {
        val result = tool.callAsync(param(mapOf())).block()!!

        assertTrue(resultText(result).startsWith("Error: invalid_argument"))
    }

    @Test
    fun `IAM拒绝时应返回permission_denied错误`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        doThrow(PermissionException("no access")).whenever(gateway)
            .requireResourcePermission(any(), any(), any(), any(), anyOrNull())

        val result = tool.callAsync(param(mapOf("repoName" to "repo-1"))).block()!!

        assertTrue(resultText(result).startsWith("Error: permission_denied"))
    }

    @Test
    fun `校验通过时应返回占位治理信息`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")

        val result = tool.callAsync(param(mapOf("repoName" to "repo-1"))).block()!!
        val json = objectMapper.readTree(resultText(result))

        assertEquals("project-1", json.get("projectId").asText())
        assertEquals("repo-1", json.get("repoName").asText())
        assertTrue(json.get("note").asText().contains("pending backend integration"))
    }

    private fun resultText(result: ToolResultBlock): String = (result.output.first() as TextBlock).text

    private fun param(input: Map<String, Any>): ToolCallParam = ToolCallParam.builder()
        .toolUseBlock(
            ToolUseBlock.builder()
                .id("call-1")
                .name(DomainToolNames.GET_REPOSITORY_GOVERNANCE_INFO)
                .build(),
        )
        .input(input)
        .runtimeContext(RuntimeContext.builder().userId("user-1").sessionId("thread-1").build())
        .build()
}
