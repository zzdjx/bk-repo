package com.tencent.bkrepo.huggingface.exception

import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.huggingface.constants.ERROR_CODE_HEADER
import com.tencent.bkrepo.huggingface.constants.ERROR_MSG_HEADER
import jakarta.servlet.http.HttpServletResponse

/**
 * 按 HuggingFace Hub 错误协议写出状态码、错误头和 JSON 错误体。
 */
internal object HfErrorResponse {

    /**
     * 写入 `X-Error-Code`/`X-Error-Message` 以及 `{"error": ...}` 响应体。
     */
    fun write(response: HttpServletResponse, status: Int, errorCode: String, errorMessage: String) {
        response.status = status
        response.setHeader(ERROR_CODE_HEADER, errorCode)
        response.setHeader(ERROR_MSG_HEADER, errorMessage)
        response.contentType = MediaTypes.APPLICATION_JSON
        response.writer.write(mapOf("error" to errorMessage).toJsonString())
    }
}
