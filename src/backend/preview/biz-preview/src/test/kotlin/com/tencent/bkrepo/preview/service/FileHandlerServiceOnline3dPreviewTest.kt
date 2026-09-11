package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ONLINE3D 预览模板与体积阈值")
class FileHandlerServiceOnline3dPreviewTest {

    private val fileTransferService = mockk<FileTransferService>()
    private val service = FileHandlerService(
        PreviewConfig(),
        mockk<CommonResourceService>(relaxed = true),
        fileTransferService
    )

    @Test
    fun `glb uses online3D template`() {
        val template = service.getFileTemplate(
            FileAttribute(suffix = "glb", type = FileType.ONLINE3D)
        )
        assertEquals(FilePreview.ONLINE3D_PREVIEW_PAGE, template)
    }

    @Test
    fun `gltf stays unsupported`() {
        val template = service.getFileTemplate(
            FileAttribute(suffix = "gltf", type = FileType.ONLINE3D)
        )
        assertEquals(FilePreview.NOT_SUPPORTED_FILE_PAGE, template)
    }

    @Test
    fun `stl stays unsupported`() {
        val template = service.getFileTemplate(
            FileAttribute(suffix = "stl", type = FileType.ONLINE3D)
        )
        assertEquals(FilePreview.NOT_SUPPORTED_FILE_PAGE, template)
    }

    @Test
    fun `remote glb is not online3D template`() {
        val template = service.getFileTemplate(
            FileAttribute(suffix = "glb", type = FileType.ONLINE3D, storageType = 1)
        )
        assertEquals(FilePreview.NOT_SUPPORTED_FILE_PAGE, template)
    }

    @Test
    fun `preview info exposes online3d size limits and source size for glb`() {
        every { fileTransferService.originalFileSize(any()) } returns 12_345L
        val previewInfo = service.buildFilePreviewInfo(
            ArtifactInfo("p1", "generic-local", "/models/hero.glb"),
            """{"fileName":"hero.glb"}"""
        )
        assertEquals(FilePreview.ONLINE3D_PREVIEW_PAGE, previewInfo.fileTemplate)
        assertEquals(FileType.ONLINE3D.name, previewInfo.fileType)
        assertEquals("glb", previewInfo.suffix)
        assertEquals(50L, previewInfo.warnFileSize)
        assertEquals(300L, previewInfo.maxFileSize)
        assertEquals(12_345L, previewInfo.size)
    }

    @Test
    fun `preview info omits online3d volume fields for unsupported suffix`() {
        val previewInfo = service.buildFilePreviewInfo(
            ArtifactInfo("p1", "generic-local", "/models/mesh.gltf"),
            """{"fileName":"mesh.gltf"}"""
        )
        assertEquals(FilePreview.NOT_SUPPORTED_FILE_PAGE, previewInfo.fileTemplate)
        assertNull(previewInfo.size)
        assertNull(previewInfo.warnFileSize)
        assertNull(previewInfo.maxFileSize)
    }
}
