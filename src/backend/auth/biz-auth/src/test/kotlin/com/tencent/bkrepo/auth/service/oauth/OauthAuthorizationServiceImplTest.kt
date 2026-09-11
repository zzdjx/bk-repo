package com.tencent.bkrepo.auth.service.oauth

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
import com.tencent.bkrepo.auth.pojo.oauth.GenerateTokenRequest
import com.tencent.bkrepo.auth.pojo.oauth.OauthToken
import com.tencent.bkrepo.auth.pojo.token.CredentialSet
import com.tencent.bkrepo.auth.service.UserService
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.common.redis.RedisOperation
import com.tencent.bkrepo.common.security.crypto.CryptoProperties
import com.tencent.bkrepo.common.security.util.RsaUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.KeyPairGenerator
import java.time.LocalDateTime
import java.util.Base64

@DisplayName("Oauth 授权码兑换与 refresh 安全行为")
class OauthAuthorizationServiceImplTest {

    private val accountDao: AccountDao = mockk()
    private val oauthTokenRepository: OauthTokenRepository = mockk()
    private val userService: UserService = mockk(relaxed = true)
    private val redisOperation: RedisOperation = mockk()

    private val redis = mutableMapOf<String, String>()
    private val tokens = mutableListOf<TOauthToken>()

    private lateinit var service: OauthAuthorizationServiceImpl
    private lateinit var response: MockHttpServletResponse

    @BeforeEach
    fun setUp() {
        redis.clear()
        tokens.clear()
        every { redisOperation.get(any()) } answers { redis[firstArg()] }
        every { redisOperation.getAndDelete(any()) } answers { redis.remove(firstArg()) }
        every { redisOperation.set(any(), any(), any()) } answers {
            redis[firstArg()] = secondArg()
        }
        every { redisOperation.delete(any<String>()) } answers { redis.remove(firstArg()) != null }
        every { redisOperation.delete(any<Collection<String>>()) } answers {
            firstArg<Collection<String>>().forEach { redis.remove(it) }
        }
        every { accountDao.findById(CLIENT_ID) } returns buildAccount()
        every { oauthTokenRepository.insert(any<TOauthToken>()) } answers {
            val token = firstArg<TOauthToken>()
            tokens.add(token)
            token
        }
        every { oauthTokenRepository.findFirstByAccessToken(any()) } answers {
            val accessToken = firstArg<String>()
            tokens.find { it.accessToken == accessToken }
        }
        every { oauthTokenRepository.findFirstByAccountIdAndRefreshToken(any(), any()) } answers {
            val accountId = firstArg<String>()
            val refreshToken = secondArg<String>()
            tokens.find { it.accountId == accountId && it.refreshToken == refreshToken }
        }
        every { oauthTokenRepository.deleteByAccessToken(any()) } answers {
            val accessToken = firstArg<String>()
            tokens.removeIf { it.accessToken == accessToken }
        }
        every { oauthTokenRepository.deleteByAccountIdAndRefreshToken(any(), any()) } answers {
            val accountId = firstArg<String>()
            val refreshToken = secondArg<String>()
            val before = tokens.size
            tokens.removeIf { it.accountId == accountId && it.refreshToken == refreshToken }
            (before - tokens.size).toLong()
        }
        service = OauthAuthorizationServiceImpl(
            accountDao = accountDao,
            oauthTokenRepository = oauthTokenRepository,
            userService = userService,
            redisOperation = redisOperation,
            cryptoProperties = CryptoProperties(
                privateKeyStr2048PKCS8 = PRIVATE_KEY_2048,
                publicKeyStr2048PKCS8 = RsaUtils.derivePublicKey(PRIVATE_KEY_2048)
            ),
            oauthProperties = OauthProperties()
        )
        bindRequest()
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    @DisplayName("公开 OAuth 客户端刷新可不带 client_secret，并轮换 refresh token")
    fun `public oauth client can refresh without secret`() {
        val issued = exchangeAuthorizationCode("code-refresh")
        val oldRefreshToken = issued.refreshToken
        assertNotNull(oldRefreshToken)
        bindRequest(clientSecret = null)

        service.refreshToken(
            GenerateTokenRequest(
                code = null,
                grantType = "refresh_token",
                clientId = CLIENT_ID,
                clientSecret = null,
                refreshToken = oldRefreshToken,
                scope = null,
                codeVerifier = null
            )
        )
        val rotated = readToken()
        assertNotEquals(oldRefreshToken, rotated.refreshToken)
        assertEquals(USER_ID, service.validateToken(rotated.accessToken))
        assertNull(service.validateToken(issued.accessToken))

        bindRequest(clientSecret = null)
        val exception = assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = null,
                    refreshToken = oldRefreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }
        assertEquals(OauthErrorType.INVALID_GRANT, exception.error)
    }

