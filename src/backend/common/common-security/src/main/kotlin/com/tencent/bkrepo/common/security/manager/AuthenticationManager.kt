/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2021 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.common.security.manager

import com.fasterxml.jackson.core.JsonProcessingException
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.tencent.bkrepo.auth.api.ServiceAccountClient
import com.tencent.bkrepo.auth.api.ServiceOauthAuthorizationClient
import com.tencent.bkrepo.auth.api.ServiceTemporaryTokenClient
import com.tencent.bkrepo.auth.api.ServiceUserClient
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizationGrantType
import com.tencent.bkrepo.auth.pojo.oauth.OauthToken
import com.tencent.bkrepo.auth.pojo.token.TemporaryTokenInfo
import com.tencent.bkrepo.auth.pojo.user.CreateUserRequest
import com.tencent.bkrepo.auth.pojo.user.UserInfo
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Base64
import java.util.Optional
import java.util.concurrent.TimeUnit

@Component
class AuthenticationManager {
    @Autowired
    private lateinit var serviceUserClient: ServiceUserClient

    @Autowired
    private lateinit var serviceAccountClient: ServiceAccountClient

    @Autowired
    private lateinit var serviceOauthAuthorizationClient: ServiceOauthAuthorizationClient

    @Autowired
    private lateinit var serviceTemporaryTokenClient: ServiceTemporaryTokenClient

    internal var clock: Clock = Clock.systemUTC()

    private val oauthTokenCache: LoadingCache<String, Optional<OauthToken>> = CacheBuilder.newBuilder()
        .maximumSize(3000)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build(CacheLoader.from { accessToken ->
            Optional.ofNullable(serviceOauthAuthorizationClient.getToken(accessToken).data)
        })

    /**
     * 校验普通用户类型账户
     * @throws AuthenticationException 校验失败
     */
    fun checkUserAccount(uid: String, token: String): String {
        val response = serviceUserClient.checkToken(uid, token)
        return if (response.data == true) uid else throw AuthenticationException("Authorization value check failed")
    }

    /**
     * 校验平台账户
     * @throws AuthenticationException 校验失败
     */
    fun checkPlatformAccount(accessKey: String, secretKey: String): String {
        val response = serviceAccountClient.checkAccountCredential(
            accesskey = accessKey,
            secretkey = secretKey,
            authorizationGrantType = AuthorizationGrantType.PLATFORM
        )
        return response.data ?: throw AuthenticationException("AccessKey/SecretKey check failed.")
    }

    /**
     * 校验Oauth Token
     */
    fun checkOauthToken(accessToken: String): String {
        val response = serviceOauthAuthorizationClient.validateToken(accessToken)
        return response.data ?: throw AuthenticationException("Access token check failed.")
    }

    /**
     * 普通用户类型账户
     */
    fun createUserAccount(userId: String) {
        val request = CreateUserRequest(userId = userId, name = userId)
        serviceUserClient.createUser(request)
    }

    /**
     * 根据用户id[userId]查询用户信息
     * 当用户不存在时返回`null`
     */
    fun findUserAccount(userId: String): UserInfo? {
        return serviceUserClient.userInfoById(userId).data
    }
    /**
     * 根据用户id[userId]查询用户密码
     * 当用户不存在时返回`null`
     */
    fun findUserPwd(userId: String): String? {
        return serviceUserClient.userPwdById(userId).data
    }

    /**
     * 根据用户id[userId]查询用户token
     */
    fun findUserToken(userId: String): List<String>? {
        return serviceUserClient.userTokenById(userId).data
    }

    /**
     * 查询 OAuth access token。
     * 结果缓存 1 分钟；命中后再读取 JWT `exp` 与当前时间比较，已过期则抛出 [AuthenticationException]。
     */
    fun findOauthToken(accessToken: String): OauthToken? {
        val token = oauthTokenCache.get(accessToken).orElse(null) ?: return null
        val expirationEpochSecond = readJwtExp(token.accessToken) ?: return token
        if (clock.instant().epochSecond >= expirationEpochSecond) {
            oauthTokenCache.invalidate(accessToken)
            throw AuthenticationException("Expired token")
        }
        return token
    }

    private fun readJwtExp(jwt: String): Long? {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        val payloadBytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val expNode = try {
            JsonUtils.objectMapper.readTree(payloadBytes).get("exp")
        } catch (_: JsonProcessingException) {
            return null
        } ?: return null
        return if (expNode.isNumber) expNode.longValue() else null
    }

    /**
     * 根据appId和ak查找sk
     * */
    fun findSecretKey(appId: String, accessKey: String): String? {
        return serviceAccountClient.findSecretKey(appId, accessKey).data
    }

    fun getTokenInfo(token: String): TemporaryTokenInfo? {
        return serviceTemporaryTokenClient.getTokenInfo(token).data
    }

    fun findUserByToken(token: String): UserInfo {
        return serviceUserClient.userInfoByToken(token).data
            ?: throw AuthenticationException("Access token check failed.")
    }
}
