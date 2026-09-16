package com.tencent.bkrepo.common.api.util.checkurl

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.MalformedURLException

class CheckUrlTest {

    @Test
    fun `accepts https url with default subhost rules`() {
        val config = UrlCheckConfig(
            schemes = listOf("https"),
            rules = listOf("example.com"),
            mode = "subhost",
        )
        assertDoesNotThrow { CheckUrl.checkUrl("https://repo.example.com", config) }
    }

    @Test
    fun `rejects invalid scheme`() {
        val config = UrlCheckConfig(
            schemes = listOf("https"),
            rules = listOf(".*"),
            mode = "regex",
        )
        assertThrows(MalformedURLException::class.java) {
            CheckUrl.checkUrl("http://repo.example.com", config)
        }
    }

    @Test
    fun `empty rules accept dotted host without fabricating regex`() {
        assertDoesNotThrow { SecUrlValidator.validate("http://1.2.3.4", UrlCheckProperties()) }
    }

    @Test
    fun `empty rules reject hostname without dot`() {
        assertThrows(MalformedURLException::class.java) {
            SecUrlValidator.validate("http://localhost", UrlCheckProperties())
        }
    }

    @Test
    fun `configured subhost rules still accept matching host`() {
        val props = UrlCheckProperties(rules = listOf("example.com"), mode = "subhost")
        assertDoesNotThrow { SecUrlValidator.validate("https://repo.example.com", props) }
    }

    @Test
    fun `configured subhost rules reject non matching host`() {
        val props = UrlCheckProperties(rules = listOf("example.com"), mode = "subhost")
        assertThrows(MalformedURLException::class.java) {
            SecUrlValidator.validate("https://evil.example.net", props)
        }
    }

    @Test
    fun `blockPrivateIp rejects loopback ip`() {
        val props = UrlCheckProperties(blockPrivateIp = true)
        assertThrows(MalformedURLException::class.java) {
            SecUrlValidator.validate("http://127.0.0.1", props)
        }
    }

    @Test
    fun `blockPrivateIp rejects rfc1918 private ip`() {
        val props = UrlCheckProperties(blockPrivateIp = true)
        listOf("http://10.0.0.1", "http://172.16.0.1", "http://192.168.1.1").forEach { url ->
            assertThrows(MalformedURLException::class.java) { SecUrlValidator.validate(url, props) }
        }
    }

    @Test
    fun `blockPrivateIp rejects link local and cloud metadata ip`() {
        val props = UrlCheckProperties(blockPrivateIp = true)
        assertThrows(MalformedURLException::class.java) {
            SecUrlValidator.validate("http://169.254.169.254", props)
        }
    }

    @Test
    fun `blockPrivateIp allows public ip`() {
        val props = UrlCheckProperties(blockPrivateIp = true)
        assertDoesNotThrow { SecUrlValidator.validate("http://8.8.8.8", props) }
    }

    @Test
    fun `blockPrivateIp disabled by default keeps existing behavior`() {
        assertDoesNotThrow { SecUrlValidator.validate("http://127.0.0.1", UrlCheckProperties()) }
    }

    @Test
    fun `rejects non canonical ipv4 literal even when blockPrivateIp is disabled`() {
        listOf(
            "http://0177.0.0.1",
            "http://192.168.000.001",
            "http://0x7f.0.0.1",
            "http://0x7f000001",
            "http://127.1",
        ).forEach { url ->
            assertThrows(MalformedURLException::class.java) {
                SecUrlValidator.validate(url, UrlCheckProperties())
            }
        }
    }

    @Test
    fun `rejects non canonical ipv4 literal when blockPrivateIp is enabled`() {
        val props = UrlCheckProperties(blockPrivateIp = true)
        assertThrows(MalformedURLException::class.java) {
            SecUrlValidator.validate("http://0177.0.0.1", props)
        }
    }
}
