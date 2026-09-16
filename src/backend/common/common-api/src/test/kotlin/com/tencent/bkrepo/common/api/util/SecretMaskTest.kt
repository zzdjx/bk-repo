package com.tencent.bkrepo.common.api.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("配置密钥掩码")
class SecretMaskTest {

    @Test
    fun `mask blank stays blank and value becomes star`() {
        assertNull(SecretMask.mask(null))
        assertEquals("", SecretMask.mask(""))
        assertEquals(SecretMask.MASKED, SecretMask.mask("real-token"))
    }

    @Test
    fun `keep existing on blank or all stars`() {
        assertTrue(SecretMask.isKeepExisting(null))
        assertTrue(SecretMask.isKeepExisting(""))
        assertTrue(SecretMask.isKeepExisting("*"))
        assertTrue(SecretMask.isKeepExisting("******"))
        assertEquals("real-token", SecretMask.keep("***", "real-token"))
        assertEquals("real-token", SecretMask.keep("", "real-token"))
        assertEquals("new-token", SecretMask.keep("new-token", "real-token"))
        assertEquals("a***b", SecretMask.keep("a***b", "real-token"))
    }
}
