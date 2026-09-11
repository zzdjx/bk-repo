package com.tencent.bkrepo.websocket.interceptor

import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.websocket.constant.APP_ENDPOINT
import com.tencent.bkrepo.websocket.constant.CLIPBOARD_COPY_TOPIC_PREFIX
import com.tencent.bkrepo.websocket.constant.ENDPOINT
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher

/**
 * 限制 CopyPDU topic 仅允许 /ws/app 连接订阅
 */
@Component
class ClipboardSubscribeInterceptor : ChannelInterceptor {

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
        if (accessor.command != StompCommand.SUBSCRIBE) {
            return message
        }
        val destination = accessor.destination ?: return message
        if (!coversCopyTopic(destination)) {
            return message
        }
        val endpoint = accessor.sessionAttributes?.get(ENDPOINT)?.toString()
        if (!isAppEndpoint(endpoint)) {
            logger.warn(
                "reject copyPDU subscribe, endpoint[$endpoint], session[${accessor.sessionId}], " +
                    "destination[$destination]"
            )
            throw PermissionException("only [$APP_ENDPOINT] can subscribe copyPDU")
        }
        return message
    }

    /**
     * Simple broker 用 AntPathMatcher 匹配订阅，父级通配也能收到 copy topic。
     * 除前缀判断外，还要用同一套 matcher 探测订阅是否覆盖 copy topic。
     */
    private fun coversCopyTopic(destination: String): Boolean {
        return destination.startsWith(CLIPBOARD_COPY_TOPIC_PREFIX) ||
            pathMatcher.match(destination, COPY_TOPIC_PROBE)
    }

    private fun isAppEndpoint(endpoint: String?): Boolean {
        return endpoint == APP_ENDPOINT || endpoint.orEmpty().startsWith("$APP_ENDPOINT/")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ClipboardSubscribeInterceptor::class.java)
        private val pathMatcher = AntPathMatcher()
        private const val COPY_TOPIC_PROBE = CLIPBOARD_COPY_TOPIC_PREFIX + "x"
    }
}
