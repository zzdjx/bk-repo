package com.tencent.bkrepo.preview.utils

import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import com.tencent.bkrepo.preview.pojo.FileAttribute
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import org.apache.commons.net.ftp.FTPClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("远程预览 FTP 下载 SSRF 防护")
class DownloadUtilsFtpSsrfTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    fun `ftp url is rejected when remote preview is disabled`() {
        val config = previewConfig(remoteEnabled = false)
        val httpUtils = mockk<HttpUtils>(relaxed = true)
        val downloadUtils = DownloadUtils(httpUtils, SsrfGuard(config))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            downloadUtils.downLoad(ftpFileAttribute("ftp://127.0.0.1/secret.txt"), config)
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
        verify(exactly = 0) { httpUtils.downloadHttpFile(any()) }
    }

    @Test
    fun `ftp url is rejected when scheme is not allowlisted`() {
        val config = previewConfig(remoteEnabled = true, schemes = "https")
        val httpUtils = mockk<HttpUtils>(relaxed = true)
        val downloadUtils = DownloadUtils(httpUtils, SsrfGuard(config))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            downloadUtils.downLoad(ftpFileAttribute("ftp://example.com/doc.pdf"), config)
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
        verify(exactly = 0) { httpUtils.downloadHttpFile(any()) }
    }

    @Test
    fun `ftp default port is rejected when not in port whitelist`() {
        val config = previewConfig(
            remoteEnabled = true,
            ports = "80,443"
        )
        val httpUtils = mockk<HttpUtils>(relaxed = true)
        val downloadUtils = DownloadUtils(httpUtils, SsrfGuard(config))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            downloadUtils.downLoad(ftpFileAttribute("ftp://example.com/doc.pdf"), config)
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
        verify(exactly = 0) { httpUtils.downloadHttpFile(any()) }
    }

    @Test
    fun `ftp url to public host is allowed with default scheme and port whitelist`() {
        val config = previewConfig(remoteEnabled = true)
        val url = SsrfGuard(config).validate("ftp://8.8.8.8/doc.pdf")

        assertEquals("ftp", url.protocol)
        assertEquals(21, url.defaultPort)
    }

    @Test
    fun `ftp url to loopback is rejected even if ftp is allowlisted`() {
        val config = previewConfig(remoteEnabled = true)
        val httpUtils = mockk<HttpUtils>(relaxed = true)
        val downloadUtils = DownloadUtils(httpUtils, SsrfGuard(config))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            downloadUtils.downLoad(ftpFileAttribute("ftp://127.0.0.1/secret.txt"), config)
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
        verify(exactly = 0) { httpUtils.downloadHttpFile(any()) }
    }

    @Test
    fun `pasv host to loopback is rejected before data connection`() {
        val config = previewConfig(remoteEnabled = true)
        val resolver = FtpUtils.createPassiveHostResolver(FTPClient(), SsrfGuard(config))

        val exception = assertThrows(PreviewInvalidException::class.java) {
            resolver.resolve("127.0.0.1")
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `pasv public host is allowed without applying control port whitelist`() {
        val config = previewConfig(remoteEnabled = true, ports = "80,443,21")
        val resolver = FtpUtils.createPassiveHostResolver(FTPClient(), SsrfGuard(config))

        assertEquals("8.8.8.8", resolver.resolve("8.8.8.8"))
    }

    private fun previewConfig(
        remoteEnabled: Boolean,
        schemes: String = "https,ftp",
        ports: String = "80,443,21",
        hosts: String = ""
    ): PreviewConfig {
        val config = mockk<PreviewConfig>(relaxed = true)
        every { config.isRemotePreviewEnabled } returns remoteEnabled
        every { config.remoteAllowedSchemes } returns schemes
        every { config.remoteAllowedPorts } returns ports
        every { config.remoteAllowedHosts } returns hosts
        every { config.isBlockInternalAddress } returns true
        every { config.fileDir } returns workspace.toAbsolutePath().toString()
        every { config.prohibitSuffix } returns "exe,dll"
        return config
    }

    private fun ftpFileAttribute(url: String): FileAttribute {
        return FileAttribute(
            storageType = 1,
            fileName = "secret.txt",
            url = url,
            suffix = "txt"
        )
    }
}
