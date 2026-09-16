package com.tencent.bkrepo.common.service.servlet

import com.fasterxml.jackson.databind.JsonNode
import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.common.service.message.MessageSourceConfiguration
import com.tencent.bkrepo.common.service.util.SpringContextUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.HttpInputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.util.MultiValueMap
import org.springframework.web.filter.FormContentFilter
import java.util.Locale

class FormContentFilterTest {

    private val filter = ServletConfiguration().formContentFilter()

    @Test
    fun `illegal percent escape returns unified 400 response without entering chain`() {
        val response = MockHttpServletResponse()
        var chainInvoked = false

        filter.doFilter(
            formRequest("a=%zz", Locale.SIMPLIFIED_CHINESE),
            response
        ) { _, _ -> chainInvoked = true }

        assertEquals(400, response.status)
        assertFalse(chainInvoked)
        assertEquals(MediaTypes.APPLICATION_JSON, response.contentType)
        val body = body(response)
        assertEquals(CommonMessageCode.REQUEST_CONTENT_INVALID.getCode(), body["code"].asInt())
        assertEquals("请求内容无效", body["message"].asText())
        assertTrue(body["data"].isNull)
        assertTrue(body["traceId"].isNull)
    }

    @Test
    fun `parse-time HttpMessageNotReadableException returns unified 400 without entering chain`() {
        val filter = ServletConfiguration().formContentFilter()
        filter.setFormConverter(object : FormHttpMessageConverter() {
            override fun read(
                clazz: Class<out MultiValueMap<String, *>>?,
                inputMessage: HttpInputMessage
            ): MultiValueMap<String, String> {
                throw HttpMessageNotReadableException("Could not decode HTTP form payload", inputMessage)
            }
        })
        val response = MockHttpServletResponse()
        var chainInvoked = false

        filter.doFilter(formRequest("a=1"), response) { _, _ -> chainInvoked = true }

        assertEquals(400, response.status)
        assertFalse(chainInvoked)
        assertEquals(
            CommonMessageCode.REQUEST_CONTENT_INVALID.getCode(),
            body(response)["code"].asInt()
        )
    }

    @Test
    fun `replaces the auto-configured filter instead of adding a second one`() {
        contextRunner().run { context ->
            assertEquals(1, context.getBeanNamesForType(FormContentFilter::class.java).size)
            val filter = context.getBean(OrderedFormContentFilter::class.java)
            assertEquals(ServletConfiguration::class.java, filter.javaClass.enclosingClass)
            assertEquals(OrderedFormContentFilter.DEFAULT_ORDER, filter.order)
        }
    }

    @Test
    fun `honours the spring switch that disables form content parsing`() {
        contextRunner()
            .withPropertyValues("spring.mvc.formcontent.filter.enabled=false")
            .run { context ->
                assertEquals(0, context.getBeanNamesForType(FormContentFilter::class.java).size)
            }
    }

    @Test
    fun `backs off when application provides a form content filter`() {
        contextRunner()
            .withBean("customFormContentFilter", FormContentFilter::class.java, { FormContentFilter() })
            .run { context ->
                assertEquals(1, context.getBeanNamesForType(FormContentFilter::class.java).size)
                assertSame(
                    context.getBean("customFormContentFilter"),
                    context.getBean(FormContentFilter::class.java)
                )
            }
    }

    private fun contextRunner(): WebApplicationContextRunner {
        return WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration::class.java))
            .withUserConfiguration(ServletConfiguration::class.java)
    }

    @Test
    fun `valid form keeps exposing parsed parameters`() {
        val response = MockHttpServletResponse()
        var parameter: String? = null

        filter.doFilter(formRequest("a=1"), response) { request, _ ->
            parameter = request.getParameter("a")
        }

        assertEquals("1", parameter)
    }

    @Test
    fun `downstream illegal argument exception is rethrown unchanged`() {
        val response = MockHttpServletResponse()
        val expected = IllegalArgumentException("downstream")

        val actual = assertThrows<IllegalArgumentException> {
            filter.doFilter(formRequest("a=1"), response) { _, _ -> throw expected }
        }

        assertSame(expected, actual)
    }

    @Test
    fun `downstream HttpMessageNotReadableException is rethrown unchanged`() {
        val response = MockHttpServletResponse()
        val expected = HttpMessageNotReadableException("downstream", MockHttpInputMessage(ByteArray(0)))

        val actual = assertThrows<HttpMessageNotReadableException> {
            filter.doFilter(formRequest("a=1"), response) { _, _ -> throw expected }
        }

        assertSame(expected, actual)
    }

    private fun formRequest(
        content: String,
        locale: Locale = Locale.ENGLISH
    ): MockHttpServletRequest {
        return MockHttpServletRequest("PUT", "/api/node/test").apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
            setContent(content.toByteArray())
            addPreferredLocale(locale)
        }
    }

    private fun body(response: MockHttpServletResponse): JsonNode {
        return JsonUtils.objectMapper.readTree(response.contentAsString)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun initSpringContext() {
            val context = AnnotationConfigApplicationContext(MessageSourceConfiguration::class.java)
            SpringContextUtils().setApplicationContext(context)
        }
    }
}
