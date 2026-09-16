package com.tencent.bkrepo.replication.config

import com.tencent.bkrepo.replication.fdtp.FdtpServerProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

@DisplayName("FDTP 服务端认证密钥")
class FdtpServerConfigurationTest {

    @Test
    @DisplayName("未配置时密钥为空")
    fun secretKeyIsBlankWhenUnset() {
        assertTrue(FdtpServerProperties().secretKey.isBlank())
    }

    @Test
    @DisplayName("启用 FDTP 时拒绝长度不足的密钥")
    fun rejectShortSecretKey() {
        assertThrows<IllegalArgumentException> {
            FdtpServerProperties().afterPropertiesSet()
        }
        assertThrows<IllegalArgumentException> {
            FdtpServerProperties(secretKey = "short").afterPropertiesSet()
        }
        assertThrows<IllegalArgumentException> {
            FdtpServerProperties(secretKey = "0".repeat(63)).afterPropertiesSet()
        }
    }

    @Test
    @DisplayName("配置达到长度要求的密钥时可创建认证管理器")
    fun acceptConfiguredSecretKey() {
        assertDoesNotThrow {
            val properties = FdtpServerProperties(secretKey = "0".repeat(64))
            properties.afterPropertiesSet()
            FdtpServerConfiguration().fdtpAuthManager(properties)
        }
    }
}