    @Test
    @DisplayName("错误 client_secret 时 refresh 失败")
    fun `refresh with wrong client secret is rejected`() {
        val issued = exchangeAuthorizationCode("code-refresh-wrong-secret")

        val exception = assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = "wrong-secret",
                    refreshToken = issued.refreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }

        assertEquals(OauthErrorType.UNAUTHORIZED_CLIENT, exception.error)
    }

    @Test
    @DisplayName("私有 OAuth 客户端省略 client_secret 时 refresh 失败")
    fun `confidential oauth client cannot refresh without secret`() {
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(publicClient = false)
        val issued = exchangeAuthorizationCode("code-refresh-confidential")

        bindRequest(clientSecret = null)
        val exception = assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = null,
                    refreshToken = issued.refreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }
        assertEquals(OauthErrorType.UNAUTHORIZED_CLIENT, exception.error)
        assertEquals(USER_ID, service.validateToken(issued.accessToken))

        bindRequest()
        service.refreshToken(
            GenerateTokenRequest(
                code = null,
                grantType = "refresh_token",
                clientId = CLIENT_ID,
                clientSecret = CLIENT_SECRET,
                refreshToken = issued.refreshToken,
                scope = null,
                codeVerifier = null
            )
        )
        assertEquals(USER_ID, service.validateToken(readToken().accessToken))
        assertNull(service.validateToken(issued.accessToken))
    }

    @Test
    @DisplayName("refresh 成功后旧 refresh token 不能再用")
    fun `refresh rotates and invalidates the previous refresh token`() {
        val issued = exchangeAuthorizationCode("code-refresh-rotate")
        val oldRefreshToken = issued.refreshToken
        assertNotNull(oldRefreshToken)

        bindRequest()
        service.refreshToken(
            GenerateTokenRequest(
                code = null,
                grantType = "refresh_token",
                clientId = CLIENT_ID,
                clientSecret = CLIENT_SECRET,
                refreshToken = oldRefreshToken,
                scope = null,
                codeVerifier = null
            )
        )
        val rotated = readToken()
        assertNotEquals(oldRefreshToken, rotated.refreshToken)
        assertNotEquals(issued.accessToken, rotated.accessToken)
        verifyOrder {
            oauthTokenRepository.findFirstByAccountIdAndRefreshToken(CLIENT_ID, oldRefreshToken!!)
            oauthTokenRepository.insert(any<TOauthToken>())
            oauthTokenRepository.deleteByAccountIdAndRefreshToken(CLIENT_ID, oldRefreshToken)
        }

        assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = CLIENT_SECRET,
                    refreshToken = oldRefreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }
        assertNull(service.validateToken(issued.accessToken))
        assertEquals(USER_ID, service.validateToken(rotated.accessToken))
    }

    @Test
    @DisplayName("refresh 写入新票失败时旧 refresh token 仍可用")
    fun `refresh keeps old token when insert fails`() {
        val issued = exchangeAuthorizationCode("code-refresh-insert-fail")
        val oldRefreshToken = issued.refreshToken
        assertNotNull(oldRefreshToken)

        every { oauthTokenRepository.insert(any<TOauthToken>()) } throws RuntimeException("mongo insert failed")
        bindRequest()
        assertThrows<RuntimeException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = CLIENT_SECRET,
                    refreshToken = oldRefreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }
        assertEquals(USER_ID, service.validateToken(issued.accessToken))

        every { oauthTokenRepository.insert(any<TOauthToken>()) } answers {
            val token = firstArg<TOauthToken>()
            tokens.add(token)
            token
        }
        service.refreshToken(
            GenerateTokenRequest(
                code = null,
                grantType = "refresh_token",
                clientId = CLIENT_ID,
                clientSecret = CLIENT_SECRET,
                refreshToken = oldRefreshToken,
                scope = null,
                codeVerifier = null
            )
        )
        val rotated = readToken()
        assertNotEquals(oldRefreshToken, rotated.refreshToken)
        assertEquals(USER_ID, service.validateToken(rotated.accessToken))
        assertNull(service.validateToken(issued.accessToken))
    }

    @Test
    @DisplayName("refresh 旧票已被消费时回滚刚写入的新票")
    fun `refresh deletes the new token if old refresh was already consumed`() {
        val issued = exchangeAuthorizationCode("code-refresh-race")
        every { oauthTokenRepository.deleteByAccountIdAndRefreshToken(any(), any()) } returns 0L

        val exception = assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = CLIENT_SECRET,
                    refreshToken = issued.refreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }

        assertEquals(OauthErrorType.INVALID_GRANT, exception.error)
        assertEquals(1, tokens.size)
        assertEquals(issued.accessToken, tokens.single().accessToken)
        assertEquals(USER_ID, service.validateToken(issued.accessToken))
    }

    @Test
    @DisplayName("吊销后 validateToken 不再接受未过期 JWT")
    fun `validateToken rejects revoked access token`() {
        val issued = exchangeAuthorizationCode("code-revoke")
        assertEquals(USER_ID, service.validateToken(issued.accessToken))

        service.deleteToken(CLIENT_ID, CLIENT_SECRET, issued.accessToken)

        assertNull(service.validateToken(issued.accessToken))
    }

    @Test
    @DisplayName("授权码兑换后不能重放")
    fun `authorization code cannot be exchanged twice`() {
        putAuthorizationCode("code-replay")
        service.createToken(authorizationCodeRequest("code-replay"))
        assertNotNull(readToken().accessToken)

        val exception = assertThrows<OauthException> {
            service.createToken(authorizationCodeRequest("code-replay"))
        }
        assertEquals(OauthErrorType.INVALID_REQUEST, exception.error)
    }

    @Test
    @DisplayName("未启用 PKCE 时任意 verifier 不能跳过 client_secret")
    fun `dummy code verifier cannot skip client secret without pkce challenge`() {
        bindRequest(clientSecret = null)
        putAuthorizationCode("code-pkce-skip")

        val exception = assertThrows<OauthException> {
            service.createToken(
                GenerateTokenRequest(
                    code = "code-pkce-skip",
                    grantType = "authorization_code",
                    clientId = CLIENT_ID,
                    clientSecret = null,
                    refreshToken = null,
                    scope = null,
                    codeVerifier = "any-verifier"
                )
            )
        }
        assertEquals(OauthErrorType.INVALID_REQUEST, exception.error)
    }

    @Test
    @DisplayName("私有 OAuth 客户端不能用 PKCE 跳过 client_secret")
    fun `confidential oauth client cannot skip secret with pkce`() {
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(publicClient = false)
        bindRequest(clientSecret = null)
        putAuthorizationCode("code-pkce-confidential", challenge = "plain:$CODE_VERIFIER")

        val exception = assertThrows<OauthException> {
            service.createToken(pkceTokenRequest("code-pkce-confidential"))
        }
        assertEquals(OauthErrorType.UNAUTHORIZED_CLIENT, exception.error)
    }

    @Test
    @DisplayName("PKCE 公共客户端可以不带 secret 刷新且轮换 refresh token")
    fun `public client can refresh without secret`() {
        bindRequest(clientSecret = null)
        putAuthorizationCode("code-public-refresh", challenge = "plain:$CODE_VERIFIER")
        service.createToken(pkceTokenRequest("code-public-refresh"))
        val issued = readToken()
        val oldRefreshToken = issued.refreshToken
        assertNotNull(oldRefreshToken)

        bindRequest(clientSecret = null)
        service.refreshToken(
            GenerateTokenRequest(
                code = null,
                grantType = "refresh_token",
                clientId = CLIENT_ID,
                clientSecret = null,
                refreshToken = oldRefreshToken,
                scope = null,
                codeVerifier = null
            )
        )
        val rotated = readToken()
        assertNotEquals(oldRefreshToken, rotated.refreshToken)
        assertNotEquals(issued.accessToken, rotated.accessToken)
        assertEquals(USER_ID, service.validateToken(rotated.accessToken))
        assertNull(service.validateToken(issued.accessToken))

        bindRequest(clientSecret = null)
        service.refreshToken(
            GenerateTokenRequest(
                code = null,
                grantType = "refresh_token",
                clientId = CLIENT_ID,
                clientSecret = null,
                refreshToken = rotated.refreshToken,
                scope = null,
                codeVerifier = null
            )
        )
        assertEquals(USER_ID, service.validateToken(readToken().accessToken))
        assertNull(service.validateToken(rotated.accessToken))

        bindRequest(clientSecret = null)
        val exception = assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = null,
                    refreshToken = oldRefreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }
        assertEquals(OauthErrorType.INVALID_GRANT, exception.error)
    }

    @Test
    @DisplayName("OIDC discovery 声明公共客户端可用 none 认证")
    fun `oidc discovery advertises none token endpoint auth`() {
        val configuration = service.getOidcConfiguration("repo-dev-test")
        assertEquals(
            listOf("client_secret_basic", "client_secret_post", "none"),
            configuration.tokenEndpointAuthMethodsSupported
        )
    }

    @Test
    @DisplayName("启用 PKCE 的公开客户端可用 verifier 兑换")
    fun `public client with pkce can exchange code without secret`() {
        bindRequest(clientSecret = null)
        putAuthorizationCode("code-pkce", challenge = "plain:$CODE_VERIFIER")

        service.createToken(
            GenerateTokenRequest(
                code = "code-pkce",
                grantType = "authorization_code",
                clientId = CLIENT_ID,
                clientSecret = null,
                refreshToken = null,
                scope = null,
                codeVerifier = CODE_VERIFIER
            )
        )

        assertEquals(USER_ID, service.validateToken(readToken().accessToken))
    }

    @Test
    @DisplayName("DISABLE 的授权码凭证不能兑换 token")
    fun `disabled authorization code credential cannot exchange token`() {
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(status = CredentialStatus.DISABLE)
        putAuthorizationCode("code-disabled")

        val exception = assertThrows<OauthException> {
            service.createToken(authorizationCodeRequest("code-disabled"))
        }
        assertEquals(OauthErrorType.UNAUTHORIZED_CLIENT, exception.error)
    }

    @Test
    @DisplayName("DISABLE 的授权码凭证不能 refresh")
    fun `disabled authorization code credential cannot refresh`() {
        val issued = exchangeAuthorizationCode("code-disabled-refresh")
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(status = CredentialStatus.DISABLE)

        val exception = assertThrows<OauthException> {
            service.refreshToken(
                GenerateTokenRequest(
                    code = null,
                    grantType = "refresh_token",
                    clientId = CLIENT_ID,
                    clientSecret = CLIENT_SECRET,
                    refreshToken = issued.refreshToken,
                    scope = null,
                    codeVerifier = null
                )
            )
        }
        assertEquals(OauthErrorType.UNAUTHORIZED_CLIENT, exception.error)
        assertEquals(USER_ID, service.validateToken(issued.accessToken))
    }

    @Test
    @DisplayName("DISABLE 的公开客户端不能跳过 client_secret")
    fun `disabled public client cannot skip secret`() {
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(status = CredentialStatus.DISABLE)
        bindRequest(clientSecret = null)
        putAuthorizationCode("code-disabled-public", challenge = "plain:$CODE_VERIFIER")

        val exception = assertThrows<OauthException> {
            service.createToken(pkceTokenRequest("code-disabled-public"))
        }
        assertEquals(OauthErrorType.UNAUTHORIZED_CLIENT, exception.error)
    }

    @Test
    @DisplayName("ENABLE 的 client_credentials 可以换 token")
    fun `enabled client credentials can create token`() {
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(
            grantType = AuthorizationGrantType.CLIENT_CREDENTIALS
        )

        service.createToken(clientCredentialsRequest())

        assertEquals("owner", service.validateToken(readToken().accessToken))
    }

    @Test
    @DisplayName("DISABLE 的 client_credentials 不能换 token")
    fun `disabled client credentials cannot create token`() {
        every { accountDao.findById(CLIENT_ID) } returns buildAccount(
            status = CredentialStatus.DISABLE,
            grantType = AuthorizationGrantType.CLIENT_CREDENTIALS
        )

        val exception = assertThrows<ErrorCodeException> {
            service.createToken(clientCredentialsRequest())
        }
        assertEquals(AuthMessageCode.AUTH_CLIENT_NOT_EXIST, exception.messageCode)
    }

    private fun exchangeAuthorizationCode(code: String): OauthToken {
        putAuthorizationCode(code)
        service.createToken(authorizationCodeRequest(code))
        return readToken()
    }

    private fun pkceTokenRequest(code: String) = GenerateTokenRequest(
        code = code,
        grantType = "authorization_code",
        clientId = CLIENT_ID,
        clientSecret = null,
        refreshToken = null,
        scope = null,
        codeVerifier = CODE_VERIFIER
    )

    private fun authorizationCodeRequest(code: String) = GenerateTokenRequest(
        code = code,
        grantType = "authorization_code",
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        refreshToken = null,
        scope = null,
        codeVerifier = null
    )

    private fun clientCredentialsRequest() = GenerateTokenRequest(
        code = null,
        grantType = "client_credentials",
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        refreshToken = null,
        scope = null,
        codeVerifier = null
    )

    private fun putAuthorizationCode(code: String, challenge: String? = null) {
        redis["$CLIENT_ID:$code:userId"] = USER_ID
        if (challenge != null) {
            redis["$CLIENT_ID:$code:challenge"] = challenge
        }
    }

    private fun readToken(): OauthToken {
        return JsonUtils.objectMapper.readValue(response.contentAsString, OauthToken::class.java)
    }

    private fun bindRequest(clientSecret: String? = CLIENT_SECRET) {
        val request = MockHttpServletRequest()
        request.setParameter("client_id", CLIENT_ID)
        if (clientSecret != null) {
            request.setParameter("client_secret", clientSecret)
        }
        response = MockHttpServletResponse()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request, response))
    }

    private fun buildAccount(
        publicClient: Boolean = true,
        status: CredentialStatus = CredentialStatus.ENABLE,
        grantType: AuthorizationGrantType = AuthorizationGrantType.AUTHORIZATION_CODE
    ) = TAccount(
        id = CLIENT_ID,
        appId = "app-1",
        locked = false,
        credentials = listOf(
            CredentialSet(
                accessKey = "ak",
                secretKey = CLIENT_SECRET,
                createdAt = LocalDateTime.now(),
                status = status,
                authorizationGrantType = grantType,
                publicClient = publicClient
            )
        ),
        owner = "owner",
        authorizationGrantTypes = setOf(grantType),
        homepageUrl = "http://localhost",
        redirectUri = "http://localhost/redirect",
        avatarUrl = null,
        scope = setOf(ResourceType.PROJECT),
        limit = null,
        scopeDesc = null,
        description = null,
        createdDate = LocalDateTime.now(),
        lastModifiedDate = LocalDateTime.now()
    )

    companion object {
        private const val CLIENT_ID = "client-1"
        private const val CLIENT_SECRET = "secret-1"
        private const val USER_ID = "user-1"
        private const val CODE_VERIFIER = "pkce-verifier"

        /**
         * Oauth 的 JWT 走 RS256，jjwt 按 RFC 7518 要求密钥不低于 2048 位
         */
        private val PRIVATE_KEY_2048 = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            Base64.getEncoder().encodeToString(generateKeyPair().private.encoded)
        }
    }
}
