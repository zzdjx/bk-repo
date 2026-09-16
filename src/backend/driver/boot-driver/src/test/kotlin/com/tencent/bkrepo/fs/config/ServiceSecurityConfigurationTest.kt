package com.tencent.bkrepo.fs.config

import com.tencent.bkrepo.common.api.constant.MS_AUTH_HEADER_UID
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.security.constant.MS_AUTH_HEADER_SECURITY_TOKEN
import com.tencent.bkrepo.common.security.http.jwt.JwtAuthProperties
import com.tencent.bkrepo.common.security.service.ServiceAuthManager
import com.tencent.bkrepo.common.security.service.ServiceAuthProperties
import com.tencent.bkrepo.fs.server.config.ServiceSecurityConfiguration
import com.tencent.bkrepo.fs.server.context.ReactiveRequestContextHolder
import com.tencent.bkrepo.fs.server.context.RequestContext
import com.tencent.bkrepo.fs.server.utils.SecurityManager
import io.jsonwebtoken.JwtException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactivefeign.client.ReactiveHttpRequest

@DisplayName("服务间 Feign 安全配置")
class ServiceSecurityConfigurationTest {

    @Test
    fun `should sign feign jwt with service secret key instead of user jwt secret`() {
        val serviceAuthManager = ServiceAuthManager(
            ServiceAuthProperties(enabled = true, secretKey = SERVICE_SECRET_KEY)
        )
        val securityManager = SecurityManager(JwtAuthProperties(secretKey = USER_SECRET_KEY))
        val interceptor = ServiceSecurityConfiguration().securityRequestInterceptor(serviceAuthManager)

        val httpRequest = MockServerHttpRequest.get(SERVICE_PATH).build()
        val exchange = MockServerWebExchange.from(httpRequest)
        exchange.attributes[USER_KEY] = USER_ID
        val requestContext = RequestContext(httpRequest, exchange.response, exchange)
        val headers = HashMap<String, List<String>>()
        val feignRequest = mock<ReactiveHttpRequest>()
        whenever(feignRequest.headers()).thenReturn(headers)

        interceptor.apply(feignRequest)
            .contextWrite { it.put(ReactiveRequestContextHolder.REQUEST_CONTEXT_KEY, requestContext) }
            .block()

        val token = headers[MS_AUTH_HEADER_SECURITY_TOKEN]?.first()
            ?: throw IllegalStateException("missing service security token")
        serviceAuthManager.verifySecurityToken(token)
        assertThrows(JwtException::class.java) {
            securityManager.validateToken(token)
        }
        assertEquals(USER_ID, headers[MS_AUTH_HEADER_UID]?.first())
    }

    companion object {
        private const val USER_SECRET_KEY = "user-jwt-secret-key"
        private const val SERVICE_SECRET_KEY = "service-jwt-secret-key"
        private const val USER_ID = "admin"
        private const val SERVICE_PATH = "/service/user/detail/admin"
    }
}
