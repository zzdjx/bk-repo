package com.tencent.bkrepo.preview.controller

import com.tencent.bkrepo.common.api.constant.ANONYMOUS_USER
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareInfo
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareOpenInfo
import com.tencent.bkrepo.preview.service.share.ArtifactShareNativePdfPreview
import com.tencent.bkrepo.preview.service.share.ArtifactShareService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse

@DisplayName("作品分享短链错误页响应")
class ArtifactShareControllerTest {

    private val artifactShareService = mockk<ArtifactShareService>()
    private val nativePdfPreview = mockk<ArtifactShareNativePdfPreview>(relaxed = true)
    private lateinit var controller: ArtifactShareController

    @BeforeEach
    fun setUp() {
        controller = ArtifactShareController(artifactShareService, nativePdfPreview)
    }

    @Test
    fun `short link access denied returns 403 html`() {
        every { artifactShareService.open(ANONYMOUS_USER, "id-1") } throws ErrorCodeException(
            PreviewMessageCode.PREVIEW_ARTIFACT_SHARE_ACCESS_DENIED,
            "alice",
        )
        val response = MockHttpServletResponse()

        controller.openRedirect("id-1", response)

        assertEquals(403, response.status)
        assertTrue(response.contentType?.startsWith(MediaType.TEXT_HTML_VALUE) == true)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(response.contentAsString.contains("无权限访问"))
        assertTrue(response.contentAsString.contains("请联系 alice"))
    }

    @Test
    fun `short link missing share returns 404 html`() {
        every { artifactShareService.open(ANONYMOUS_USER, "missing") } throws ErrorCodeException(
            PreviewMessageCode.PREVIEW_ARTIFACT_SHARE_NOT_FOUND,
            "missing",
        )
        val response = MockHttpServletResponse()

        controller.openRedirect("missing", response)

        assertEquals(404, response.status)
        assertTrue(response.contentAsString.contains("无分享作品"))
        assertTrue(response.contentAsString.contains("分享作品不存在或已删除"))
        assertFalse(response.contentAsString.contains("我已开通权限，点此重试"))
    }

    @Test
    fun `short link missing node returns 404 html`() {
        every { artifactShareService.open(ANONYMOUS_USER, "id-1") } throws ErrorCodeException(
            PreviewMessageCode.PREVIEW_NODE_NOT_FOUND,
            9L,
        )
        val response = MockHttpServletResponse()

        controller.openRedirect("id-1", response)

        assertEquals(404, response.status)
        assertTrue(response.contentAsString.contains("无分享作品"))
        assertTrue(response.contentAsString.contains("分享作品不存在或已删除"))
    }

    @Test
    fun `short code missing share returns 404 html`() {
        every { artifactShareService.openByShortShareId(ANONYMOUS_USER, "Ab12Cd34") } throws ErrorCodeException(
            PreviewMessageCode.PREVIEW_ARTIFACT_SHARE_NOT_FOUND,
            "Ab12Cd34",
        )
        val response = MockHttpServletResponse()

        controller.openByShortShareId("Ab12Cd34", response)

        assertEquals(404, response.status)
        assertTrue(response.contentAsString.contains("无分享作品"))
        assertTrue(response.contentAsString.contains("分享作品不存在或已删除"))
    }

    @Test
    fun `short code access denied returns 403 html`() {
        every { artifactShareService.openByShortShareId(ANONYMOUS_USER, "Ab12Cd34") } throws ErrorCodeException(
            PreviewMessageCode.PREVIEW_ARTIFACT_SHARE_ACCESS_DENIED,
            "alice",
        )
        val response = MockHttpServletResponse()

        controller.openByShortShareId("Ab12Cd34", response)

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("无权限访问"))
    }

    @Test
    fun `system error on short link is not rendered as html`() {
        every { artifactShareService.open(ANONYMOUS_USER, "id-1") } throws ErrorCodeException(
            CommonMessageCode.SYSTEM_ERROR,
        )
        val response = MockHttpServletResponse()

        assertThrows(ErrorCodeException::class.java) {
            controller.openRedirect("id-1", response)
        }
        assertFalse(response.contentAsString.contains("无权限访问"))
        assertFalse(response.contentAsString.contains("无分享作品"))
    }

    @Test
    fun `short link success still returns preview html`() {
        val share = mockk<ArtifactShareInfo>()
        every { share.artifactName } returns "demo"
        val openInfo = mockk<ArtifactShareOpenInfo>()
        every { openInfo.previewUrl } returns "/ui/p/filePreview"
        every { openInfo.share } returns share
        every { artifactShareService.open(ANONYMOUS_USER, "id-1") } returns openInfo
        val response = MockHttpServletResponse()

        controller.openRedirect("id-1", response)

        assertEquals(200, response.status)
        assertTrue(response.contentAsString.contains("<iframe"))
        assertTrue(response.contentAsString.contains("demo"))
    }
}
