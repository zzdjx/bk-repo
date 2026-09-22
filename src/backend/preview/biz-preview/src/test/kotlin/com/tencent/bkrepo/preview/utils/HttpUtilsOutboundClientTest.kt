package com.tencent.bkrepo.preview.utils

import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import io.micrometer.observation.ObservationRegistry
import io.mockk.mockk
import java.net.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("远程预览 HTTP 出站客户端安全配置")
class HttpUtilsOutboundClientTest {

    /**
     * 代理场景下 OkHttp 不会用自定义 DNS 解析目标域名（HTTP 代理只解析代理地址，
     * SOCKS 走 createUnresolved），[PreviewCallbackDns] 会失效，因此必须固定直连。
     */
    @Test
    @DisplayName("固定直连，不继承 JVM 默认 ProxySelector")
    fun `outbound client pins direct connection`() {
        assertEquals(Proxy.NO_PROXY, newHttpUtils().okHttpClient.proxy)
    }

    @Test
    @DisplayName("出站客户端装载 SSRF DNS 校验器")
    fun `outbound client installs ssrf dns guard`() {
        assertInstanceOf(PreviewCallbackDns::class.java, newHttpUtils().okHttpClient.dns)
    }

    @Test
    @DisplayName("关闭自动重定向，由调用方逐跳校验")
    fun `outbound client disables automatic redirects`() {
        val client = newHttpUtils().okHttpClient

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    private fun newHttpUtils(): HttpUtils {
        val config = mockk<PreviewConfig>(relaxed = true)
        return HttpUtils(ObservationRegistry.NOOP, SsrfGuard(config))
    }
}
