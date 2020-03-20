package com.tencent.bkrepo.common.service.feign

import com.tencent.bkrepo.common.service.log.LoggerHolder
import feign.Request
import feign.Response
import java.io.IOException

/**
 * feign 统一slf4j日志记录器
 * 将默认的debug级别修改为info，并统一logger
 *
 * @author: carrypan
 * @date: 2019/11/1
 */
class FeignApiLogger : feign.Logger() {

    override fun logRequest(configKey: String, logLevel: Level, request: Request) {
        if (logger.isInfoEnabled) {
            super.logRequest(configKey, logLevel, request)
        }
    }

    @Throws(IOException::class)
    override fun logAndRebufferResponse(configKey: String, logLevel: Level, response: Response, elapsedTime: Long): Response {
        return if (logger.isInfoEnabled) super.logAndRebufferResponse(configKey, logLevel, response, elapsedTime) else response
    }

    override fun log(configKey: String, format: String, vararg args: Any) {
        if (logger.isInfoEnabled) {
            logger.info(String.format(methodTag(configKey) + format, *args))
        }
    }
    companion object {
        private val logger = LoggerHolder.apiLogger
    }
}
