/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.webhook.service

import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.artifact.event.base.EventType
import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.webhook.UT_LONG_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_MASKED_LONG_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_MASKED_SHORT_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_PROJECT_ID
import com.tencent.bkrepo.webhook.UT_SHORT_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_USER
import com.tencent.bkrepo.webhook.constant.AssociationType
import com.tencent.bkrepo.webhook.constant.WebHookRequestStatus
import com.tencent.bkrepo.webhook.dao.WebHookDao
import com.tencent.bkrepo.webhook.dao.WebHookLogDao
import com.tencent.bkrepo.webhook.model.TWebHookLog
import com.tencent.bkrepo.webhook.pojo.CreateWebHookRequest
import com.tencent.bkrepo.webhook.pojo.UpdateWebHookRequest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.mongodb.core.query.Query
import java.time.LocalDateTime

@DataMongoTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebHookServiceTest @Autowired constructor(
    private val webHookService: WebHookService,
    private val webHookDao: WebHookDao,
    private val webHookLogDao: WebHookLogDao
) : ServiceBaseTest() {

    @BeforeAll
    fun init() {
        initMock()
    }

    @BeforeEach
    fun beforeEach() {
        webHookDao.remove(Query())
        reset(permissionManager)
    }

    @Test
    @DisplayName("创建WebHook测试")
    fun create() {
        val request = CreateWebHookRequest(
            url = "https://webhook.example.com",
            headers = mapOf("key" to "value"),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        val webhook = webHookService.createWebHook(UT_USER, request)
        Assertions.assertEquals(webhook.associationId, UT_PROJECT_ID)
        verify(permissionManager).checkProjectPermission(eq(PermissionAction.MANAGE), eq(UT_PROJECT_ID), any())
    }

    @Test
    @DisplayName("查询WebHook时对 headers 值做掩码")
    fun `getWebHook masks header values`() {
        val request = CreateWebHookRequest(
            url = "https://webhook.example.com",
            headers = mapOf(
                "authorization" to UT_SHORT_HEADER_VALUE,
                "x-token" to UT_LONG_HEADER_VALUE
            ),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        val created = webHookService.createWebHook(UT_USER, request)
        val webhook = webHookService.getWebHook(UT_USER, created.id)

        Assertions.assertEquals(UT_MASKED_SHORT_HEADER_VALUE, webhook.headers?.get("authorization"))
        Assertions.assertEquals(UT_MASKED_LONG_HEADER_VALUE, webhook.headers?.get("x-token"))
        val stored = webHookDao.findById(created.id)
        Assertions.assertEquals(UT_SHORT_HEADER_VALUE, stored?.headers?.get("authorization"))
        Assertions.assertEquals(UT_LONG_HEADER_VALUE, stored?.headers?.get("x-token"))
    }

    @Test
    @DisplayName("更新时回传掩码请求头应保留原始值")
    fun `updateWebHook keeps original headers when masked values are submitted`() {
        val request = CreateWebHookRequest(
            url = "https://webhook.example.com",
            headers = mapOf(
                "authorization" to UT_SHORT_HEADER_VALUE,
                "x-token" to UT_LONG_HEADER_VALUE
            ),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        val created = webHookService.createWebHook(UT_USER, request)
        webHookService.updateWebHook(
            UT_USER,
            UpdateWebHookRequest(
                id = created.id,
                url = "https://webhook2.example.com",
                headers = created.headers
            )
        )

        val stored = webHookDao.findById(created.id)
        Assertions.assertEquals("https://webhook2.example.com", stored?.url)
        Assertions.assertEquals(UT_SHORT_HEADER_VALUE, stored?.headers?.get("authorization"))
        Assertions.assertEquals(UT_LONG_HEADER_VALUE, stored?.headers?.get("x-token"))
    }

    @Test
    @DisplayName("查询WebHook列表")
    fun list() {
        val list = webHookService.listWebHook(UT_USER, AssociationType.PROJECT, UT_PROJECT_ID)
        Assertions.assertEquals(list.size, 0)
    }

    @Test
    @DisplayName("删除WebHook")
    fun delete() {
        val request = CreateWebHookRequest(
            url = "https://webhook.example.com",
            headers = mapOf("key" to "value"),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        val webhook = webHookService.createWebHook(UT_USER, request)
        webHookService.deleteWebHook(UT_USER, webhook.id)
    }

    @Test
    @DisplayName("更新WebHook")
    fun update() {
        val createWebHookRequest = CreateWebHookRequest(
            url = "https://webhook.example.com",
            headers = mapOf("key" to "value"),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        var webhook = webHookService.createWebHook(UT_USER, createWebHookRequest)
        val updateWebHookRequest = UpdateWebHookRequest(webhook.id, "https://webhook2.example.com")
        webhook = webHookService.updateWebHook(UT_USER, updateWebHookRequest)
        Assertions.assertEquals(webhook.url, "https://webhook2.example.com")
    }

    @Test
    @DisplayName("无权限时拒绝重试WebHook请求")
    fun `retryWebHookRequest denies user without permission`() {
        val webhook = webHookService.createWebHook(
            UT_USER,
            CreateWebHookRequest(
                url = "https://webhook.example.com",
                headers = mapOf("key" to "value"),
                triggers = listOf(EventType.NODE_CREATED),
                associationType = AssociationType.PROJECT,
                associationId = UT_PROJECT_ID
            )
        )
        val log = webHookLogDao.insert(
            TWebHookLog(
                webHookId = webhook.id,
                webHookUrl = webhook.url,
                triggeredEvent = EventType.NODE_CREATED,
                requestHeaders = emptyMap(),
                requestPayload = "{}",
                status = WebHookRequestStatus.FAIL,
                requestDuration = 0L,
                requestTime = LocalDateTime.now()
            )
        )
        whenever(permissionManager.checkProjectPermission(any(), any(), any())).thenThrow(PermissionException())

        assertThrows<PermissionException> {
            webHookService.retryWebHookRequest(log.id.orEmpty())
        }
    }

    @Test
    @DisplayName("创建WebHook时拒绝不带域名格式的地址(SSRF)")
    fun `createWebHook rejects url without domain format`() {
        val request = CreateWebHookRequest(
            url = "https://localhost",
            headers = mapOf("key" to "value"),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        assertThrows<ErrorCodeException> {
            webHookService.createWebHook(UT_USER, request)
        }
    }

    @Test
    @DisplayName("创建WebHook时拒绝非http(s)协议的地址(SSRF)")
    fun `createWebHook rejects non http scheme url`() {
        val request = CreateWebHookRequest(
            url = "file:///etc/passwd",
            headers = mapOf("key" to "value"),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
        assertThrows<ErrorCodeException> {
            webHookService.createWebHook(UT_USER, request)
        }
    }

    @Test
    @DisplayName("更新WebHook时拒绝不带域名格式的地址(SSRF)")
    fun `updateWebHook rejects url without domain format`() {
        val created = webHookService.createWebHook(
            UT_USER,
            CreateWebHookRequest(
                url = "https://webhook.example.com",
                headers = mapOf("key" to "value"),
                triggers = listOf(EventType.NODE_CREATED),
                associationType = AssociationType.PROJECT,
                associationId = UT_PROJECT_ID
            )
        )
        assertThrows<ErrorCodeException> {
            webHookService.updateWebHook(UT_USER, UpdateWebHookRequest(created.id, "https://localhost"))
        }
    }

    @Test
    @DisplayName("创建WebHook时拒绝内网/回环字面量IP地址(SSRF)")
    fun `createWebHook rejects private ip literal url`() {
        listOf("http://127.0.0.1", "http://10.0.0.1", "http://169.254.169.254").forEach { url ->
            val request = CreateWebHookRequest(
                url = url,
                headers = mapOf("key" to "value"),
                triggers = listOf(EventType.NODE_CREATED),
                associationType = AssociationType.PROJECT,
                associationId = UT_PROJECT_ID
            )
            assertThrows<ErrorCodeException> {
                webHookService.createWebHook(UT_USER, request)
            }
        }
    }
}
