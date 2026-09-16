package com.tencent.bkrepo.preview.controller

import com.tencent.bkrepo.common.api.constant.ANONYMOUS_USER
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareInfo
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareKind
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareOpenInfo
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareResourceType
import com.tencent.bkrepo.preview.pojo.share.ShareVisibility
import com.tencent.bkrepo.preview.service.share.ArtifactShareNativePdfPreview
import com.tencent.bkrepo.preview.service.share.ArtifactShareService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import java.time.LocalDateTime

@DisplayName("作品分享短链响应")
class ArtifactShareControllerNativePreviewTest {

    private val artifactShareService = mockk<ArtifactShareService>()
    private val nativePdfPreview = mockk<ArtifactShareNativePdfPreview>(relaxed = true)
    private lateinit var controller: ArtifactShareController

    @BeforeEach
    fun setUp() {
        controller = ArtifactShareController(artifactShareService, nativePdfPreview)
    }

    @Test
    fun `html share still returns iframe page`() {
        val openInfo = openInfo("html", "/sites/index.html")
        every { artifactShareService.open(ANONYMOUS_USER, "id-1") } returns openInfo
        every { nativePdfPreview.writeIfApplicable(openInfo) } returns false
        val response = MockHttpServletResponse()

        controller.openRedirect("id-1", response)

        assertTrue(response.contentType?.startsWith(MediaType.TEXT_HTML_VALUE) == true)
        assertTrue(response.contentAsString.contains("<iframe"))
        assertNull(response.getHeader("Location"))
        verify(exactly = 1) { nativePdfPreview.writeIfApplicable(openInfo) }
    }

    @Test
    fun `pdf share streams on the original short url`() {
        val openInfo = openInfo("pdf", "/docs/a.pdf")
        every { artifactShareService.open(ANONYMOUS_USER, "id-1") } returns openInfo
        every { nativePdfPreview.writeIfApplicable(openInfo) } returns true
        val response = MockHttpServletResponse()

        controller.openRedirect("id-1", response)

        assertEquals(200, response.status)
        assertNull(response.getHeader("Location"))
        assertFalse(response.contentAsString.contains("<iframe"))
        verify(exactly = 1) { nativePdfPreview.writeIfApplicable(openInfo) }
    }

    @Test
    fun `slides share still returns iframe page`() {
        val openInfo = openInfo("slides", "/talk/deck.pptx")
        every { artifactShareService.openByShortShareId(ANONYMOUS_USER, "Ab12Cd34") } returns openInfo
        every { nativePdfPreview.writeIfApplicable(openInfo) } returns false
        val response = MockHttpServletResponse()

        controller.openByShortShareId("Ab12Cd34", response)

        assertTrue(response.contentType?.startsWith(MediaType.TEXT_HTML_VALUE) == true)
        assertTrue(response.contentAsString.contains("<iframe"))
        assertFalse(response.contentAsString.contains("文件加载中，请稍后"))
        assertNull(response.getHeader("Location"))
        verify(exactly = 1) { nativePdfPreview.writeIfApplicable(openInfo) }
    }

    private fun openInfo(type: String, fullPath: String): ArtifactShareOpenInfo {
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
