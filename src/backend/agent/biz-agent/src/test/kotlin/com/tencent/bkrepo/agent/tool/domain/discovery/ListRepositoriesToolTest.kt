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
import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.LocalConfiguration
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.repository.pojo.repo.RepositoryInfo
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Suppress("DEPRECATION")
@DisplayName("ListRepositoriesTool")
class ListRepositoriesToolTest {

    private val gateway = mock<DomainToolGateway>()
    private val repositoryClient = mock<RepositoryClient>()
    private val tool = ListRepositoriesTool(gateway, repositoryClient)
    private val objectMapper = ObjectMapper()

    @Test
    fun `IAM拒绝时应返回permission_denied错误`() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(gateway.currentUserId(any())).thenReturn("user-1")
        doThrow(PermissionException("no access")).whenever(gateway)
            .requireResourcePermission(any(), any(), any(), anyOrNull(), anyOrNull())

        val result = tool.callAsync(param()).block()!!

        assertTrue(resultText(result).startsWith("Error: permission_denied"))
    }

    @Test
    fun `应只回传有权限的仓库及其摘要字段`() {
        stubContext()
        whenever(repositoryClient.listPermissionRepo(eq("user-1"), eq("project-1"), any()))
            .thenReturn(Response(code = 0, data = listOf(repositoryInfo("custom"), repositoryInfo("pipeline"))))

        val json = objectMapper.readTree(resultText(tool.callAsync(param()).block()!!))

        assertTrue(json.get("ok").asBoolean())
        assertEquals("project-1", json.get("projectId").asText())
        assertEquals(2, json.get("total").asInt())
        val first = json.get("repositories").get(0)
        assertEquals("custom", first.get("name").asText())
        assertEquals("GENERIC", first.get("type").asText())
        assertEquals("LOCAL", first.get("category").asText())
    }

    @Test
    fun `仓库摘要不应包含存储凭证key等内部字段`() {
        stubContext()
        whenever(repositoryClient.listPermissionRepo(any(), any(), any()))
            .thenReturn(Response(code = 0, data = listOf(repositoryInfo("custom"))))

        val text = resultText(tool.callAsync(param()).block()!!)

        assertTrue(!text.contains("storageCredentialsKey"))
        assertTrue(!text.contains("configuration"))
        assertTrue(!text.contains("createdBy"))
    }

    @Test
    fun `项目下没有仓库时应返回总数0而不是报错`() {
        stubContext()
        whenever(repositoryClient.listPermissionRepo(any(), any(), any()))
            .thenReturn(Response(code = 0, data = emptyList()))

        val json = objectMapper.readTree(resultText(tool.callAsync(param()).block()!!))

        assertTrue(json.get("ok").asBoolean())
        assertEquals(0, json.get("total").asInt())
    }

    @Test
    fun `远程调用失败时应返回upstream_error而不是中断整轮对话`() {
        stubContext()
        whenever(repositoryClient.listPermissionRepo(any(), any(), any()))
            .thenThrow(IllegalStateException("repository service unavailable"))

        val result = tool.callAsync(param()).block()!!

        assertTrue(resultText(result).startsWith("Error: upstream_error"))
    }

    private fun stubContext() {
        whenever(gateway.currentProjectId(any())).thenReturn("project-1")
        whenever(gateway.currentUserId(any())).thenReturn("user-1")
    }

    private fun repositoryInfo(name: String) = RepositoryInfo(
        projectId = "project-1",
        name = name,
        type = RepositoryType.GENERIC,
        category = RepositoryCategory.LOCAL,
        public = false,
        description = null,
        configuration = LocalConfiguration(),
        storageCredentialsKey = "storage-credentials-key",
        createdBy = "creator",
        createdDate = "2026-01-01T00:00:00.000",
        lastModifiedBy = "creator",
        lastModifiedDate = "2026-01-01T00:00:00.000",
        quota = null,
        used = null,
        display = true,
    )

    private fun resultText(result: ToolResultBlock): String = (result.output.first() as TextBlock).text

    private fun param(): ToolCallParam = ToolCallParam.builder()
        .toolUseBlock(
            ToolUseBlock.builder()
                .id("call-1")
                .name(DomainToolNames.LIST_REPOSITORIES)
                .build(),
        )
        .input(emptyMap<String, Any>())
        .runtimeContext(RuntimeContext.builder().userId("user-1").sessionId("thread-1").build())
        .build()
}
