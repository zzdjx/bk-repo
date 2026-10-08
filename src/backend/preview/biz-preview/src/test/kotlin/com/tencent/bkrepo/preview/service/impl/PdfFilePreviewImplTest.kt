package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.PdfPreviewArtifactNameSource
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
        val preview = preview(null)

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
        verify(exactly = 0) { fileTransferService.originalFileSize(any()) }
        verify(exactly = 0) { fileTransferService.sendAppendedBytes(any(), any(), any()) }
    }

    @Test
    fun `local pdf appends title and keeps the body ranged`() {
        val original = pdf(info = "草稿")
        every { fileTransferService.originalFileSize(fileAttribute) } returns original.size.toLong()
        every { fileTransferService.readAt(fileAttribute, any(), any(), any()) } answers {
            val start = (invocation.args[1] as Long).toInt()
            val length = invocation.args[2] as Int
            original.copyOfRange(start, (start + length).coerceAtMost(original.size))
        }
        val suffix = slot<ByteArray>()
        every {
            fileTransferService.sendAppendedBytes(
                fileAttribute,
                original.size.toLong(),
                capture(suffix)
            )
        } returns Unit
        val preview = preview("周报")

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
        PDDocument.load(original + suffix.captured).use { document ->
            assertEquals("周报.pdf", document.documentInformation.title)
        }
    }

    private fun preview(artifactName: String?): PdfFilePreviewImpl {
        val artifactNameSource = mockk<PdfPreviewArtifactNameSource>()
        every { artifactNameSource.read(any()) } returns artifactName
        return PdfFilePreviewImpl(
            PreviewConfig(),
            fileTransferService,
            mockk<PreviewFileCacheServiceImpl>(),
            mockk<NodeService>(),
            artifactNameSource,
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
