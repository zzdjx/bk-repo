package com.tencent.bkrepo.common.security.service

import com.tencent.bkrepo.common.api.exception.SystemErrorException
import com.tencent.bkrepo.common.security.util.JwtUtils
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

@DisplayName("ServiceAuthManager测试")
internal class ServiceAuthManagerTest {

    @Test
    @DisplayName("enabled且密钥为空时拒绝启动")
    fun rejectBlankSecretKeyWhenEnabled() {
        assertThrows<IllegalArgumentException> {
            ServiceAuthManager(ServiceAuthProperties(enabled = true, secretKey = ""))
        }
        assertThrows<IllegalArgumentException> {
            ServiceAuthManager(ServiceAuthProperties(enabled = true, secretKey = "   "))
        }
    }

    @Test
    @DisplayName("previousSecretKey 可校验另一把密钥签发的 token")
    fun verifyTokenSignedByPreviousKey() {
        val oldManager = ServiceAuthManager(ServiceAuthProperties(secretKey = OLD_KEY))
        val newManager = ServiceAuthManager(
            ServiceAuthProperties(secretKey = NEW_KEY, previousSecretKey = OLD_KEY)
        )
        newManager.verifySecurityToken(newManager.getSecurityToken())
        newManager.verifySecurityToken(oldManager.getSecurityToken())
        assertThrows<SystemErrorException> {
            newManager.verifySecurityToken(JwtUtils.generateToken(JwtUtils.createSigningKey(OTHER_KEY), TOKEN_TTL))
        }
    }

    @Test
    @DisplayName("空白previousSecretKey不参与校验")
    fun blankPreviousSecretKeyIsIgnored() {
        val manager = ServiceAuthManager(
            ServiceAuthProperties(secretKey = NEW_KEY, previousSecretKey = "   ")
        )
        val paddedEmptyToken = JwtUtils.generateToken(JwtUtils.createSigningKey(""), TOKEN_TTL)
        assertThrows<SystemErrorException> {
            manager.verifySecurityToken(paddedEmptyToken)
        }
    }

    companion object {
        private const val OLD_KEY = "old-service-key"
        private const val NEW_KEY = "new-service-key"
        private const val OTHER_KEY = "other-service-key"
        private val TOKEN_TTL = Duration.ofMinutes(10)
    }
}
