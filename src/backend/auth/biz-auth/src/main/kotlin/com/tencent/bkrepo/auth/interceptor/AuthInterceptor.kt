/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.auth.interceptor

import com.tencent.bkrepo.auth.constant.AUTHORIZATION
import com.tencent.bkrepo.auth.constant.AUTH_API_ACCOUNT_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_API_AUTH_MODE_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_API_OAUTH_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_API_PERMISSION_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_API_ROLE_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_API_USER_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_CLUSTER_PERMISSION_CHECK_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_CLUSTER_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_CLUSTER_TOKEN_DECREMENT_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_CLUSTER_TOKEN_DELETE_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_CLUSTER_TOKEN_INFO_PREFIX
import com.tencent.bkrepo.auth.constant.AUTH_FAILED_RESPONSE
import com.tencent.bkrepo.auth.constant.BASIC_AUTH_HEADER_PREFIX
import com.tencent.bkrepo.auth.constant.PLATFORM_AUTH_HEADER_PREFIX
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizationGrantType
import com.tencent.bkrepo.auth.pojo.user.CreateUserRequest
import com.tencent.bkrepo.auth.service.AccountService
import com.tencent.bkrepo.auth.service.OauthAuthorizationService
import com.tencent.bkrepo.auth.service.UserService
import com.tencent.bkrepo.common.api.constant.ADMIN_USER
import com.tencent.bkrepo.common.api.constant.ANONYMOUS_USER
import com.tencent.bkrepo.common.api.constant.AUTH_HEADER_UID
import com.tencent.bkrepo.common.api.constant.BEARER_AUTH_PREFIX
import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.constant.MS_AUTH_HEADER_UID
import com.tencent.bkrepo.common.api.constant.PLATFORM_KEY
import com.tencent.bkrepo.common.api.constant.StringPool.COLON
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.common.security.http.core.HttpAuthSecurity
import com.tencent.bkrepo.common.security.http.jwt.JwtAuthProperties
import com.tencent.bkrepo.common.security.util.JwtUtils
import com.tencent.bkrepo.common.service.util.HttpSigner
import com.tencent.bkrepo.common.service.util.SpringContextUtils
import com.tencent.bkrepo.repository.constant.SYSTEM_USER
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.commons.codec.digest.HmacAlgorithms
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.security.MessageDigest
import java.util.Base64

/**
 * Auth 服务专用 HTTP 鉴权拦截器。
 * 与公共链读取同一套 [HttpAuthSecurity] 开关，但不处理 Temporary Token。
 */
