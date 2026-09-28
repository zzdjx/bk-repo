package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.PdfArtifactNameReader
import com.tencent.bkrepo.preview.service.cache.impl.PreviewFileCacheServiceImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

@DisplayName("PDF 文件预览")
class PdfFilePreviewImplTest {

    private val fileTransferService = mockk<FileTransferService>(relaxed = true)
    private val fileAttribute = FileAttribute(
        storageType = 0,
        projectId = "p1",
        repoName = "drive-local",
        artifactUri = "/works/demo.pdf",
        fileName = "demo.pdf",
        suffix = "pdf",
        type = FileType.PDF,
        size = 80L * 1024 * 1024
    )

    @Test
    fun `local pdf without artifact name streams original artifact`() {
        val preview = preview(PdfArtifactNameReader { null })

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
        verify(exactly = 0) { fileTransferService.readOriginalBytes(any()) }
        verify(exactly = 0) { fileTransferService.sendRangedBytes(any(), any()) }
    }

    @Test
    fun `local pdf rewrites title before ranged response`() {
        every { fileTransferService.readOriginalBytes(fileAttribute) } returns pdf(info = "草稿")
        val bytes = slot<ByteArray>()
        every { fileTransferService.sendRangedBytes(fileAttribute, capture(bytes)) } returns Unit
        val preview = preview(PdfArtifactNameReader { "周报" })

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
        PDDocument.load(bytes.captured).use { document ->
            assertEquals("周报.pdf", document.documentInformation.title)
        }
    }

    private fun preview(nameReader: PdfArtifactNameReader): PdfFilePreviewImpl {
        return PdfFilePreviewImpl(
            PreviewConfig(),
            fileTransferService,
            mockk<PreviewFileCacheServiceImpl>(),
            mockk<NodeService>(),
            nameReader,
        )
    }
}

private fun pdf(info: String): ByteArray {
    PDDocument().use { document ->
        document.addPage(PDPage())
        document.documentInformation.title = info
        val output = ByteArrayOutputStream()
        document.save(output)
        return output.toByteArray()
    }
}
