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

package com.tencent.bkrepo.webhook.service.impl

import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.util.Preconditions
import com.tencent.bkrepo.common.api.util.checkurl.SecUrlValidator
import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.common.artifact.event.base.ArtifactEvent
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import com.tencent.bkrepo.common.security.permission.PrincipalType
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.webhook.config.WebHookProperties
import com.tencent.bkrepo.webhook.constant.AssociationType
import com.tencent.bkrepo.webhook.constant.WebHookRequestStatus
import com.tencent.bkrepo.webhook.dao.WebHookDao
import com.tencent.bkrepo.webhook.dao.WebHookLogDao
import com.tencent.bkrepo.webhook.event.WebHookTestEvent
import com.tencent.bkrepo.webhook.exception.WebHookMessageCode
import com.tencent.bkrepo.webhook.executor.WebHookExecutor
import com.tencent.bkrepo.webhook.model.TWebHook
import com.tencent.bkrepo.webhook.payload.EventPayloadFactory
import com.tencent.bkrepo.webhook.pojo.CreateWebHookRequest
import com.tencent.bkrepo.webhook.pojo.UpdateWebHookRequest
import com.tencent.bkrepo.webhook.pojo.WebHook
import com.tencent.bkrepo.webhook.pojo.WebHookLog
import com.tencent.bkrepo.webhook.service.WebHookService
import com.tencent.bkrepo.webhook.util.restoreUnchangedHeaderValues
import com.tencent.bkrepo.webhook.util.toWebHook
import com.tencent.bkrepo.webhook.util.toWebHookLog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class WebHookServiceImpl(
    private val webHookDao: WebHookDao,
    private val webHookLogDao: WebHookLogDao,
    private val webHookExecutor: WebHookExecutor,
    private val permissionManager: PermissionManager,
    private val eventPayloadFactory: EventPayloadFactory,
    private val webHookProperties: WebHookProperties
) : WebHookService {

    override fun createWebHook(userId: String, request: CreateWebHookRequest): WebHook {
        logger.info("create webhook, userId: $userId, request: $request")
        with(request) {
            checkPermission(userId, associationType, associationId)
            val validatedUrl = SecUrlValidator.validateOrThrow(url, webHookProperties.callbackUrl, "url")
            val webHook = TWebHook(
                url = validatedUrl,
                headers = headers,
                triggers = triggers,
                associationType = associationType,
                associationId = associationId,
                resourceKeyPattern = resourceKeyPattern,
                createdBy = userId,
                createdDate = LocalDateTime.now(),
                lastModifiedBy = userId,
                lastModifiedDate = LocalDateTime.now()
            )
            val tWebHook = webHookDao.insert(webHook)
            return tWebHook.toWebHook()
        }
    }

    override fun updateWebHook(userId: String, request: UpdateWebHookRequest): WebHook {
        logger.info("update webhook, userId: $userId, request: $request")
        val webHook = findWebHookById(request.id)
        checkPermission(userId, webHook.associationType, webHook.associationId)
        webHook.url = request.url?.let { SecUrlValidator.validateOrThrow(it, webHookProperties.callbackUrl, "url") }
            ?: webHook.url
        webHook.triggers = request.triggers ?: webHook.triggers
        webHook.headers = request.headers.restoreUnchangedHeaderValues(webHook.headers)
        webHook.resourceKeyPattern = request.resourceKeyPattern ?: webHook.resourceKeyPattern
        webHook.lastModifiedBy = userId
        webHook.lastModifiedDate = LocalDateTime.now()
        val tWebHook = webHookDao.save(webHook)
        return tWebHook.toWebHook()
    }

    override fun deleteWebHook(userId: String, id: String) {
        logger.info("delete webhook, userId: $userId, id: $id")
        val webHook = findWebHookById(id)
        checkPermission(userId, webHook.associationType, webHook.associationId)
        webHookDao.removeById(id)
    }

    override fun getWebHook(userId: String, id: String): WebHook {
        logger.info("get webhook, userId: $userId, id: $id")
        val webHook = findWebHookById(id)
        checkPermission(userId, webHook.associationType, webHook.associationId)
        return webHook.toWebHook()
    }

    override fun listWebHook(userId: String, associationType: AssociationType, associationId: String?): List<WebHook> {
        logger.info("list webhook, userId: $userId, type: $associationType, id: $associationId")
        checkPermission(userId, associationType, associationId)
        val webHookList = webHookDao.findByAssociationTypeAndAssociationId(associationType, associationId)
        return webHookList.map { it.toWebHook() }
    }

    override fun testWebHook(userId: String, id: String): WebHookLog {
        logger.info("test webhook, userId: $userId, id: $id")
        val webHook = findWebHookById(id)
        val (projectId, repoName) = checkPermission(userId, webHook.associationType, webHook.associationId)
        val event = WebHookTestEvent(projectId, repoName, "test", userId, webHook.toWebHook())
        return webHookExecutor.execute(event, webHook).toWebHookLog()
    }

    override fun retryWebHookRequest(logId: String): WebHookLog {
        val userId = SecurityUtils.getUserId()
        logger.info("retry webhook, userId: $userId, logId: $logId")
        val log = webHookLogDao.findById(logId)
            ?: throw ErrorCodeException(WebHookMessageCode.WEBHOOK_LOG_NOT_FOUND)
        val webHook = webHookDao.findById(log.webHookId)
            ?: throw ErrorCodeException(WebHookMessageCode.WEBHOOK_NOT_FOUND)
        checkPermission(userId, webHook.associationType, webHook.associationId)
        val payload = if (log.status == WebHookRequestStatus.ERROR) {
            val event = log.requestPayload.readJsonString<ArtifactEvent>()
            eventPayloadFactory.build(event)
        } else {
            log.requestPayload.readJsonString()
        }
        return webHookExecutor.execute(payload, webHook).toWebHookLog()
    }

    private fun findWebHookById(id: String): TWebHook {
        return webHookDao.findById(id)
            ?: throw ErrorCodeException(WebHookMessageCode.WEBHOOK_NOT_FOUND)
    }

    private fun checkPermission(userId: String, type: AssociationType, associationId: String?): Pair<String, String> {
        val projectId: String
        val repoName: String
        when (type) {
            AssociationType.SYSTEM -> {
                projectId = ""
                repoName = ""
                permissionManager.checkPrincipal(userId, PrincipalType.ADMIN)
            }
            AssociationType.PROJECT -> {
                Preconditions.checkNotNull(associationId, "associationId")
                projectId = associationId!!
                repoName = ""
                permissionManager.checkProjectPermission(PermissionAction.MANAGE, projectId)
            }
            AssociationType.REPO -> {
                Preconditions.checkArgument(associationId?.split(StringPool.COLON)?.size == 2, "associationId")
                projectId = associationId!!.split(StringPool.COLON).first()
                repoName = associationId.split(StringPool.COLON).last()
                permissionManager.checkRepoPermission(PermissionAction.WRITE, projectId, repoName)
            }
        }
        return Pair(projectId, repoName)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebHookServiceImpl::class.java)
    }
}
