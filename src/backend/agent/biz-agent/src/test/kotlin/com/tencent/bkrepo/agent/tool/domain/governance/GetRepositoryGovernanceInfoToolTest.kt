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
@DisplayName("GetRepositoryGovernanceInfoTool")
class GetRepositoryGovernanceInfoToolTest {

    private val gateway = mock<DomainToolGateway>()
    private val repositoryClient = mock<RepositoryClient>()
    private val tool = GetRepositoryGovernanceInfoTool(gateway, repositoryClient)
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
    fun `应返回真实配额与清理策略而不是占位结果`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(eq("project-1"), eq("custom"), anyOrNull()))
            .thenReturn(Response(code = 0, data = repositoryDetail(cleanupStrategy())))

        val json = objectMapper.readTree(resultText(tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!))

        assertTrue(json.get("ok").asBoolean())
        assertEquals("project-1", json.get("projectId").asText())
        assertEquals("custom", json.get("repoName").asText())
        assertEquals(1024L, json.get("quota").asLong())
        assertEquals(512L, json.get("used").asLong())
        val strategy = json.get("cleanupStrategy")
        assertTrue(strategy.get("enable").asBoolean())
        assertEquals("retentionDays", strategy.get("cleanupType").asText())
        assertEquals("30", strategy.get("cleanupValue").asText())
        assertEquals("/logs", strategy.get("cleanTargets").get(0).asText())
    }

    @Test
    fun `未配置清理策略时应返回null而不是报错`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenReturn(Response(code = 0, data = repositoryDetail(null)))

        val json = objectMapper.readTree(resultText(tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!))

        assertTrue(json.get("ok").asBoolean())
        assertTrue(json.get("cleanupStrategy") == null || json.get("cleanupStrategy").isNull)
    }

    /**
     * 清理策略是按键从 configuration.settings 里取的，同一个 map 里的其他条目（远程仓库的上游口令就放在
     * 这里）不能跟着出去。
     */
    @Test
    fun `同在settings里的其他配置项不应被带出`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenReturn(Response(code = 0, data = repositoryDetail(cleanupStrategy())))

        val text = resultText(tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!)

        assertFalse(text.contains("super-secret-proxy-password"))
        assertFalse(text.contains("old-credentials-key"))
    }

    @Test
    fun `仓库不存在时应返回ok为false而不是报错`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenReturn(Response(code = 0, data = null))

        val json = objectMapper.readTree(resultText(tool.callAsync(param(mapOf("repoName" to "ghost"))).block()!!))

        assertFalse(json.get("ok").asBoolean())
        assertEquals("repository_not_found", json.get("error").asText())
    }

    @Test
    fun `远程调用失败时应返回upstream_error而不是中断整轮对话`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(repositoryClient.getRepoDetail(any(), any(), anyOrNull()))
            .thenThrow(IllegalStateException("repository service unavailable"))

        val result = tool.callAsync(param(mapOf("repoName" to "custom"))).block()!!

        assertTrue(resultText(result).startsWith("Error: upstream_error"))
    }

    private fun cleanupStrategy(): Map<String, Any> = mapOf(
        "enable" to true,
        "cleanupType" to "retentionDays",
        "cleanupValue" to "30",
        "cleanTargets" to listOf("/logs"),
    )

    private fun repositoryDetail(cleanupStrategy: Map<String, Any>?) = RepositoryDetail(
        projectId = "project-1",
        name = "custom",
        type = RepositoryType.GENERIC,
        category = RepositoryCategory.LOCAL,
        public = false,
        description = null,
        configuration = LocalConfiguration().apply {
            settings["password"] = "super-secret-proxy-password"
            cleanupStrategy?.let { settings["cleanupStrategy"] = it }
        },
        storageCredentials = null,
        oldCredentialsKey = "old-credentials-key",
        createdBy = "creator",
        createdDate = "2026-01-01T00:00:00.000",
        lastModifiedBy = "creator",
        lastModifiedDate = "2026-01-01T00:00:00.000",
        quota = 1024L,
        used = 512L,
    )

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
