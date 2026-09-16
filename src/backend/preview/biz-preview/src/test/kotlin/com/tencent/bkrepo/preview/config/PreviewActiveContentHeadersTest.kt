package com.tencent.bkrepo.preview.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@DisplayName("预览主动内容安全头")
class PreviewActiveContentHeadersTest {

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `svg original preview gets csp that blocks script`() {
        val response = bindResponse()

        PreviewActiveContentHeaders.applyIfActiveContent("evil.svg", "image/svg+xml")

        assertEquals(PreviewActiveContentHeaders.NOSNIFF, response.getHeader("X-Content-Type-Options"))
        val csp = response.getHeader("Content-Security-Policy")
        assertTrue(csp?.contains("script-src 'none'") == true)
        assertTrue(csp?.contains("object-src 'none'") == true)
        assertTrue(csp?.contains("default-src 'none'") == true)
    }

    @Test
    fun `svg content type without svg suffix still gets csp`() {
        val response = bindResponse()

        PreviewActiveContentHeaders.applyIfActiveContent("converted.bin", "image/svg+xml;charset=UTF-8")

        assertEquals(PreviewActiveContentHeaders.NOSNIFF, response.getHeader("X-Content-Type-Options"))
        assertTrue(
            response.getHeader("Content-Security-Policy")?.contains("script-src 'none'") == true
        )
    }

    @Test
    fun `png preview does not get active content headers`() {
        val response = bindResponse()

        PreviewActiveContentHeaders.applyIfActiveContent("demo.png", "image/png")

        assertNull(response.getHeader("X-Content-Type-Options"))
        assertNull(response.getHeader("Content-Security-Policy"))
        assertFalse(PreviewActiveContentHeaders.isSvgContent("demo.png", "image/png"))
    }

    @Test
    fun `html and js do not get svg csp headers`() {
        val htmlResponse = bindResponse()
        PreviewActiveContentHeaders.applyIfActiveContent("page.html", "text/html")
        assertNull(htmlResponse.getHeader("Content-Security-Policy"))
        RequestContextHolder.resetRequestAttributes()

        val jsResponse = bindResponse()
        PreviewActiveContentHeaders.applyIfActiveContent("app.js", "application/javascript")
        assertNull(jsResponse.getHeader("Content-Security-Policy"))

        assertFalse(PreviewActiveContentHeaders.isSvgContent("page.html"))
        assertFalse(PreviewActiveContentHeaders.isSvgContent("app.js"))
        assertTrue(PreviewActiveContentHeaders.isSvgContent("icon.SVG"))
    }

    private fun bindResponse(): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(MockHttpServletRequest(), response)
        )
        return response
    }
}
