package com.tencent.bkrepo.preview.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("内联预览 Content-Disposition")
class PreviewInlineDispositionTest {

    @Test
    fun `resolveFileName prefers original path basename`() {
        assertEquals(
            "季度汇报.pptx",
            PreviewInlineDisposition.resolveFileName("/works/季度汇报.pptx", "分享标题"),
        )
        assertEquals("deck.pptx", PreviewInlineDisposition.resolveFileName("/talk/deck.pptx", null))
        assertEquals("deck.pptx", PreviewInlineDisposition.resolveFileName("/talk/deck.pptx", "  "))
    }

    @Test
    fun `resolveFileName falls back to artifact name then default`() {
        assertEquals("分享标题", PreviewInlineDisposition.resolveFileName("/", "分享标题"))
        assertEquals("preview.pdf", PreviewInlineDisposition.resolveFileName(null, null))
        assertEquals("preview.pdf", PreviewInlineDisposition.resolveFileName("  ", "  "))
    }

    @Test
    fun `encode keeps ascii filename and adds filename star`() {
        val header = PreviewInlineDisposition.encode("deck.pptx")
        assertEquals("inline;filename=\"deck.pptx\";filename*=UTF-8''deck.pptx", header)
    }

    @Test
    fun `encode percent-encodes chinese filename`() {
        val header = PreviewInlineDisposition.encode("季度汇报.pptx")
        assertTrue(header.startsWith("inline;filename=\""))
        assertTrue(header.contains("filename*=UTF-8''"))
        assertTrue(header.contains("%"))
        assertFalse(header.contains("attachment"))
    }
}
