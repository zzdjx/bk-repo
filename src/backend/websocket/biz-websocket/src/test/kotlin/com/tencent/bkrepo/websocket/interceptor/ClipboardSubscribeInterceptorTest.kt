package com.tencent.bkrepo.websocket.interceptor

import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.websocket.constant.APP_ENDPOINT
import com.tencent.bkrepo.websocket.constant.DESKTOP_ENDPOINT
import com.tencent.bkrepo.websocket.constant.ENDPOINT
import com.tencent.bkrepo.websocket.constant.USER_ENDPOINT
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

@DisplayName("CopyPDU 订阅端点限制")
class ClipboardSubscribeInterceptorTest {

    private val interceptor = ClipboardSubscribeInterceptor()
    private val channel = object : MessageChannel {
        override fun send(message: Message<*>) = true
        override fun send(message: Message<*>, timeout: Long) = true
    }

    @Test
    @DisplayName("/ws/app 允许订阅 copy topic")
    fun `app endpoint can subscribe copy topic`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/copy/ws-1", APP_ENDPOINT)
        assertSame(message, interceptor.preSend(message, channel))
    }

    @Test
    @DisplayName("SockJS /ws/app 路径允许订阅 copy topic")
    fun `sockjs app path can subscribe copy topic`() {
        val message = stompMessage(
            StompCommand.SUBSCRIBE,
            "/topic/clipboard/copy/ws-1",
            "$APP_ENDPOINT/123/xxx/websocket"
        )
        assertSame(message, interceptor.preSend(message, channel))
    }

    @Test
    @DisplayName("/ws/desktop 禁止订阅 copy topic")
    fun `desktop endpoint cannot subscribe copy topic`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/copy/ws-1", DESKTOP_ENDPOINT)
        assertThrows(PermissionException::class.java) {
            interceptor.preSend(message, channel)
        }
    }

    @Test
    @DisplayName("/ws/user 禁止订阅 copy topic")
    fun `user endpoint cannot subscribe copy topic`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/copy/ws-1", USER_ENDPOINT)
        assertThrows(PermissionException::class.java) {
            interceptor.preSend(message, channel)
        }
    }

    @Test
    @DisplayName("缺少端点信息时拒绝订阅 copy topic")
    fun `missing endpoint cannot subscribe copy topic`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/copy/ws-1", null)
        assertThrows(PermissionException::class.java) {
            interceptor.preSend(message, channel)
        }
    }

    @Test
    @DisplayName("/topic/** 禁止非 app 订阅，避免通配收到 copy topic")
    fun `topic wildcard cannot subscribe copy topic from desktop`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/**", DESKTOP_ENDPOINT)
        assertThrows(PermissionException::class.java) {
            interceptor.preSend(message, channel)
        }
    }

    @Test
    @DisplayName("/topic/clipboard/** 禁止非 app 订阅，避免通配收到 copy topic")
    fun `clipboard wildcard cannot subscribe copy topic from desktop`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/**", USER_ENDPOINT)
        assertThrows(PermissionException::class.java) {
            interceptor.preSend(message, channel)
        }
    }

    @Test
    @DisplayName("/topic/clipboard/*/** 禁止非 app 订阅，避免通配收到 copy topic")
    fun `clipboard nested wildcard cannot subscribe copy topic from desktop`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/*/**", DESKTOP_ENDPOINT)
        assertThrows(PermissionException::class.java) {
            interceptor.preSend(message, channel)
        }
    }

    @Test
    @DisplayName("非 copy topic 订阅不受端点限制")
    fun `paste topic subscribe is not restricted`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/paste/session-1", DESKTOP_ENDPOINT)
        assertSame(message, interceptor.preSend(message, channel))
    }

    @Test
    @DisplayName("不覆盖 copy topic 的通配订阅不受端点限制")
    fun `unrelated topic wildcard is not restricted`() {
        val message = stompMessage(StompCommand.SUBSCRIBE, "/topic/clipboard/paste/**", DESKTOP_ENDPOINT)
        assertSame(message, interceptor.preSend(message, channel))
    }

    @Test
    @DisplayName("发送 copy 不受订阅端点限制")
    fun `send copy is not restricted`() {
        val message = stompMessage(StompCommand.SEND, "/app/clipboard/copy", DESKTOP_ENDPOINT)
        assertSame(message, interceptor.preSend(message, channel))
    }

    private fun stompMessage(command: StompCommand, destination: String, endpoint: String?): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        accessor.destination = destination
        accessor.sessionId = "session-1"
        accessor.sessionAttributes = endpoint?.let { mutableMapOf(ENDPOINT to it) } ?: mutableMapOf()
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }
}
