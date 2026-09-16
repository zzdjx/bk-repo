package com.tencent.bkrepo.common.security.http.core

import com.tencent.bkrepo.common.api.constant.ANONYMOUS_USER
import com.tencent.bkrepo.common.api.constant.BASIC_AUTH_PREFIX
import com.tencent.bkrepo.common.api.constant.BEARER_AUTH_PREFIX
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.api.util.BasicAuthUtils
import com.tencent.bkrepo.common.security.http.credentials.AnonymousCredentials
import com.tencent.bkrepo.common.security.http.credentials.HttpAuthCredentials
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import com.tencent.bkrepo.common.security.http.mirrors.MirrorsAuthConfiguration
import com.tencent.bkrepo.common.security.http.mirrors.MirrorsAuthHandler
import com.tencent.bkrepo.common.security.http.mirrors.MirrorsAuthProperties
import com.tencent.bkrepo.common.security.manager.AuthenticationManager
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@DisplayName("公共 HTTP 鉴权拦截器开关行为")
class HttpAuthInterceptorTest {

    @Test
    @DisplayName("未携带凭据且匿名开启时以匿名身份放行")
    fun `anonymous request is allowed when anonymous is enabled`() {
        val security = HttpAuthSecurity().disableBasicAuth()
        val interceptor = HttpAuthInterceptor(security)
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals(ANONYMOUS_USER, request.getAttribute(USER_KEY))
    }

    @Test
    @DisplayName("关闭内置 Basic 后未挂接盘 Handler，携带 Basic 凭据按匿名策略")
    fun `disabled built-in basic without replacement follows anonymous policy`() {
        val security = HttpAuthSecurity().disableBasicAuth()
        val interceptor = HttpAuthInterceptor(security)
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.AUTHORIZATION, "${BASIC_AUTH_PREFIX}abc")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals(ANONYMOUS_USER, request.getAttribute(USER_KEY))
    }

    @Test
    @DisplayName("disableBasicAuth 后 MirrorsAuthHandler 仍可用专属密码")
    fun `mirrors dedicated password still works after disableBasicAuth`() {
        val mirrorsAuthHandler = MirrorsAuthHandler(
            MirrorsAuthProperties(enabled = true, password = "mirrors-secret"),
            AuthenticationManager()
        )
        val security = HttpAuthSecurity()
            .disableBasicAuth()
            .disableAnonymous()
            .addHttpAuthHandler(mirrorsAuthHandler)
        val interceptor = HttpAuthInterceptor(security)
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.AUTHORIZATION, BasicAuthUtils.encode("user", "mirrors-secret"))
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals("user", request.getAttribute(USER_KEY))
    }

    @Test
    @DisplayName("mirrors 配置口令为空时拒绝认证")
    fun `mirrors auth rejects empty configured password`() {
        val mirrorsAuthHandler = MirrorsAuthHandler(
            MirrorsAuthProperties(enabled = true, password = ""),
            AuthenticationManager()
        )
        val security = HttpAuthSecurity()
            .disableBasicAuth()
            .disableAnonymous()
            .addHttpAuthHandler(mirrorsAuthHandler)
        val interceptor = HttpAuthInterceptor(security)
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.AUTHORIZATION, BasicAuthUtils.encode("admin", ""))
        val response = MockHttpServletResponse()

        assertThrows(AuthenticationException::class.java) {
            interceptor.preHandle(request, response, Any())
        }
    }

    @Test
    @DisplayName("开启 mirrors 但未配置口令时拒绝创建认证组件")
    fun `mirrors auth customizer requires password`() {
        assertThrows(IllegalArgumentException::class.java) {
            MirrorsAuthConfiguration().mirrorsAuthSecurityCustomizer(
                MirrorsAuthProperties(enabled = true, password = "  ")
            )
        }
    }

    @Test
    @DisplayName("关闭 JWT 与 OAuth 后服务自有 Bearer Handler 仍可认证")
    fun `service specific bearer handler still authenticates when jwt and oauth disabled`() {
        val security = HttpAuthSecurity()
            .disableJwtAuth()
            .disableOauthAuth()
            .disableAnonymous()
            .addHttpAuthHandler(StubBearerAuthHandler())
        val interceptor = HttpAuthInterceptor(security)
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.AUTHORIZATION, "${BEARER_AUTH_PREFIX}token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals("token-user", request.getAttribute(USER_KEY))
    }

    private class TokenCredentials(val token: String) : HttpAuthCredentials

    private class StubBearerAuthHandler : HttpAuthHandler {
        override fun extractAuthCredentials(request: HttpServletRequest): HttpAuthCredentials {
            val authorization = request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
            if (!authorization.startsWith(BEARER_AUTH_PREFIX)) {
                return AnonymousCredentials()
            }
            return TokenCredentials(authorization.removePrefix(BEARER_AUTH_PREFIX))
        }

        override fun onAuthenticate(request: HttpServletRequest, authCredentials: HttpAuthCredentials): String {
            require(authCredentials is TokenCredentials)
            return "token-user"
        }
    }
}
