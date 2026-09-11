package com.tencent.bkrepo.webhook.executor

import com.tencent.bkrepo.common.api.util.checkurl.UrlCheckProperties
import com.tencent.bkrepo.webhook.config.WebHookProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

@DisplayName("WebHook回调DNS解析防SSRF测试")
class WebHookCallbackDnsTest {

    @Test
    @DisplayName("blockPrivateIp开启时拒绝解析出回环/内网地址的host")
    fun `lookup rejects loopback and private ip when blockPrivateIp enabled`() {
        val dns = WebHookCallbackDns(webHookProperties(blockPrivateIp = true))
        listOf("127.0.0.1", "10.0.0.1", "169.254.169.254").forEach { host ->
            assertThrows(UnknownHostException::class.java) { dns.lookup(host) }
        }
    }

    @Test
    @DisplayName("blockPrivateIp开启时放行公网地址")
    fun `lookup allows public ip when blockPrivateIp enabled`() {
        val dns = WebHookCallbackDns(webHookProperties(blockPrivateIp = true))
        assertDoesNotThrow { dns.lookup("8.8.8.8") }
    }

    @Test
    @DisplayName("blockPrivateIp关闭时放行回环地址，保持现有行为")
    fun `lookup allows loopback ip when blockPrivateIp disabled`() {
        val dns = WebHookCallbackDns(webHookProperties(blockPrivateIp = false))
        assertDoesNotThrow { dns.lookup("127.0.0.1") }
    }

    private fun webHookProperties(blockPrivateIp: Boolean): WebHookProperties {
        return WebHookProperties(callbackUrl = UrlCheckProperties(blockPrivateIp = blockPrivateIp))
    }
}
