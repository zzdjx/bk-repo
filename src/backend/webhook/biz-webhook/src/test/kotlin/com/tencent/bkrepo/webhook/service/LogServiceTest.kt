package com.tencent.bkrepo.webhook.service

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
import com.tencent.bkrepo.webhook.pojo.ListWebHookLogOption
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import java.time.LocalDateTime

@DataMongoTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WebHook日志服务测试")
class LogServiceTest @Autowired constructor(
    private val logService: LogService,
    private val webHookService: WebHookService,
    private val webHookDao: WebHookDao,
    private val webHookLogDao: WebHookLogDao
) : ServiceBaseTest() {

    private var requestTime: LocalDateTime? = null

    @BeforeAll
    fun init() {
        initMock()
    }

    @BeforeEach
    fun beforeEach() {
        webHookDao.remove(Query())
        reset(permissionManager)
    }

    @AfterEach
    fun afterEach() {
        requestTime?.let {
            webHookLogDao.remove(Query(Criteria.where(TWebHookLog::requestTime.name).isEqualTo(it)))
        }
        requestTime = null
    }

    @Test
    @DisplayName("查询日志时对 requestHeaders 值做掩码")
    fun `listLog masks request header values`() {
        val webhook = createProjectWebHook()
        val logRequestTime = LocalDateTime.now()
        requestTime = logRequestTime
        webHookLogDao.insert(
            TWebHookLog(
                webHookId = webhook.id,
                webHookUrl = "https://localhost",
                triggeredEvent = EventType.NODE_CREATED,
                requestHeaders = mapOf(
                    "authorization" to UT_SHORT_HEADER_VALUE,
                    "x-token" to UT_LONG_HEADER_VALUE
                ),
                requestPayload = "{}",
                status = WebHookRequestStatus.SUCCESS,
                requestDuration = 10L,
                requestTime = logRequestTime
            )
        )

        val page = logService.listLog(
            UT_USER,
            webhook.id,
            ListWebHookLogOption(
                startDate = logRequestTime.minusMinutes(1).toString(),
                endDate = logRequestTime.plusMinutes(1).toString(),
                status = null
            )
        )

        Assertions.assertEquals(1, page.records.size)
        val headers = page.records.first().requestHeaders
        Assertions.assertEquals(UT_MASKED_SHORT_HEADER_VALUE, headers["authorization"])
        Assertions.assertEquals(UT_MASKED_LONG_HEADER_VALUE, headers["x-token"])
    }

    @Test
    @DisplayName("查询日志时对 payload 内嵌 headers 做掩码")
    fun `listLog masks embedded webhook headers in requestPayload`() {
        val webhook = createProjectWebHook()
        val logRequestTime = LocalDateTime.now()
        requestTime = logRequestTime
        webHookLogDao.insert(
            TWebHookLog(
                webHookId = webhook.id,
                webHookUrl = "https://localhost",
                triggeredEvent = EventType.WEBHOOK_TEST,
                requestHeaders = emptyMap(),
                requestPayload = buildEmbeddedHeaderPayload(),
                status = WebHookRequestStatus.SUCCESS,
                requestDuration = 10L,
                requestTime = logRequestTime
            )
        )

        val page = logService.listLog(
            UT_USER,
            webhook.id,
            ListWebHookLogOption(
                startDate = logRequestTime.minusMinutes(1).toString(),
                endDate = logRequestTime.plusMinutes(1).toString(),
                status = null
            )
        )

        Assertions.assertEquals(1, page.records.size)
        val payload = page.records.first().requestPayload
        Assertions.assertFalse(payload.contains(UT_SHORT_HEADER_VALUE))
        Assertions.assertFalse(payload.contains(UT_LONG_HEADER_VALUE))
        Assertions.assertTrue(payload.contains(UT_MASKED_SHORT_HEADER_VALUE))
        Assertions.assertTrue(payload.contains(UT_MASKED_LONG_HEADER_VALUE))
    }

    @Test
    @DisplayName("无权限时拒绝查询WebHook日志")
    fun `listLog denies user without permission`() {
        val webhook = createProjectWebHook()
        whenever(permissionManager.checkProjectPermission(any(), any(), any())).thenThrow(PermissionException())

        assertThrows<PermissionException> {
            logService.listLog(
                UT_USER,
                webhook.id,
                ListWebHookLogOption(startDate = null, endDate = null, status = null)
            )
        }
    }

    private fun createProjectWebHook() = webHookService.createWebHook(
        UT_USER,
        CreateWebHookRequest(
            url = "https://webhook.example.com",
            headers = mapOf("key" to "value"),
            triggers = listOf(EventType.NODE_CREATED),
            associationType = AssociationType.PROJECT,
            associationId = UT_PROJECT_ID
        )
    )

    private fun buildEmbeddedHeaderPayload(): String {
        val headers = """{"authorization":"$UT_SHORT_HEADER_VALUE","x-token":"$UT_LONG_HEADER_VALUE"}"""
        return """{"webHook":{"headers":$headers}}"""
    }
}
