/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
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

package com.tencent.bkrepo.auth.service.oauth

import cn.hutool.core.codec.Base64Decoder
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.tencent.bkrepo.auth.config.OauthProperties
import com.tencent.bkrepo.auth.dao.AccountDao
import com.tencent.bkrepo.auth.dao.repository.OauthTokenRepository
import com.tencent.bkrepo.auth.exception.OauthException
import com.tencent.bkrepo.auth.message.AuthMessageCode
import com.tencent.bkrepo.auth.model.TAccount
import com.tencent.bkrepo.auth.model.TOauthToken
import com.tencent.bkrepo.auth.pojo.enums.CredentialStatus
import com.tencent.bkrepo.auth.pojo.enums.OauthErrorType
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizationGrantType
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizeRequest
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizedResult
import com.tencent.bkrepo.auth.pojo.oauth.GenerateTokenRequest
import com.tencent.bkrepo.auth.pojo.oauth.IdToken
import com.tencent.bkrepo.auth.pojo.oauth.JsonWebKey
import com.tencent.bkrepo.auth.pojo.oauth.JsonWebKeySet
import com.tencent.bkrepo.auth.pojo.oauth.OauthToken
import com.tencent.bkrepo.auth.pojo.oauth.OidcConfiguration
import com.tencent.bkrepo.auth.pojo.oauth.UserInfo
import com.tencent.bkrepo.auth.pojo.token.CredentialSet
import com.tencent.bkrepo.auth.service.OauthAuthorizationService
import com.tencent.bkrepo.auth.service.UserService
import com.tencent.bkrepo.auth.util.OauthUtils
import com.tencent.bkrepo.common.api.constant.BASIC_AUTH_PREFIX
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.common.api.util.Preconditions
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.api.util.toXmlString
import com.tencent.bkrepo.common.artifact.hash.HashAlgorithm
import com.tencent.bkrepo.common.redis.RedisOperation
import com.tencent.bkrepo.common.security.crypto.CryptoProperties
import com.tencent.bkrepo.common.security.util.JwtUtils
import com.tencent.bkrepo.common.security.util.RsaUtils
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.common.service.util.HeaderUtils
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import io.jsonwebtoken.SignatureAlgorithm
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

