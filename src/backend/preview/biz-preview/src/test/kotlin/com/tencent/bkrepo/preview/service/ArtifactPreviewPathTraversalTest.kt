package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import com.tencent.bkrepo.preview.utils.DownloadUtils
import com.tencent.bkrepo.preview.utils.HttpUtils
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("制品预览 fileName 路径穿越防护")
class ArtifactPreviewPathTraversalTest {

    @TempDir
    lateinit var workspace: Path

    @ParameterizedTest
    @ValueSource(
        strings = [
            "../../../outside/pwned.sh",
            "..\\..\\..\\outside\\pwned.sh",
            "/tmp/pwned.sh",
            "%2e%2e%2fpwned.sh"
        ]
    )
    fun `extraParam fileName with path traversal is rejected`(fileName: String) {
        val previewRoot = workspace.resolve("preview").toFile().apply { mkdirs() }
        val outsideFile = workspace.resolve("outside").resolve("pwned.sh").toFile()
        val extraParam = """{"fileName":"${fileName.replace("\\", "\\\\")}"}"""
        val fileHandlerService = newFileHandlerService(previewRoot)

        val exception = assertThrows(PreviewInvalidException::class.java) {
            fileHandlerService.getFileAttribute(artifactInfo(), extraParam)
        }

        assertEquals(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, exception.messageCode)
        assertFalse(outsideFile.exists())
    }

    @Test
    fun `getRelFilePath rejects traversal fileName and does not write outside preview dir`() {
        val previewRoot = workspace.resolve("preview").toFile().apply { mkdirs() }
        val outsideFile = workspace.resolve("outside").resolve("pwned.sh").toFile()
        val downloadUtils = DownloadUtils(mockk<HttpUtils>(), mockk())

        assertThrows(PreviewInvalidException::class.java) {
            downloadUtils.getRelFilePath("../../../outside/pwned.sh", "sh", previewRoot.absolutePath)
        }
        assertFalse(outsideFile.exists())
    }

    @Test
    fun `legal extraParam fileName stays inside preview dir`() {
        val previewRoot = workspace.resolve("preview").toFile().apply { mkdirs() }
        val extraParam = """{"fileName":"readme.txt"}"""
        val fileHandlerService = newFileHandlerService(previewRoot)
        val downloadUtils = DownloadUtils(mockk<HttpUtils>(), mockk())

        val fileAttribute = fileHandlerService.getFileAttribute(artifactInfo(), extraParam)
        val tmpPath = downloadUtils.getRelFilePath(
            fileAttribute.fileName,
            fileAttribute.suffix.orEmpty(),
            previewRoot.absolutePath
        )
        writeLikeArtifactPreview(tmpPath, PAYLOAD)
        writeLikeArtifactPreview(fileAttribute.outFilePath.orEmpty(), PAYLOAD)

        assertEquals("readme.txt", fileAttribute.fileName)
        assertTrue(File(tmpPath).isFile)
        assertTrue(isInsidePreviewRoot(previewRoot, File(tmpPath)))
        assertTrue(isInsidePreviewRoot(previewRoot, File(fileAttribute.outFilePath.orEmpty())))
    }

    private fun newFileHandlerService(previewRoot: File): FileHandlerService {
        val config = mockk<PreviewConfig>()
        every { config.fileDir } returns previewRoot.absolutePath
        return FileHandlerService(config, mockk<CommonResourceService>(), mockk())
    }

    private fun artifactInfo(): ArtifactInfo =
        ArtifactInfo("demo-project", "generic-local", "/docs/readme.txt")

    private fun writeLikeArtifactPreview(filePath: String, content: String) {
        val file = File(filePath)
        file.parentFile?.let { parentDir ->
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
        }
        file.writeText(content)
    }

    private fun isInsidePreviewRoot(previewRoot: File, target: File): Boolean {
        val rootPath = previewRoot.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        return targetPath.startsWith(rootPath)
    }

    companion object {
        private const val PAYLOAD = "preview-safe-write"
    }
}
