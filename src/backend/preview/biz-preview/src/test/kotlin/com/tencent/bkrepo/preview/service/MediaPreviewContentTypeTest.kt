package com.tencent.bkrepo.preview.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("媒体预览 Content-Type")
class MediaPreviewContentTypeTest {

    @Test
    fun `maps whitelist suffixes to browser playable mime types`() {
        assertEquals("video/mp4", MediaPreviewContentType.fromSuffix("mp4"))
        assertEquals("video/webm", MediaPreviewContentType.fromSuffix("webm"))
        assertEquals("audio/mpeg", MediaPreviewContentType.fromSuffix("mp3"))
        assertEquals("audio/wav", MediaPreviewContentType.fromSuffix("wav"))
        assertEquals("audio/ogg", MediaPreviewContentType.fromSuffix("ogg"))
        assertEquals("audio/ogg", MediaPreviewContentType.fromSuffix("oga"))
        assertEquals("video/mp4", MediaPreviewContentType.fromSuffix("m4v"))
        assertEquals("video/quicktime", MediaPreviewContentType.fromSuffix("mov"))
        assertEquals("video/x-matroska", MediaPreviewContentType.fromSuffix("mkv"))
        assertEquals("video/x-flv", MediaPreviewContentType.fromSuffix("flv"))
        assertEquals("audio/aac", MediaPreviewContentType.fromSuffix("aac"))
        assertEquals("application/pdf", MediaPreviewContentType.fromSuffix("pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            MediaPreviewContentType.fromSuffix("pptx")
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            MediaPreviewContentType.fromSuffix("ppsx")
        )
        assertEquals(
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
            MediaPreviewContentType.fromSuffix("pptm")
        )
        assertEquals(
            "application/vnd.ms-powerpoint",
            MediaPreviewContentType.fromSuffix("ppt")
        )
    }

    @Test
    fun `suffix matching is case insensitive`() {
        assertEquals("video/mp4", MediaPreviewContentType.fromSuffix("MP4"))
        assertEquals("application/pdf", MediaPreviewContentType.fromSuffix("PDF"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            MediaPreviewContentType.fromSuffix("PPTX")
        )
    }

    @Test
    fun `unknown suffix has no media preview content type`() {
        assertNull(MediaPreviewContentType.fromSuffix("exe"))
        assertNull(MediaPreviewContentType.fromSuffix("docx"))
    }
}
