package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.cache.impl.PreviewFileCacheServiceImpl
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PDF 文件预览")
class PdfFilePreviewImplTest {

    private val fileTransferService = mockk<FileTransferService>(relaxed = true)
    private val preview = PdfFilePreviewImpl(
        PreviewConfig(),
        fileTransferService,
        mockk<PreviewFileCacheServiceImpl>(),
        mockk<NodeService>()
    )

    @Test
    fun `local pdf preview streams original artifact`() {
        val fileAttribute = FileAttribute(
            storageType = 0,
            projectId = "p1",
            repoName = "drive-local",
            artifactUri = "/works/demo.pdf",
            fileName = "demo.pdf",
            suffix = "pdf",
            type = FileType.PDF,
            size = 80L * 1024 * 1024
        )

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
        verify(exactly = 0) { fileTransferService.download(any()) }
        verify(exactly = 0) { fileTransferService.sendFileAsResponse(any(), any()) }
    }
}
