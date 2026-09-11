package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PPTX 原样预览判定")
class LivePptxPreviewTest {

    @Test
    fun `supports live slide suffixes`() {
        assertTrue(LivePptxPreview.isSupported("pptx"))
        assertTrue(LivePptxPreview.isSupported("PPTX"))
        assertTrue(LivePptxPreview.isSupported("pptm"))
        assertTrue(LivePptxPreview.isSupported("ppsx"))
        assertTrue(LivePptxPreview.isSupported("ppt"))
        assertTrue(LivePptxPreview.isSupported("PPT"))
        assertTrue(LivePptxPreview.isOoxml("pptx"))
        assertTrue(LivePptxPreview.isOoxml("PPTM"))
        assertFalse(LivePptxPreview.isOoxml("ppt"))
        assertFalse(LivePptxPreview.isOoxml("pdf"))
    }

    @Test
    fun `local pptx streams original unless convert=pdf`() {
        val attr = localPptx()
        assertTrue(LivePptxPreview.shouldStreamOriginal(attr))
        assertTrue(LivePptxPreview.shouldSkipOfficeConvert(attr))

        attr.forcePdfConvert = true
        assertFalse(LivePptxPreview.shouldStreamOriginal(attr))
        assertFalse(LivePptxPreview.shouldSkipOfficeConvert(attr))
    }

    @Test
    fun `remote pptx skips office convert but does not stream by artifact id`() {
        val attr = FileAttribute(
            storageType = 1,
            url = "https://example.com/deck.pptx",
            fileName = "deck.pptx",
            suffix = "pptx",
            type = FileType.OFFICE
        )
        assertFalse(LivePptxPreview.shouldStreamOriginal(attr))
        assertTrue(LivePptxPreview.shouldSkipOfficeConvert(attr))
    }

    @Test
    fun `legacy ppt streams original and skips office convert`() {
        val attr = localPptx().copy(suffix = "ppt", fileName = "deck.ppt")
        assertTrue(LivePptxPreview.isSupported("ppt"))
        assertTrue(LivePptxPreview.shouldStreamOriginal(attr))
        assertTrue(LivePptxPreview.shouldSkipOfficeConvert(attr))
        assertFalse(LivePptxPreview.isOoxml("ppt"))
    }

    private fun localPptx() = FileAttribute(
        storageType = 0,
        projectId = "p1",
        repoName = "generic-local",
        artifactUri = "/talk/deck.pptx",
        fileName = "deck.pptx",
        suffix = "pptx",
        type = FileType.OFFICE
    )
}