@Service
class OauthAuthorizationServiceImpl(
    private val accountDao: AccountDao,
    private val oauthTokenRepository: OauthTokenRepository,
    private val userService: UserService,
    private val redisOperation: RedisOperation,
    private val cryptoProperties: CryptoProperties,
    private val oauthProperties: OauthProperties
) : OauthAuthorizationService {

    override fun authorized(authorizeRequest: AuthorizeRequest): AuthorizedResult {
        with(authorizeRequest) {
            val userId = SecurityUtils.getUserId()
            val client = accountDao.findById(clientId)
                    ?: throw OauthException(OauthErrorType.INVALID_CLIENT, "client[$clientId] not found]")
            val code = OauthUtils.generateCode()

            val userIdKey = "$clientId:$code:userId"
            val openIdKey = "$clientId:$code:openId"
            val expiredInSecond = TimeUnit.MINUTES.toSeconds(10L)
            redisOperation.set(userIdKey, userId, expiredInSecond)
            if (!nonce.isNullOrBlank()) {
                val nonceKey = "$clientId:$code:nonce"
                redisOperation.set(nonceKey, nonce!!, expiredInSecond)
            }
            if (!codeChallenge.isNullOrBlank() && !codeChallengeMethod.isNullOrBlank()) {
                val challengeKey = "$clientId:$code:challenge"
                redisOperation.set(challengeKey, "${codeChallengeMethod}:${codeChallenge}", expiredInSecond)
            }
            if (scope.orEmpty().contains("openid")) {
                redisOperation.set(openIdKey, true.toString(), expiredInSecond)
            }

            return AuthorizedResult(
                redirectUrl = "${client.redirectUri!!.removeSuffix(StringPool.SLASH)}?code=$code&state=$state",
                userId = userId,
                appId = client.appId,
                scope = client.scope?.toList() ?: emptyList()
            )
        }
    }

    override fun createToken(generateTokenRequest: GenerateTokenRequest) {
        val (clientId, clientSecret) = resolveClientCredentials(generateTokenRequest)
        val tOauthToken =
            if (generateTokenRequest.grantType.equals(AuthorizationGrantType.AUTHORIZATION_CODE.value(), true)) {
                createAuthorizationCodeToken(generateTokenRequest, clientId, clientSecret)
            } else if (generateTokenRequest.grantType.equals(AuthorizationGrantType.CLIENT_CREDENTIALS.value(), true)) {
                createClientCredentialsToken(clientId, clientSecret)
            } else {
                throw OauthException(OauthErrorType.UNSUPPORTED_GRANT_TYPE, generateTokenRequest.grantType)
            }
        responseToken(transfer(tOauthToken))
    }

    override fun refreshToken(generateTokenRequest: GenerateTokenRequest) {
        val (clientId, clientSecret) = resolveClientCredentials(generateTokenRequest)
        val refreshToken = generateTokenRequest.refreshToken
            ?: throw OauthException(OauthErrorType.INVALID_REQUEST, "refresh_token")
        val existing = oauthTokenRepository.findFirstByAccountIdAndRefreshToken(clientId, refreshToken)
            ?: throw OauthException(OauthErrorType.INVALID_GRANT, "refresh token[$refreshToken] not found")
        val client = checkClientSecret(clientId, clientSecret, null, null)
        val token = buildOauthToken(
            userId = existing.userId,
            nonce = OauthUtils.generateRandomString(10),
            client = client,
            openId = existing.idToken != null
        )
        val deleted = oauthTokenRepository.deleteByAccountIdAndRefreshToken(clientId, refreshToken)
        if (deleted == 0L) {
            oauthTokenRepository.deleteByAccessToken(token.accessToken)
            throw OauthException(OauthErrorType.INVALID_GRANT, "refresh token[$refreshToken] not found")
        }
        responseToken(transfer(token))
    }

    private fun resolveClientCredentials(generateTokenRequest: GenerateTokenRequest): Pair<String, String?> {
        val authorization = HeaderUtils.getHeader(HttpHeaders.AUTHORIZATION)?.removePrefix(BASIC_AUTH_PREFIX)
        if (!authorization.isNullOrBlank()) {
            val decoded = Base64Decoder.decodeStr(authorization)
            val separatorIndex = decoded.indexOf(StringPool.COLON)
            if (separatorIndex <= 0) {
                throw OauthException(OauthErrorType.INVALID_REQUEST, "client_id")
            }
            val headerClientId = decoded.substring(0, separatorIndex).takeIf { it.isNotBlank() }
                ?: throw OauthException(OauthErrorType.INVALID_REQUEST, "client_id")
            val headerClientSecret = decoded.substring(separatorIndex + 1).takeIf { it.isNotBlank() }
            return headerClientId to headerClientSecret
        }
        val request = HttpContextHolder.getRequestOrNull()
        val clientId = generateTokenRequest.clientId?.takeIf { it.isNotBlank() }
            ?: request?.getParameter("client_id")?.takeIf { it.isNotBlank() }
            ?: throw OauthException(OauthErrorType.INVALID_REQUEST, "client_id")
        val clientSecret = generateTokenRequest.clientSecret?.takeIf { it.isNotBlank() }
            ?: request?.getParameter("client_secret")?.takeIf { it.isNotBlank() }
        return clientId to clientSecret
    }

    private fun createAuthorizationCodeToken(
        generateTokenRequest: GenerateTokenRequest,
        clientId: String,
        clientSecret: String?
    ): TOauthToken {
        val code = generateTokenRequest.code
            ?: throw OauthException(OauthErrorType.INVALID_REQUEST, "code")
        val userIdKey = "$clientId:$code:userId"
        val openIdKey = "$clientId:$code:openId"
        val nonceKey = "$clientId:$code:nonce"
        val challengeKey = "$clientId:$code:challenge"
        val userId = redisOperation.getAndDelete(userIdKey)
            ?: throw OauthException(OauthErrorType.INVALID_REQUEST, "auth code check failed")
        try {
            val openId = redisOperation.get(openIdKey).toBoolean()
            val nonce = redisOperation.get(nonceKey)
            val client = checkClientSecret(clientId, clientSecret, code, generateTokenRequest.codeVerifier)
            val accountId = client.id
                ?: throw OauthException(OauthErrorType.INVALID_CLIENT, "client[$clientId] not found")
            val tOauthToken = buildOauthToken(userId, nonce, client, openId)
            userService.addUserAccount(userId, accountId)
            return tOauthToken
        } finally {
            redisOperation.delete(listOf(openIdKey, nonceKey, challengeKey))
        }
    }

    private fun createClientCredentialsToken(
        clientId: String,
        clientSecret: String?
    ): TOauthToken {
        Preconditions.checkNotBlank(clientSecret, "client_secret")
        val client = accountDao.findById(clientId) ?: throw ErrorCodeException(AuthMessageCode.AUTH_CLIENT_NOT_EXIST)
        enabledCredentials(client).find {
            it.authorizationGrantType == AuthorizationGrantType.CLIENT_CREDENTIALS &&
                MessageDigest.isEqual(it.secretKey.toByteArray(), clientSecret?.toByteArray())
        } ?: throw ErrorCodeException(AuthMessageCode.AUTH_CLIENT_NOT_EXIST)
        return buildOauthToken(client.owner!!, null, client, false)
    }

    private fun buildOauthToken(
        userId: String,
        nonce: String?,
        client: TAccount,
        openId: Boolean
    ): TOauthToken {
        val idToken = generateOpenIdToken(client.id!!, userId, nonce)
        val tOauthToken = TOauthToken(
            accessToken = idToken.toJwtToken(client.scope),
            refreshToken = OauthUtils.generateRefreshToken(),
            expireSeconds = oauthProperties.expiredDuration.seconds,
            type = "Bearer",
            accountId = client.id,
            userId = userId,
            scope = client.scope,
            issuedAt = Instant.now(Clock.systemDefaultZone()),
            idToken = if (openId) idToken else null,
        )
        oauthTokenRepository.insert(tOauthToken)
        return tOauthToken
    }

    private fun responseToken(token: OauthToken) {
        val accept = HeaderUtils.getHeader(HttpHeaders.ACCEPT).orEmpty()
        val response = HttpContextHolder.getResponse()
        response.addHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.addHeader(HttpHeaders.PRAGMA, "no-cache")
        when {
            accept.contains(MediaType.APPLICATION_XML_VALUE) -> {
                response.contentType = MediaTypes.APPLICATION_XML
                response.writer.write(token.toXmlString())
            }

            else -> {
                response.contentType = MediaTypes.APPLICATION_JSON
                response.writer.write(token.toJsonString())
            }
        }
    }

    private fun generateOpenIdToken(clientId: String, userId: String, nonce: String?): IdToken {
        val clock = Clock.systemDefaultZone()
        return IdToken(
            iss = "${oauthProperties.domain}/auth/api/oauth",
            sub = userId,
            aud = listOf(clientId),
            exp = Instant.now(clock).plusSeconds(oauthProperties.expiredDuration.seconds).epochSecond,
            iat = Instant.now(clock).epochSecond,
            nonce = nonce
        )
    }

    override fun getToken(accessToken: String): OauthToken? {
        val tOauthToken = oauthTokenRepository.findFirstByAccessToken(accessToken) ?: return null
        return transfer(tOauthToken)
    }

    override fun validateToken(accessToken: String): String? {
        val claims = JwtUtils.validateToken(
            signingKey = RsaUtils.stringToPublicKey(cryptoProperties.publicKeyStr2048PKCS8),
            token = accessToken
        )
        oauthTokenRepository.findFirstByAccessToken(accessToken) ?: return null
        return claims.body.subject
    }

    override fun deleteToken(clientId: String, clientSecret: String, accessToken: String) {
        checkClientSecret(clientId, clientSecret, null, null)
        oauthTokenRepository.deleteByAccessToken(accessToken)
    }

    override fun getUserInfo(): UserInfo {
        val userId = SecurityUtils.getUserId()
        return UserInfo(userId, userId)
    }

    override fun getOidcConfiguration(projectId: String): OidcConfiguration {
        return OidcConfiguration(
            issuer = "${oauthProperties.domain}/auth/api/oauth/${projectId}",
            authorizationEndpoint = "${oauthProperties.domain}/ui/${projectId}/oauth/authorize",
            tokenEndpoint = "${oauthProperties.domain}/auth/api/oauth/${projectId}/token",
            jwksUri = "${oauthProperties.domain}/auth/api/oauth/${projectId}/.well-known/jwks.json",
            responseTypesSupported = listOf("code"),
            subjectTypesSupported = listOf("public"),
            userinfoEndpoint = "${oauthProperties.domain}/auth/api/oauth/${projectId}/userInfo",
            scopesSupported = listOf("openid", "offline_access")
        )
    }

    override fun getJwks(): JsonWebKeySet {
        val publicKey = RsaUtils.stringToPublicKey(cryptoProperties.publicKeyStr2048PKCS8)
        val key = JsonWebKey(
            kty = "RSA",
            kid = KEY_ID_VALUE,
            use = "sig",
            alg = SignatureAlgorithm.RS256.value,
            n = Base64.getUrlEncoder().encodeToString(publicKey.modulus.toByteArray()),
            e = Base64.getUrlEncoder().encodeToString(publicKey.publicExponent.toByteArray()),
            x5t = StringPool.EMPTY,
            x5c = emptyList()
        )
        return JsonWebKeySet(listOf(key))
    }

    private fun transfer(tOauthToken: TOauthToken) = OauthToken(
        accessToken = tOauthToken.accessToken,
        tokenType = tOauthToken.type,
        scope = if (tOauthToken.scope == null) "" else tOauthToken.scope!!.joinToString(StringPool.COMMA),
        idToken = tOauthToken.idToken?.toJwtToken(tOauthToken.scope),
        refreshToken = tOauthToken.refreshToken,
        expiresIn = if (tOauthToken.expireSeconds == null) {
            null
        } else {
            tOauthToken.issuedAt.epochSecond + tOauthToken.expireSeconds -
                    Instant.now(Clock.systemDefaultZone()).epochSecond
        }
    )

    private fun IdToken.toJwtToken(scope: Set<ResourceType>?): String {
        val claims = mutableMapOf<String, Any>()
        claims["scope"] = scope.orEmpty()
        claims.putAll(JsonUtils.objectMapper.convertValue(this, jacksonTypeRef()))
        return JwtUtils.generateToken(
            signingKey = RsaUtils.stringToPrivateKey(cryptoProperties.privateKeyStr2048PKCS8),
            expireDuration = oauthProperties.expiredDuration,
            subject = sub,
            claims = claims,
            header = mapOf(KEY_ID_NAME to KEY_ID_VALUE),
            algorithm = SignatureAlgorithm.RS256
        )
    }

    private fun checkClientSecret(
        clientId: String,
        clientSecret: String?,
        code: String?,
        codeVerifier: String?
    ): TAccount {
        val client = accountDao.findById(clientId)
            ?: throw OauthException(OauthErrorType.INVALID_CLIENT, "client[$clientId] not found")
        val challenge = if (code.isNullOrBlank()) {
            null
        } else {
            redisOperation.get("$clientId:$code:challenge")
        }
        resolveOauthCredential(client, clientSecret, code, codeVerifier, challenge)
        if (!code.isNullOrBlank()) {
            checkCodeVerifier(challenge, codeVerifier)
        }
        return client
    }

    private fun resolveOauthCredential(
        client: TAccount,
        clientSecret: String?,
        code: String?,
        codeVerifier: String?,
        challenge: String?
    ): CredentialSet {
        val codeCredentials = enabledCredentials(client).filter {
            it.authorizationGrantType == AuthorizationGrantType.AUTHORIZATION_CODE
        }
        val credential = if (!clientSecret.isNullOrBlank()) {
            codeCredentials.find {
                MessageDigest.isEqual(it.secretKey.toByteArray(), clientSecret.toByteArray())
            }
        } else if (codeCredentials.any { it.publicClient }) {
            if (!code.isNullOrBlank() && (codeVerifier.isNullOrBlank() || challenge.isNullOrBlank())) {
                throw OauthException(OauthErrorType.INVALID_REQUEST, "need clientSecret or codeVerifier")
            }
            codeCredentials.find { it.publicClient }
        } else {
            throw OauthException(OauthErrorType.UNAUTHORIZED_CLIENT, "auth secret check failed")
        }
        return credential ?: throw OauthException(OauthErrorType.UNAUTHORIZED_CLIENT, "auth secret check failed")
    }

    private fun enabledCredentials(client: TAccount) =
        client.credentials.filter { it.status == CredentialStatus.ENABLE }

    private fun checkCodeVerifier(challengeValue: String?, codeVerifier: String?) {
        if (challengeValue.isNullOrBlank()) {
            return
        }
        val (method, challenge) = challengeValue.split(StringPool.COLON)
        val pass = when (method) {
            "plain" -> MessageDigest.isEqual(challenge.toByteArray(), codeVerifier?.toByteArray())
            "S256" -> {
                val computed = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(HashAlgorithm.SHA256().digest(codeVerifier.orEmpty().byteInputStream()))
                MessageDigest.isEqual(computed.toByteArray(), challenge.toByteArray())
            }
            else -> false
        }
        if (!pass) {
            throw OauthException(OauthErrorType.INVALID_REQUEST, "code_verifier")
        }
    }

    companion object {
        private const val KEY_ID_NAME = "kid"
        private const val KEY_ID_VALUE = "bkrepo_rsa_rs256"
    }
}
