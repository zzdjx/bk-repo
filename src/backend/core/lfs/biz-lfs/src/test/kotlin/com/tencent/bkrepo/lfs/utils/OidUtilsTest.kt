package com.tencent.bkrepo.lfs.utils

import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("LFS oid 路径转换")
class OidUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = ["", "a", "ab", "abc"])
    @DisplayName("oid 长度不足时应返回参数错误而不是越界")
    fun `should reject oid shorter than 4 characters`(oid: String) {
        val exception = assertThrows<ErrorCodeException> {
            OidUtils.convertToFullPath(oid)
        }

        assertEquals(CommonMessageCode.PARAMETER_INVALID, exception.messageCode)
        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    @DisplayName("最短合法 oid 应按前四位切分存储路径")
    fun `should convert four-character oid to nested storage path`() {
        assertEquals("/ab/cd/abcd", OidUtils.convertToFullPath(FOUR_CHAR_OID))
    }

    @Test
    @DisplayName("合法 64 位 hex oid 应按前四位切分存储路径")
    fun `should convert sha256 oid to nested storage path`() {
        assertEquals(SHA256_OID_FULL_PATH, OidUtils.convertToFullPath(SHA256_OID))
    }

    @Test
    @DisplayName("存储路径应能还原为 oid")
    fun `should convert storage path back to oid`() {
        assertEquals(SHA256_OID, OidUtils.convertToOid(SHA256_OID_FULL_PATH))
    }

    companion object {
        private const val FOUR_CHAR_OID = "abcd"
        private const val SHA256_OID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private const val SHA256_OID_FIRST_DIR = "01"
        private const val SHA256_OID_SECOND_DIR = "23"
        private const val SHA256_OID_FULL_PATH =
            "/$SHA256_OID_FIRST_DIR/$SHA256_OID_SECOND_DIR/$SHA256_OID"
    }
}
