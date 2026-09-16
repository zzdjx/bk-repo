package com.tencent.bkrepo.webhook.util

import com.tencent.bkrepo.common.artifact.event.base.EventType
import com.tencent.bkrepo.webhook.UT_LONG_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_MASKED_LONG_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_MASKED_SHORT_HEADER_VALUE
import com.tencent.bkrepo.webhook.UT_SHORT_HEADER_VALUE
import com.tencent.bkrepo.webhook.constant.WebHookRequestStatus
import com.tencent.bkrepo.webhook.model.TWebHookLog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@DisplayName("WebHook转换掩码测试")
class WebHookConvertersTest {

    @Test
    @DisplayName("日志转换时对 payload 内嵌 headers 做掩码")
    fun `toWebHookLog masks embedded webhook headers in requestPayload`() {
        val headers = """{"authorization":"$UT_SHORT_HEADER_VALUE","x-token":"$UT_LONG_HEADER_VALUE"}"""
        val log = buildLog(
            triggeredEvent = EventType.WEBHOOK_TEST,
            requestPayload = """{"webHook":{"headers":$headers}}"""
        )

        val payload = log.toWebHookLog().requestPayload
        Assertions.assertFalse(payload.contains(UT_SHORT_HEADER_VALUE))
        Assertions.assertFalse(payload.contains(UT_LONG_HEADER_VALUE))
        Assertions.assertTrue(payload.contains(UT_MASKED_SHORT_HEADER_VALUE))
        Assertions.assertTrue(payload.contains(UT_MASKED_LONG_HEADER_VALUE))
    }

    @Test
    @DisplayName("日志转换时对事件 data 内嵌 headers 做掩码")
    fun `toWebHookLog masks embedded headers under event data`() {
        val log = buildLog(
            triggeredEvent = EventType.WEBHOOK_TEST,
            requestPayload = """{"data":{"webHook":{"headers":{"authorization":"$UT_SHORT_HEADER_VALUE"}}}}"""
        )

        val payload = log.toWebHookLog().requestPayload
        Assertions.assertFalse(payload.contains(UT_SHORT_HEADER_VALUE))
        Assertions.assertTrue(payload.contains(UT_MASKED_SHORT_HEADER_VALUE))
    }

    @Test
    @DisplayName("普通事件 payload 保持原样")
    fun `toWebHookLog keeps non webhook payloads unchanged`() {
        val payload = """{"packageKey":"npm://foo","packageVersion":"1.0.0"}"""
        val log = buildLog(triggeredEvent = EventType.NODE_CREATED, requestPayload = payload)

        Assertions.assertEquals(payload, log.toWebHookLog().requestPayload)
    }

    private fun buildLog(triggeredEvent: EventType, requestPayload: String): TWebHookLog {
        return TWebHookLog(
            webHookId = "webhook-id",
            webHookUrl = "https://localhost",
            triggeredEvent = triggeredEvent,
            requestHeaders = emptyMap(),
            requestPayload = requestPayload,
            status = WebHookRequestStatus.SUCCESS,
            requestDuration = 1L,
            requestTime = LocalDateTime.now()
        )
    }
}
