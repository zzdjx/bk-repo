package com.tencent.bkrepo.preview.service.share

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.preview.constant.PREVIEW_RESPONSE_FILENAME
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareInfo
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareKind
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareOpenInfo
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareResourceType
import com.tencent.bkrepo.preview.pojo.share.ShareVisibility
import com.tencent.bkrepo.preview.service.FileHandlerService
import com.tencent.bkrepo.preview.service.FilePreview
import com.tencent.bkrepo.preview.service.FilePreviewFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

@DisplayName("作品分享短链原生 PDF 预览")
class ArtifactShareNativePdfPreviewTest {

    private val fileHandlerService = mockk<FileHandlerService>()
    private val previewFactory = mockk<FilePreviewFactory>()
    private val nativePdfPreview = ArtifactShareNativePdfPreview(fileHandlerService, previewFactory)

    @Test
    fun `pdf type streams via preview handler`() {
        val fileAttribute = FileAttribute()
        val filePreview = mockk<FilePreview>(relaxed = true)
        val artifactInfo = slot<ArtifactInfo>()
        every { fileHandlerService.getFileAttribute(capture(artifactInfo), null) } returns fileAttribute
        every { previewFactory[fileAttribute] } returns filePreview

        assertTrue(nativePdfPreview.writeIfApplicable(openInfo(type = "pdf", fullPath = "/docs/a.pdf")))

        assertEquals("p", artifactInfo.captured.projectId)
        assertEquals("r", artifactInfo.captured.repoName)
        assertEquals("/docs/a.pdf", artifactInfo.captured.getArtifactFullPath())
        verify(exactly = 1) { filePreview.filePreviewHandle(fileAttribute) }
    }

    @Test
    fun `pdf type writes original filename onto request`() {
        val fileAttribute = FileAttribute()
        val filePreview = mockk<FilePreview>(relaxed = true)
        every { fileHandlerService.getFileAttribute(any(), null) } returns fileAttribute
        every { previewFactory[fileAttribute] } returns filePreview
        val request = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            assertTrue(nativePdfPreview.writeIfApplicable(openInfo(type = "pdf", fullPath = "/docs/report.pdf")))
            assertEquals("report.pdf", request.getAttribute(PREVIEW_RESPONSE_FILENAME))
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `slides type is not native pdf`() {
        assertFalse(nativePdfPreview.writeIfApplicable(openInfo(type = "slides", fullPath = "/talk/deck.pptx")))
        verify(exactly = 0) { fileHandlerService.getFileAttribute(any<ArtifactInfo>(), null) }
    }

    @Test
    fun `html type is not native pdf`() {
        assertFalse(nativePdfPreview.writeIfApplicable(openInfo(type = "html", fullPath = "/sites/index.html")))
        verify(exactly = 0) { fileHandlerService.getFileAttribute(any<ArtifactInfo>(), null) }
    }

    private fun openInfo(type: String?, fullPath: String): ArtifactShareOpenInfo {
        return ArtifactShareOpenInfo(
            share = ArtifactShareInfo(
                shareId = "id-1",
                shareKind = ArtifactShareKind.MATERIAL,
                resourceType = ArtifactShareResourceType.DRIVE_NODE,
                projectId = "p",
                repoName = "r",
                resourceId = 1L,
                fullPath = fullPath,
                visibility = ShareVisibility.PUBLIC,
                artifactName = "demo",
                type = type,
                sharePath = "/share/Ab12Cd34",
                createdBy = "alice",
                createdDate = TIME,
                lastModifiedDate = TIME,
            ),
            previewUrl = "/ui/p/filePreview/local/0/r$fullPath?token=t",
            downloadUrl = "/download",
        )
    }

    companion object {
        private val TIME = LocalDateTime.of(2026, 1, 1, 0, 0)
    }
}
