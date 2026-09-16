package com.tencent.bkrepo.huggingface.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HuggingFace 重定向 Authorization 转发策略")
class HfRedirectAuthTest {

    @Test
    fun `keeps authorization on same host redirects`() {
        assertTrue(
            HfRedirectAuth.shouldForwardAuthorization("huggingface.co", "huggingface.co")
        )
        assertTrue(
            HfRedirectAuth.shouldForwardAuthorization("HF-MIRROR.EXAMPLE.COM", "hf-mirror.example.com")
        )
    }

    @Test
    fun `strips authorization when a mirror redirects to huggingface_co`() {
        assertFalse(
            HfRedirectAuth.shouldForwardAuthorization("hf-mirror.example.com", "huggingface.co")
        )
        assertFalse(
            HfRedirectAuth.shouldForwardAuthorization("mirror.internal", "hf.co")
        )
    }

    @Test
    fun `strips authorization on cdn or storage hosts`() {
        assertFalse(
            HfRedirectAuth.shouldForwardAuthorization("huggingface.co", "cdn-lfs.huggingface.co")
        )
        assertFalse(
            HfRedirectAuth.shouldForwardAuthorization("huggingface.co", "cas-bridge.xethub.hf.co")
        )
        assertFalse(
            HfRedirectAuth.shouldForwardAuthorization("huggingface.co", "s3.amazonaws.com")
        )
    }

    @Test
    fun `strips authorization when target host is missing`() {
        assertFalse(HfRedirectAuth.shouldForwardAuthorization("huggingface.co", null))
        assertFalse(HfRedirectAuth.shouldForwardAuthorization("huggingface.co", ""))
    }
}
