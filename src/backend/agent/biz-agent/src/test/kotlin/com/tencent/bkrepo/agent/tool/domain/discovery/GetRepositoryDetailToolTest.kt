/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.domain.discovery

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.tool.domain.DomainToolNames
import com.tencent.bkrepo.agent.tool.gateway.DomainToolGateway
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.LocalConfiguration
import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.tool.ToolCallParam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Suppress("DEPRECATION")
@DisplayName("GetRepositoryDetailTool")
class GetRepositoryDetailToolTest {

    private val gateway = mock<DomainToolGateway>()
    private val repositoryClient = mock<RepositoryClient>()
    private val tool = GetRepositoryDetailTool(gateway, repositoryClient)
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
            .requireResourcePermission(any(), any(), any(), anyOrNull(), anyOrNull())

        val result = tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!

        assertTrue(resultText(result).startsWith("Error: permission_denied"))
    }

    @Test
    fun `应返回真实仓库详情而不是占位结果`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(eq("project-1"), eq("custom"), anyOrNull()))
            .thenReturn(Response(code = 0, data = repositoryDetail()))

        val json = objectMapper.readTree(resultText(tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!))

        assertTrue(json.get("ok").asBoolean())
        assertEquals("project-1", json.get("projectId").asText())
        assertEquals("custom", json.get("name").asText())
        assertEquals("GENERIC", json.get("type").asText())
        assertEquals("LOCAL", json.get("category").asText())
        assertEquals(1024L, json.get("quota").asLong())
        assertEquals(512L, json.get("used").asLong())
        assertEquals("creator", json.get("createdBy").asText())
    }

    /**
     * 这条是本工具最重要的约束：RepositoryDetail 上挂着存储凭证与仓库配置，后者对远程/代理仓库会带上游
     * 口令。工具结果会原样进入对话历史并落 agent_message 归档，漏一次就是长期泄漏。
     */
    @Test
    fun `结果不应包含存储凭证与仓库配置`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenReturn(Response(code = 0, data = repositoryDetail()))

        val text = resultText(tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!)

        assertFalse(text.contains("super-secret-proxy-password"))
        assertFalse(text.contains("old-credentials-key"))
        assertFalse(text.contains("configuration"))
        assertFalse(text.contains("storageCredentials"))
    }

    @Test
    fun `仓库不存在时应返回ok为false而不是报错`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenReturn(Response(code = 0, data = null))

        val json = objectMapper.readTree(resultText(tool.callAsync(param(mapOf("repoName" to "ghost"))).block()!!))

        assertFalse(json.get("ok").asBoolean())
        assertEquals("repository_not_found", json.get("error").asText())
        assertEquals("ghost", json.get("repoName").asText())
    }

    @Test
    fun `远程调用失败时应返回upstream_error而不是中断整轮对话`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenThrow(IllegalStateException("repository service unavailable"))

        val result = tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!

        assertTrue(resultText(result).startsWith("Error: upstream_error"))
    }

    private fun repositoryDetail() = RepositoryDetail(
        projectId = "project-1",
        name = "custom",
        type = RepositoryType.GENERIC,
        category = RepositoryCategory.LOCAL,
        public = false,
        description = "通用仓库",
        configuration = LocalConfiguration().apply { settings["password"] = "super-secret-proxy-password" },
        storageCredentials = null,
        oldCredentialsKey = "old-credentials-key",
        createdBy = "creator",
        createdDate = "2026-01-01T00:00:00.000",
        lastModifiedBy = "editor",
        lastModifiedDate = "2026-02-01T00:00:00.000",
        quota = 1024L,
        used = 512L,
    )

    private fun resultText(result: ToolResultBlock): String = (result.output.first() as TextBlock).text

    private fun param(input: Map<String, Any>): ToolCallParam = ToolCallParam.builder()
        .toolUseBlock(
            ToolUseBlock.builder()
                .id("call-1")
                .name(DomainToolNames.GET_REPOSITORY_DETAIL)
                .build(),
        )
        .input(input)
        .runtimeContext(RuntimeContext.builder().userId("user-1").sessionId("thread-1").build())
        .build()
}
