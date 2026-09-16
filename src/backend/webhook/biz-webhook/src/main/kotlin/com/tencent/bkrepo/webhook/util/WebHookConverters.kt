package com.tencent.bkrepo.webhook.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.common.api.util.MaskPartStringUtil
import com.tencent.bkrepo.webhook.model.TWebHook
import com.tencent.bkrepo.webhook.model.TWebHookLog
import com.tencent.bkrepo.webhook.pojo.WebHook
import com.tencent.bkrepo.webhook.pojo.WebHookLog

/**
 * 将 WebHook 日志实体转换为对外 DTO，并对 requestHeaders 以及 payload 内嵌的 headers 值做掩码。
 */
fun TWebHookLog.toWebHookLog(): WebHookLog {
    return WebHookLog(
        id = id.orEmpty(),
        webHookUrl = webHookUrl,
        triggeredEvent = triggeredEvent,
        requestHeaders = requestHeaders.maskHeaderValues(),
        requestPayload = requestPayload.maskEmbeddedWebHookHeaders(),
        status = status,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        requestDuration = requestDuration,
        requestTime = requestTime,
        errorMsg = errorMsg
    )
}

/**
 * 将 WebHook 实体转换为对外 DTO，并对 headers 值做掩码。
 */
fun TWebHook.toWebHook(): WebHook {
    return toUnmaskedWebHook().maskHeaders()
}

/**
 * 将 WebHook 实体转换为未掩码 DTO，供内部转换使用。
 */
private fun TWebHook.toUnmaskedWebHook(): WebHook {
    return WebHook(
        id = id.orEmpty(),
        url = url,
        headers = headers,
        triggers = triggers,
        associationType = associationType,
        associationId = associationId,
        resourceKeyPattern = resourceKeyPattern,
        createdBy = createdBy,
        createdDate = createdDate,
        lastModifiedBy = lastModifiedBy,
        lastModifiedDate = lastModifiedDate
    )
}

/**
 * 更新时若请求头值等于库中原文的掩码结果，则保留原文，避免 GET 后再 PUT 把密钥写成掩码值。
 */
internal fun Map<String, String>?.restoreUnchangedHeaderValues(stored: Map<String, String>?): Map<String, String>? {
    if (this == null) {
        return stored
    }
    return mapValues { (key, value) ->
        val original = stored?.get(key)
        if (original != null && MaskPartStringUtil.maskPartString(original) == value) {
            original
        } else {
            value
        }
    }
}

private fun WebHook.maskHeaders(): WebHook {
    return copy(headers = headers?.maskHeaderValues())
}

private fun Map<String, String>.maskHeaderValues(): Map<String, String> {
    return mapValues { MaskPartStringUtil.maskPartString(it.value) }
}

/**
 * 测试事件会把 WebHook 配置写进 requestPayload，其中的 headers 需在对外返回时掩码。
 */
private fun String.maskEmbeddedWebHookHeaders(): String {
    val root = try {
        JsonUtils.objectMapper.readTree(this)
    } catch (_: Exception) {
        return this
    }
    val masked = maskHeadersObject(root.path("webHook").path("headers")) ||
        maskHeadersObject(root.path("data").path("webHook").path("headers"))
    if (!masked) {
        return this
    }
    return JsonUtils.objectMapper.writeValueAsString(root)
}

private fun maskHeadersObject(headers: JsonNode): Boolean {
    if (headers !is ObjectNode) {
        return false
    }
    val fieldNames = headers.fieldNames().asSequence().toList()
    var masked = false
    for (name in fieldNames) {
        val value = headers.get(name)
        if (value != null && value.isTextual) {
            headers.put(name, MaskPartStringUtil.maskPartString(value.asText()))
            masked = true
        }
    }
    return masked
}
