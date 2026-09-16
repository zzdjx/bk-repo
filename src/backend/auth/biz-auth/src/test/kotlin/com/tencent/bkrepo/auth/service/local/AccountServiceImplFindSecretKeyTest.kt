package com.tencent.bkrepo.auth.service.local

import com.tencent.bkrepo.auth.dao.AccountDao
import com.tencent.bkrepo.auth.model.TAccount
import com.tencent.bkrepo.auth.pojo.enums.CredentialStatus
import com.tencent.bkrepo.auth.pojo.oauth.AuthorizationGrantType
import com.tencent.bkrepo.auth.pojo.token.CredentialSet
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.query.Query
import java.time.LocalDateTime

@DisplayName("签名认证取 sk 时过滤凭证状态")
class AccountServiceImplFindSecretKeyTest {

    private val accountDao: AccountDao = mockk()
    private val service = AccountServiceImpl(
        accountDao = accountDao,
        oauthTokenRepository = mockk(),
        userDao = mockk()
    )

    @Test
    fun `findSecretKey returns secret of enabled credential`() {
        every { accountDao.findOne(any<Query>(), TAccount::class.java) } returns buildAccount(
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            status = CredentialStatus.ENABLE
        )

        assertEquals(SECRET_KEY, service.findSecretKey(APP_ID, ACCESS_KEY))
    }

    @Test
    fun `findSecretKey returns null when credential is disabled`() {
        every { accountDao.findOne(any<Query>(), TAccount::class.java) } returns buildAccount(
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            status = CredentialStatus.DISABLE
        )

        assertNull(service.findSecretKey(APP_ID, ACCESS_KEY))
    }

    @Test
    fun `findSecretKey does not return another credential secret when target is disabled`() {
        every { accountDao.findOne(any<Query>(), TAccount::class.java) } returns TAccount(
            id = "id",
            appId = APP_ID,
            locked = false,
            credentials = listOf(
                CredentialSet(
                    accessKey = ACCESS_KEY,
                    secretKey = SECRET_KEY,
                    createdAt = LocalDateTime.now(),
                    status = CredentialStatus.DISABLE,
                    authorizationGrantType = AuthorizationGrantType.PLATFORM
                ),
                CredentialSet(
                    accessKey = "other-ak",
                    secretKey = "other-sk",
                    createdAt = LocalDateTime.now(),
                    status = CredentialStatus.ENABLE,
                    authorizationGrantType = AuthorizationGrantType.PLATFORM
                )
            ),
            owner = "owner",
            authorizationGrantTypes = setOf(AuthorizationGrantType.PLATFORM),
            homepageUrl = null,
            redirectUri = null,
            avatarUrl = null,
            scope = null,
            limit = null,
            scopeDesc = null,
            description = null,
            createdDate = LocalDateTime.now(),
            lastModifiedDate = LocalDateTime.now()
        )

        assertNull(service.findSecretKey(APP_ID, ACCESS_KEY))
    }

    private fun buildAccount(accessKey: String, secretKey: String, status: CredentialStatus) = TAccount(
        id = "id",
        appId = APP_ID,
        locked = false,
        credentials = listOf(
            CredentialSet(
                accessKey = accessKey,
                secretKey = secretKey,
                createdAt = LocalDateTime.now(),
                status = status,
                authorizationGrantType = AuthorizationGrantType.PLATFORM
            )
        ),
        owner = "owner",
        authorizationGrantTypes = setOf(AuthorizationGrantType.PLATFORM),
        homepageUrl = null,
        redirectUri = null,
        avatarUrl = null,
        scope = null,
        limit = null,
        scopeDesc = null,
        description = null,
        createdDate = LocalDateTime.now(),
        lastModifiedDate = LocalDateTime.now()
    )

    companion object {
        private const val APP_ID = "app-1"
        private const val ACCESS_KEY = "ak-1"
        private const val SECRET_KEY = "sk-1"
    }
}