class AuthInterceptor(
    private val httpAuthSecurity: HttpAuthSecurity,
    injectedAccountService: AccountService? = null,
    injectedUserService: UserService? = null,
    injectedOauthAuthorizationService: OauthAuthorizationService? = null,
    injectedJwtProperties: JwtAuthProperties? = null
) : HandlerInterceptor {

    private val accountService: AccountService by lazy {
        injectedAccountService ?: SpringContextUtils.getBean()
    }
    private val userService: UserService by lazy {
        injectedUserService ?: SpringContextUtils.getBean()
    }
    private val oauthAuthorizationService: OauthAuthorizationService by lazy {
        injectedOauthAuthorizationService ?: SpringContextUtils.getBean()
    }
    private val jwtProperties: JwtAuthProperties by lazy {
        injectedJwtProperties ?: SpringContextUtils.getBean()
    }
    private val signingKey by lazy { JwtUtils.createSigningKey(jwtProperties.secretKey) }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val authHeader = request.getHeader(AUTHORIZATION).orEmpty()
        var authFailStr = String.format(AUTH_FAILED_RESPONSE, "empty auth header")
        try {
            // basic认证
            if (authHeader.startsWith(BASIC_AUTH_HEADER_PREFIX) && httpAuthSecurity.basicAuthEnabled) {
                return checkUserFromBasic(request, authHeader)
            }

            // platform认证
            if (authHeader.startsWith(PLATFORM_AUTH_HEADER_PREFIX) && httpAuthSecurity.platformAuthEnabled) {
                authFailStr = String.format(AUTH_FAILED_RESPONSE, "illegal platform token")
                return checkUserFromPlatform(request, authHeader)
            }

            // oauth/jwt认证
            if (authHeader.startsWith(BEARER_AUTH_PREFIX) &&
                (httpAuthSecurity.jwtAuthEnabled || httpAuthSecurity.oauthEnabled)
            ) {
                authFailStr = String.format(AUTH_FAILED_RESPONSE, "illegal bearer token")
                return checkBearerToken(request, authHeader)
            }

            // sign认证
            if (hasSignCredentials(request) && httpAuthSecurity.signAuthEnabled) {
                authFailStr = String.format(AUTH_FAILED_RESPONSE, "illegal sign")
                return checkUserFromSign(request)
            }

            throw IllegalArgumentException("invalid auth type")
        } catch (e: IllegalArgumentException) {
            response.status = HttpStatus.UNAUTHORIZED.value
            response.writer.print(authFailStr)
            logger.warn("check exception [$e]")
            return false
        }
    }

    private fun checkBearerToken(request: HttpServletRequest, authHeader: String): Boolean {
        val token = authHeader.removePrefix(BEARER_AUTH_PREFIX)
        if (httpAuthSecurity.jwtAuthEnabled) {
            val userId = parseInternalJwtUserId(token)
            if (userId != null) {
                return checkUserFromJwt(request, userId)
            }
        }
        if (httpAuthSecurity.oauthEnabled) {
            return checkOauthToken(request, authHeader)
        }
        throw IllegalArgumentException("invalid auth type")
    }

    private fun parseInternalJwtUserId(token: String): String? {
        return try {
            JwtUtils.validateToken(signingKey, token).body.subject?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug("validate internal jwt token failed", e)
            null
        }
    }

    private fun checkUserFromJwt(request: HttpServletRequest, userId: String): Boolean {
        val userAccess = isUserAccessApi(request)
        val user = userService.getUserInfoById(userId) ?: run {
            logger.warn("internal jwt user [$userId] not found")
            throw IllegalArgumentException("internal jwt user not found")
        }
        val isAdmin = user.admin
        request.setAttribute(USER_KEY, userId)
        request.setAttribute(ADMIN_USER, isAdmin)
        if (!isAdmin && !userAccess) {
            logger.warn("user [$userId] can not access this endpoint [${request.requestURI}]")
            throw IllegalArgumentException("user has no permission")
        }
        return true
    }

    private fun checkUserFromBasic(request: HttpServletRequest, authHeader: String): Boolean {
        val userAccess = isUserAccessApi(request)
        val encodedCredentials = authHeader.removePrefix(BASIC_AUTH_HEADER_PREFIX)
        val decodedHeader = String(Base64.getDecoder().decode(encodedCredentials))
        val parts = decodedHeader.split(COLON)
        require(parts.size == CREDENTIAL_PARTS_SIZE)
        val user = userService.findUserByUserToken(parts[0], parts[1]) ?: run {
            logger.warn("find no user [${parts[0]}]")
            throw IllegalArgumentException("user name token mismatch")
        }

        request.setAttribute(USER_KEY, parts[0])
        request.setAttribute(ADMIN_USER, user.admin)
        // 非管理员访问非授权endpoint
        if (!user.admin && !userAccess) {
            logger.warn("user [${parts[0]}] can not access this endpoint [${request.requestURI}]")
            throw IllegalArgumentException("user has no permission")
        }
        return true
    }

    private fun checkUserFromPlatform(request: HttpServletRequest, authHeader: String): Boolean {
        // platform认证
        val encodedCredentials = authHeader.removePrefix(PLATFORM_AUTH_HEADER_PREFIX)
        val decodedHeader = String(Base64.getDecoder().decode(encodedCredentials))
        val parts = decodedHeader.split(COLON)
        require(parts.size == CREDENTIAL_PARTS_SIZE)
        val appId = accountService.checkCredential(parts[0], parts[1], AuthorizationGrantType.PLATFORM) ?: run {
            logger.warn("find no account [$parts[0]]")
            throw IllegalArgumentException("check platform credential failed")
        }
        val userId = request.getHeader(AUTH_HEADER_UID).orEmpty().trim()
        if (userId.isEmpty()) {
            logger.warn("platform auth with empty userId")
            throw IllegalArgumentException("platform auth with empty userId")
        }
        setAuthAttribute(userId, appId, request)
        return true
    }

    private fun checkUserFromSign(request: HttpServletRequest): Boolean {
        val sig = request.getParameter(HttpSigner.SIGN)
        val appId = request.getParameter(HttpSigner.APP_ID)
        val accessKey = request.getParameter(HttpSigner.ACCESS_KEY)

        val secretKey = accountService.findSecretKey(appId, accessKey)
        // 账号非法
            ?: throw AuthenticationException("AppId or accessKey error.")
        val uri = getUrlPath(request)
        val bodyHash = request.getAttribute(HttpSigner.SIGN_BODY).toString()
        val computeSig = HttpSigner.sign(request, uri, bodyHash, secretKey, HmacAlgorithms.HMAC_SHA_1.getName())
        if (sig == null || !MessageDigest.isEqual(computeSig.toByteArray(), sig.toByteArray())) {
            val signatureStr = HttpSigner.getSignatureStr(request, uri, bodyHash)
            logger.warn("Invalid signature, server signature string: [$signatureStr]")
            throw AuthenticationException("Invalid signature")
        }
        val signTime = request.getParameter(HttpSigner.SIGN_TIME)
        val expiredTime = signTime.split(HttpSigner.TIME_SPLIT).last().toLong()
        if (expiredTime < System.currentTimeMillis() / HttpSigner.MILLIS_PER_SECOND) {
            // 请求超时
            throw PermissionException("Request timeout.")
        }
        val userId = request.getHeader(MS_AUTH_HEADER_UID) ?: SYSTEM_USER
        setAuthAttribute(userId, appId, request)
        return true
    }

    private fun setAuthAttribute(userId: String, appId: String, request: HttpServletRequest) {
        val userAccess = isUserAccessApi(request)
        val anonymousAccess = isAnonymousAccessApi(request)
        val userInfo = userService.getUserInfoById(userId)
        val isAdmin: Boolean = userInfo?.admin ?: false
        if (userId.isNotEmpty() && userInfo == null && userId != ANONYMOUS_USER) {
            val createRequest = CreateUserRequest(userId = userId, name = userId)
            userService.createUser(createRequest)
        }

        val condition = !anonymousAccess && !isAdmin && !userAccess
        if (condition) {
            logger.warn("user [$userId] can not access the endpoint [${request.requestURI}]")
            throw IllegalArgumentException("user access admin endpoint")
        }

        logger.debug("auth userId [$userId], platId [$appId]")
        request.setAttribute(USER_KEY, userId)
        request.setAttribute(PLATFORM_KEY, appId)
        request.setAttribute(ADMIN_USER, isAdmin)
    }

    private fun hasSignCredentials(request: HttpServletRequest): Boolean {
        return request.getParameter(HttpSigner.SIGN) != null &&
            request.getParameter(HttpSigner.APP_ID) != null &&
            request.getParameter(HttpSigner.ACCESS_KEY) != null
    }

    private fun getUrlPath(request: HttpServletRequest): String {
        val realPath = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE).toString()
        var path = realPath
        if (httpAuthSecurity.prefixEnabled) {
            path = realPath.removePrefix(httpAuthSecurity.prefix)
        }
        return path
    }

    /**
     * 非管理员可访问的用户侧 API。必须用 Spring 映射路径做前缀匹配，
     * 禁止对 [HttpServletRequest.getRequestURI] 做 contains：Tomcat 会保留矩阵参数（`;`），
     * 而 Spring MVC 路由会剥离分号，导致 `/api/proxy/create;/api/user` 绕过管理员检查。
     */
    private fun isUserAccessApi(request: HttpServletRequest): Boolean {
        return matchesPathPrefix(resolveLookupPath(request), userAccessApiSet)
    }

    private fun isAnonymousAccessApi(request: HttpServletRequest): Boolean {
        return matchesPathPrefix(resolveLookupPath(request), anonymousAccessApiSet)
    }

    private fun resolveLookupPath(request: HttpServletRequest): String {
        val mappedPath = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE) as? String
        var path = (mappedPath ?: request.requestURI).substringBefore(';')
        val contextPath = request.contextPath
        if (contextPath.isNotEmpty() && path.startsWith(contextPath)) {
            path = path.removePrefix(contextPath)
        }
        if (httpAuthSecurity.prefixEnabled) {
            path = path.removePrefix(httpAuthSecurity.prefix)
        }
        return path
    }

    private fun matchesPathPrefix(path: String, prefixes: Set<String>): Boolean {
        return prefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") }
    }

    private fun checkOauthToken(request: HttpServletRequest, authHeader: String): Boolean {
        return try {
            val userAccess = isUserAccessApi(request)
            val userId = oauthAuthorizationService.validateToken(authHeader.removePrefix(BEARER_AUTH_PREFIX))
                ?: throw AuthenticationException()
            val user = userService.getUserInfoById(userId)
            val isAdmin = user?.admin ?: false
            request.setAttribute(USER_KEY, userId)
            request.setAttribute(ADMIN_USER, isAdmin)
            if (!isAdmin && !userAccess) {
                logger.warn("user [$userId] can not access this endpoint [${request.requestURI}]")
                throw IllegalArgumentException("user has no permission")
            }
            true
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            logger.error("check oauth token error: ", e)
            throw AuthenticationException()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AuthInterceptor::class.java)
        private const val CREDENTIAL_PARTS_SIZE = 2

        // 项目内权限校验api, 开放给项目内有权限用户使用，具体校验权限在方法内
        private val userAccessApiSet = setOf(
            AUTH_API_USER_PREFIX,
            AUTH_API_ACCOUNT_PREFIX,
            AUTH_API_ROLE_PREFIX,
            AUTH_API_PERMISSION_PREFIX,
            AUTH_API_OAUTH_PREFIX,
            AUTH_API_AUTH_MODE_PREFIX,
            AUTH_CLUSTER_PREFIX
        )

        private val anonymousAccessApiSet = setOf(
            AUTH_CLUSTER_PERMISSION_CHECK_PREFIX,
            AUTH_CLUSTER_TOKEN_INFO_PREFIX,
            AUTH_CLUSTER_TOKEN_DELETE_PREFIX,
            AUTH_CLUSTER_TOKEN_DECREMENT_PREFIX
        )
    }
}
