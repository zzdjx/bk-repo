package com.tencent.bkrepo.common.metadata.config

import com.tencent.bkrepo.common.api.util.checkurl.SecUrlValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyChannelUrlCheckTest {

    @Test
    fun `proxy channel url blocks literal private addresses by default`() {
        val props = RepositoryProperties().proxyChannelUrl
        assertTrue(props.blockPrivateIp)
        assertFalse(SecUrlValidator.isValid("http://127.0.0.1", props))
        assertFalse(SecUrlValidator.isValid("http://10.0.0.1", props))
        assertFalse(SecUrlValidator.isValid("http://169.254.169.254", props))
        assertTrue(SecUrlValidator.isValid("https://repo.example.com", props))
    }
}
