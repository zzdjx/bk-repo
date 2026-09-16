package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.exception.PreviewNotFoundException
import com.tencent.bkrepo.preview.pojo.DownloadResult
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.OfficeFileConverter
import com.tencent.bkrepo.preview.service.PptxEmbeddedMediaService
import com.tencent.bkrepo.preview.service.cache.impl.PreviewFileCacheServiceImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Office 文件预览")
class OfficeFilePreviewImplTest {

    private val fileTransferService = mockk<FileTransferService>(relaxed = true)
    private val pptxEmbeddedMediaService = mockk<PptxEmbeddedMediaService>(relaxed = true)
    private val preview = OfficeFilePreviewImpl(
        PreviewConfig(),
        mockk<OfficeFileConverter>(),
        fileTransferService,
        mockk<PreviewFileCacheServiceImpl>(),
        mockk<NodeService>(),
        pptxEmbeddedMediaService
    )

    @Test
    fun `local pptx preview slims ooxml package`() {
        val fileAttribute = localPptx()

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { pptxEmbeddedMediaService.streamSlimPackage(fileAttribute) }
        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
        verify(exactly = 0) { fileTransferService.download(any()) }
    }

    @Test
    fun `local pptx convert pdf does not stream original`() {
        val fileAttribute = localPptx().copy(forcePdfConvert = true)
        every { fileTransferService.download(any()) } returns DownloadResult(code = DownloadResult.CODE_FAIL)

        assertThrows(PreviewNotFoundException::class.java) {
            preview.filePreviewHandle(fileAttribute)
        }

        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
        verify(exactly = 1) { fileTransferService.download(fileAttribute) }
    }

    @Test
    fun `local ppt streams original without slimming`() {
        val fileAttribute = localPptx().copy(suffix = "ppt", fileName = "deck.ppt", artifactUri = "/talk/deck.ppt")

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
        verify(exactly = 0) { pptxEmbeddedMediaService.streamSlimPackage(any()) }
        verify(exactly = 0) { fileTransferService.download(any()) }
    }

    @Test
    fun `embedded part streams zip media`() {
        val fileAttribute = localPptx().copy(embeddedPartPath = "ppt/media/media1.mp4")

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { pptxEmbeddedMediaService.streamPart(fileAttribute) }
        verify(exactly = 0) { pptxEmbeddedMediaService.streamSlimPackage(any()) }
        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
    }

    private fun localPptx() = FileAttribute(
        storageType = 0,
        projectId = "p1",
        repoName = "generic-local",
        artifactUri = "/talk/deck.pptx",
        fileName = "deck.pptx",
        suffix = "pptx",
        type = FileType.OFFICE,
        size = 2L * 1024 * 1024
    )
}
