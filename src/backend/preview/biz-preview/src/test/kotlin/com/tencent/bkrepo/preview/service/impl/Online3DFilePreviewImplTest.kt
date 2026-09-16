package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewSystemException
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import com.tencent.bkrepo.preview.service.FileTransferService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ONLINE3D 文件预览")
class Online3DFilePreviewImplTest {

    private val fileTransferService = mockk<FileTransferService>(relaxed = true)
    private val config = mockk<PreviewConfig>()
    private val preview = Online3DFilePreviewImpl(fileTransferService, config)

    @Test
    fun `remote online3d preview is not supported`() {
        every { config.online3dMaxFileSize } returns 300
        val fileAttribute = glbAttribute(storageType = 1)

        val exception = assertThrows(PreviewSystemException::class.java) {
            preview.filePreviewHandle(fileAttribute)
        }
        assertEquals(PreviewMessageCode.PREVIEW_FILE_NOT_SUPPORT_ERROR, exception.messageCode)
        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
    }

    @Test
    fun `uppercase glb suffix streams original artifact`() {
        every { config.online3dMaxFileSize } returns 0
        val fileAttribute = glbAttribute().copy(fileName = "hero.GLB", suffix = "GLB")

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
    }

    @Test
    fun `non glb online3d suffix is not supported`() {
        every { config.online3dMaxFileSize } returns 300
        val fileAttribute = glbAttribute().copy(fileName = "mesh.gltf", suffix = "gltf")

        val exception = assertThrows(PreviewSystemException::class.java) {
            preview.filePreviewHandle(fileAttribute)
        }
        assertEquals(PreviewMessageCode.PREVIEW_FILE_NOT_SUPPORT_ERROR, exception.messageCode)
        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
    }

    @Test
    fun `local glb preview streams original artifact`() {
        every { config.online3dMaxFileSize } returns 300
        every { fileTransferService.originalFileSize(any()) } returns 12L * 1024 * 1024
        val fileAttribute = glbAttribute()

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
        verify(exactly = 0) { fileTransferService.download(any()) }
        verify(exactly = 0) { fileTransferService.sendFileAsResponse(any(), any()) }
    }

    @Test
    fun `glb larger than max file size is rejected`() {
        every { config.online3dMaxFileSize } returns 300
        every { fileTransferService.originalFileSize(any()) } returns 300L * 1024 * 1024 + 1
        val fileAttribute = glbAttribute()

        val exception = assertThrows(PreviewSystemException::class.java) {
            preview.filePreviewHandle(fileAttribute)
        }
        assertEquals(PreviewMessageCode.PREVIEW_FILE_SIZE_LIMIT_ERROR, exception.messageCode)
        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
    }

    @Test
    fun `glb equal to max file size is allowed`() {
        every { config.online3dMaxFileSize } returns 300
        every { fileTransferService.originalFileSize(any()) } returns 300L * 1024 * 1024
        val fileAttribute = glbAttribute()

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
    }

    @Test
    fun `non positive max file size disables hard limit`() {
        every { config.online3dMaxFileSize } returns 0
        val fileAttribute = glbAttribute()

        preview.filePreviewHandle(fileAttribute)

        verify(exactly = 0) { fileTransferService.originalFileSize(any()) }
        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(fileAttribute) }
    }

    private fun glbAttribute(storageType: Int = 0) = FileAttribute(
        storageType = storageType,
        projectId = "p1",
        repoName = "generic-local",
        artifactUri = "/models/hero.glb",
        url = "https://example.com/hero.glb",
        fileName = "hero.glb",
        suffix = "glb",
        type = FileType.ONLINE3D
    )
}
