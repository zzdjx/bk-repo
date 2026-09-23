package com.tencent.bkrepo.preview.utils

import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("远程预览 HTTP 出站 DNS 解析结果校验")
class PreviewCallbackDnsTest {

    @Test
    @DisplayName("解析出回环地址时拒绝建连，阻断 DNS Rebinding")
    fun `lookup rejects host resolved to loopback`() {
        val dns = PreviewCallbackDns(SsrfGuard(previewConfig()))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            dns.lookup("localhost")
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    @DisplayName("解析出公网地址时放行，并原样返回本次解析结果")
    fun `lookup returns resolved addresses for public host`() {
        val dns = PreviewCallbackDns(SsrfGuard(previewConfig()))

        val addresses = dns.lookup("8.8.8.8")

        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), addresses)
    }

    @Test
    @DisplayName("回环地址属于始终拦截网段，不受内网拦截开关影响")
    fun `lookup rejects loopback even when internal address block disabled`() {
        val dns = PreviewCallbackDns(SsrfGuard(previewConfig(blockInternal = false)))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            dns.lookup("127.0.0.1")
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }

    private fun previewConfig(blockInternal: Boolean = true): PreviewConfig {
        val config = mockk<PreviewConfig>(relaxed = true)
        every { config.isBlockInternalAddress } returns blockInternal
        return config
    }
}
