package com.tencent.bkrepo.common.storage.config

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

@DisplayName("存储加密配置测试")
class EncryptPropertiesTest {

    @Test
    fun `should have empty key by default`() {
        val properties = EncryptProperties()

        assertEquals(false, properties.enabled)
        assertEquals("", properties.key)
        assertDoesNotThrow { properties.requireKeyIfEnabled() }
    }

    @Test
    fun `should pass when encrypt disabled even if key is blank`() {
        val properties = EncryptProperties(enabled = false, key = "  ")

        assertDoesNotThrow { properties.requireKeyIfEnabled() }
    }

    @Test
    fun `should require key when encrypt enabled`() {
        val properties = EncryptProperties(enabled = true)

        val exception = assertThrows<ErrorCodeException> { properties.requireKeyIfEnabled() }
        assertEquals(CommonMessageCode.PARAMETER_MISSING, exception.messageCode)
        assertEquals("encrypt.key", exception.params.first())
    }

    @Test
    fun `should reject blank key when encrypt enabled`() {
        val properties = EncryptProperties(enabled = true, key = "   ")

        assertThrows<ErrorCodeException> { properties.requiredKey() }
    }

    @Test
    fun `should return configured key when encrypt enabled`() {
        val properties = EncryptProperties(enabled = true, key = "custom-encrypt-key")

        assertEquals("custom-encrypt-key", properties.requiredKey())
    }

    @Test
    fun `should fail storage properties validation when default encrypt enabled without key`() {
        val storageProperties = StorageProperties().apply {
            filesystem.encrypt.enabled = true
        }

        assertThrows<ErrorCodeException> { storageProperties.validate() }
    }
}
