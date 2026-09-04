/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.gateway

import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_PROJECT_ID
import com.tencent.bkrepo.auth.api.ServicePermissionClient
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.common.security.http.core.HttpAuthProperties
import io.agentscope.core.agent.RuntimeContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@DisplayName("DomainToolGateway 权限判定")
class DomainToolGatewayTest {

    private val permissionClient = mock<ServicePermissionClient>()

    @Test
    fun `未开启鉴权时explain应直接放行且不调用IAM`() {
        val gateway = gateway(HttpAuthProperties(enabled = false))

        val explanation = gateway.explainResourcePermission(
            runtimeContext = runtimeContext(),
            resourceType = ResourceType.REPO,
            action = PermissionAction.READ,
            repoName = "repo-1",
        )

        assertTrue(explanation.allowed)
        assertEquals("user-1", explanation.userId)
        assertEquals("project-1", explanation.projectId)
        verifyNoInteractions(permissionClient)
    }

    @Test
    fun `IAM放行时explain应返回allowed为true`() {
        whenever(permissionClient.checkPermission(any())).thenReturn(Response(0, null, true))
        val gateway = gateway(HttpAuthProperties(enabled = true))

        val explanation = gateway.explainResourcePermission(
            runtimeContext = runtimeContext(),
            resourceType = ResourceType.REPO,
            action = PermissionAction.WRITE,
            repoName = "repo-1",
            path = "/a/b",
        )

        assertTrue(explanation.allowed)
        assertEquals(PermissionAction.WRITE, explanation.action)
        assertEquals("/a/b", explanation.path)
    }

    @Test
    fun `IAM拒绝时explain应返回allowed为false而不是抛异常`() {
        whenever(permissionClient.checkPermission(any())).thenReturn(Response(0, null, false))
        val gateway = gateway(HttpAuthProperties(enabled = true))

        val explanation = gateway.explainResourcePermission(
            runtimeContext = runtimeContext(),
            resourceType = ResourceType.REPO,
            action = PermissionAction.DELETE,
            repoName = "repo-1",
        )

        assertFalse(explanation.allowed)
    }

    @Test
    fun `explain缺少认证用户时应抛异常`() {
        val gateway = gateway(HttpAuthProperties(enabled = true))
        val anonymousContext = RuntimeContext.builder()
            .put(RUNTIME_CONTEXT_PROJECT_ID, "project-1")
            .build()

        assertThrows(PermissionException::class.java) {
            gateway.explainResourcePermission(
                runtimeContext = anonymousContext,
                resourceType = ResourceType.REPO,
                action = PermissionAction.READ,
                repoName = "repo-1",
            )
        }
    }

    @Test
    fun `requireResourcePermission在IAM拒绝时应抛异常`() {
        whenever(permissionClient.checkPermission(any())).thenReturn(Response(0, null, false))
        val gateway = gateway(HttpAuthProperties(enabled = true))

        assertThrows(PermissionException::class.java) {
            gateway.requireResourcePermission(
                runtimeContext = runtimeContext(),
                resourceType = ResourceType.REPO,
                action = PermissionAction.READ,
                repoName = "repo-1",
            )
        }
    }

    private fun gateway(httpAuthProperties: HttpAuthProperties): DomainToolGateway =
        DomainToolGateway(permissionClient, httpAuthProperties)

    private fun runtimeContext(): RuntimeContext = RuntimeContext.builder()
        .userId("user-1")
        .sessionId("thread-1")
        .put(RUNTIME_CONTEXT_PROJECT_ID, "project-1")
        .build()
}
