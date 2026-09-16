package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PPTX 内嵌媒体 part 查询参数")
class FileHandlerServiceEmbeddedPartTest {

    private val service = FileHandlerService(PreviewConfig(), mockk<CommonResourceService>(), mockk())

    @Test
    fun `blank part is ignored`() {
        val attr = localPptx()
        service.applyEmbeddedPartQuery(attr, " ")
        assertNull(attr.embeddedPartPath)
    }

    @Test
    fun `legal media part is normalized`() {
        val attr = localPptx()
        service.applyEmbeddedPartQuery(attr, "/ppt/media/media1.mp4")
        assertEquals("ppt/media/media1.mp4", attr.embeddedPartPath)
    }

    @Test
    fun `path traversal part is rejected`() {
        val attr = localPptx()
        val exception = assertThrows(PreviewInvalidException::class.java) {
            service.applyEmbeddedPartQuery(attr, "ppt/media/../slides/slide1.xml")
        }
        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `legacy ppt cannot stream zip parts`() {
        val attr = localPptx().copy(suffix = "ppt", fileName = "deck.ppt")
        val exception = assertThrows(PreviewInvalidException::class.java) {
            service.applyEmbeddedPartQuery(attr, "ppt/media/media1.mp4")
        }
        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
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
