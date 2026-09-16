package com.tencent.bkrepo.auth.interceptor

import com.tencent.bkrepo.auth.constant.AUTHORIZATION
import com.tencent.bkrepo.auth.constant.BASIC_AUTH_HEADER_PREFIX
import com.tencent.bkrepo.auth.pojo.user.UserInfo
import com.tencent.bkrepo.auth.service.AccountService
import com.tencent.bkrepo.auth.service.OauthAuthorizationService
import com.tencent.bkrepo.auth.service.UserService
import com.tencent.bkrepo.common.api.constant.BEARER_AUTH_PREFIX
import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.security.http.core.HttpAuthSecurity
import com.tencent.bkrepo.common.security.http.jwt.JwtAuthProperties
import com.tencent.bkrepo.common.security.util.JwtUtils
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration

@DisplayName("Auth 服务拦截器鉴权开关")
class AuthInterceptorTest {

    private val accountService = mockk<AccountService>(relaxed = true)
    private val userService = mockk<UserService>(relaxed = true)
    private val oauthAuthorizationService = mockk<OauthAuthorizationService>(relaxed = true)
    private val jwtProperties = JwtAuthProperties(secretKey = "test-secret")

    @Test
    @DisplayName("Basic 关闭后携带 Basic 凭据返回 401")
    fun `disabled basic credentials return unauthorized`() {
        val interceptor = interceptor(HttpAuthSecurity().disableBasicAuth())
        val request = MockHttpServletRequest()
        request.addHeader(AUTHORIZATION, "${BASIC_AUTH_HEADER_PREFIX}abc")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertFalse(allowed)
        assertEquals(HttpStatus.UNAUTHORIZED.value, response.status)
    }

    @Test
    @DisplayName("JWT 与 OAuth 都关闭后 Bearer 返回 401")
    fun `bearer returns unauthorized when jwt and oauth are both disabled`() {
        val interceptor = interceptor(HttpAuthSecurity().disableJwtAuth().disableOauthAuth())
        val request = MockHttpServletRequest()
        request.addHeader(AUTHORIZATION, "${BEARER_AUTH_PREFIX}token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertFalse(allowed)
        assertEquals(HttpStatus.UNAUTHORIZED.value, response.status)
    }

    @Test
    @DisplayName("仅关闭 JWT 时 Bearer 走 OAuth 而不是内部 JWT")
    fun `bearer skips jwt parsing when jwt is disabled`() {
        val token = JwtUtils.generateToken(
            JwtUtils.createSigningKey(jwtProperties.secretKey),
            Duration.ZERO,
            "jwt-user"
        )
        every { userService.getUserInfoById("jwt-user") } returns adminUser("jwt-user")
        every { oauthAuthorizationService.validateToken(token) } returns "oauth-user"
        every { userService.getUserInfoById("oauth-user") } returns adminUser("oauth-user")

        val interceptor = interceptor(HttpAuthSecurity().disableJwtAuth())
        val request = MockHttpServletRequest()
        request.requestURI = "/auth/api/user/list"
        request.addHeader(AUTHORIZATION, "$BEARER_AUTH_PREFIX$token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals("oauth-user", request.getAttribute(USER_KEY))
    }

    @Test
    @DisplayName("JWT 开启时 Bearer 内部 JWT 仍然可用")
    fun `bearer still authenticates internal jwt when jwt is enabled`() {
        val token = JwtUtils.generateToken(
            JwtUtils.createSigningKey(jwtProperties.secretKey),
            Duration.ZERO,
            "jwt-user"
        )
        every { userService.getUserInfoById("jwt-user") } returns adminUser("jwt-user")
        every { oauthAuthorizationService.validateToken(token) } returns "oauth-user"
        every { userService.getUserInfoById("oauth-user") } returns adminUser("oauth-user")

        val interceptor = interceptor(HttpAuthSecurity())
        val request = MockHttpServletRequest()
        request.requestURI = "/auth/api/user/list"
        request.addHeader(AUTHORIZATION, "$BEARER_AUTH_PREFIX$token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals("jwt-user", request.getAttribute(USER_KEY))
    }

    @Test
    @DisplayName("仅关闭 OAuth 时内部 JWT 仍然可用")
    fun `internal jwt still works when oauth is disabled`() {
        val token = JwtUtils.generateToken(
            JwtUtils.createSigningKey(jwtProperties.secretKey),
            Duration.ZERO,
            "jwt-user"
        )
        every { userService.getUserInfoById("jwt-user") } returns adminUser("jwt-user")

        val interceptor = interceptor(HttpAuthSecurity().disableOauthAuth())
        val request = MockHttpServletRequest()
        request.requestURI = "/auth/api/user/list"
        request.addHeader(AUTHORIZATION, "$BEARER_AUTH_PREFIX$token")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertTrue(allowed)
        assertEquals("jwt-user", request.getAttribute(USER_KEY))
    }

    private fun interceptor(security: HttpAuthSecurity): AuthInterceptor {
        return AuthInterceptor(
            security,
            accountService,
            userService,
            oauthAuthorizationService,
            jwtProperties
        )
    }

    private fun adminUser(userId: String) = UserInfo(
        userId = userId,
        name = userId,
        email = null,
        phone = null,
        createdDate = null,
        locked = false,
        admin = true,
        group = false
    )
}
