package com.tencent.bkrepo.fs.filter

import com.tencent.bkrepo.common.api.constant.BEARER_AUTH_PREFIX
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.api.constant.MS_AUTH_HEADER_UID
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.api.exception.SystemErrorException
import com.tencent.bkrepo.common.security.constant.MS_AUTH_HEADER_SECURITY_TOKEN
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import com.tencent.bkrepo.common.security.http.jwt.JwtAuthProperties
import com.tencent.bkrepo.common.security.service.ServiceAuthManager
import com.tencent.bkrepo.common.security.service.ServiceAuthProperties
import com.tencent.bkrepo.fs.server.filter.AuthHandlerFilterFunction
import com.tencent.bkrepo.fs.server.service.PermissionService
import com.tencent.bkrepo.fs.server.utils.SecurityManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait

@DisplayName("认证过滤器")
class AuthHandlerFilterFunctionTest {

    private val permissionService: PermissionService = mock()
    private val securityManager = SecurityManager(JwtAuthProperties(secretKey = USER_SECRET_KEY))
    private val serviceAuthProperties = ServiceAuthProperties(enabled = true, secretKey = SERVICE_SECRET_KEY)
    private val serviceAuthManager = ServiceAuthManager(serviceAuthProperties)
    private val filter = AuthHandlerFilterFunction(
        securityManager = securityManager,
        permissionService = permissionService,
        serviceAuthManager = serviceAuthManager,
        serviceAuthProperties = serviceAuthProperties
    )

    @Test
    fun `should accept service jwt signed by service secret key`() = runBlocking {
        val request = serviceRequest(
            token = serviceAuthManager.getSecurityToken(),
            uid = USER_ID
        )

        val response = filter.filter(request, okHandler)

        assertEquals(HttpStatus.OK, response.statusCode())
        assertEquals(USER_ID, request.exchange().attributes[USER_KEY])
    }

    @Test
    fun `should reject user jwt on service api`() {
        val request = serviceRequest(
            token = securityManager.generateToken(subject = USER_ID),
            uid = USER_ID
        )

        assertThrows(SystemErrorException::class.java) {
            runBlocking { filter.filter(request, okHandler) }
        }
    }

    @Test
    fun `should still validate user jwt with auth jwt secret`() = runBlocking {
        val request = userRequest(token = securityManager.generateToken(subject = USER_ID))

        val response = filter.filter(request, okHandler)

        assertEquals(HttpStatus.OK, response.statusCode())
        assertEquals(USER_ID, request.exchange().attributes[USER_KEY])
    }

    @Test
    fun `should reject service jwt on user api`() {
        val request = userRequest(token = serviceAuthManager.getSecurityToken())

        assertThrows(AuthenticationException::class.java) {
            runBlocking { filter.filter(request, okHandler) }
        }
    }

    private fun serviceRequest(token: String, uid: String): ServerRequest {
        return mockRequest(
            path = SERVICE_PATH,
            headers = mapOf(
                MS_AUTH_HEADER_SECURITY_TOKEN to token,
                MS_AUTH_HEADER_UID to uid
            )
        )
    }

    private fun userRequest(token: String): ServerRequest {
        return mockRequest(
            path = USER_PATH,
            headers = mapOf(HttpHeaders.AUTHORIZATION to "$BEARER_AUTH_PREFIX$token")
        )
    }

    private fun mockRequest(path: String, headers: Map<String, String>): ServerRequest {
        val httpRequest = MockServerHttpRequest.get(path).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return MockServerRequest.builder()
            .uri(httpRequest.uri)
            .headers(httpRequest.headers)
            .exchange(MockServerWebExchange.from(httpRequest))
            .build()
    }

    companion object {
        private const val USER_SECRET_KEY = "user-jwt-secret-key"
        private const val SERVICE_SECRET_KEY = "service-jwt-secret-key"
        private const val USER_ID = "admin"
        private const val SERVICE_PATH = "/service/block/list/project/repo"
        private const val USER_PATH = "/node/info/project/repo/file"
        private val okHandler: suspend (ServerRequest) -> ServerResponse = {
            ServerResponse.ok().buildAndAwait()
        }
    }
}
