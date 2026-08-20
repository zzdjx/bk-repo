package com.tencent.bkrepo.fs.service.drive.thumbnail

import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ArtifactThumbnailGenerateService
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.DriveArtifactThumbnailBinder
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ThumbnailGenerateResult
import com.tencent.bkrepo.fs.server.storage.CoArtifactFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("上传后作品缩略图绑定")
class DriveArtifactThumbnailBinderTest {

    @TempDir
    lateinit var tempDir: Path

    private val generateService: ArtifactThumbnailGenerateService = mock()
    private val properties = DriveThumbnailProperties().apply { enabled = true }
    private val binder = DriveArtifactThumbnailBinder(generateService, properties)

    @Test
    fun `does nothing without IMATE_ARTIFACT_NAME`() = runBlocking<Unit> {
        assertNull(binder.render("/a.png", emptyList(), sourceFile()))
        verify(generateService, never()).generate(any(), any(), any())
    }

    @Test
    fun `does nothing for sidecar files`() = runBlocking<Unit> {
        val metadata = listOf(TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = "cover"))
        assertNull(binder.render("/.a.png.thumb.jpg", metadata, sourceFile()))
        verify(generateService, never()).generate(any(), any(), any())
    }

    @Test
    fun `resolves artifact type from case-insensitive metadata key`() = runBlocking<Unit> {
        whenever(generateService.generate(any(), any(), any())).thenReturn(ThumbnailGenerateResult.Success)
        val metadata = listOf(
            TMetadata(key = "imate_artifact_name", value = "封面"),
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_TYPE, value = "image"),
        )
        val jpeg = binder.render("/docs/hero.png", metadata, sourceFile())
        try {
            assertTrue(jpeg != null && Files.exists(jpeg))
        } finally {
            jpeg?.let { Files.deleteIfExists(it) }
        }
        verify(generateService).generate(any(), eq("image"), any())
    }

    @Test
    fun `render keeps jpeg until caller deletes it`() = runBlocking {
        whenever(generateService.generate(any(), any(), any())).thenReturn(ThumbnailGenerateResult.Success)
        val metadata = listOf(
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = "封面"),
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_TYPE, value = "image"),
        )
        val jpeg = binder.render("/docs/hero.png", metadata, sourceFile())
        try {
            assertTrue(jpeg != null && Files.exists(jpeg))
        } finally {
            jpeg?.let { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `returns null when generate is skipped`() = runBlocking {
        whenever(generateService.generate(any(), any(), any()))
            .thenReturn(ThumbnailGenerateResult.Skipped("skip type[code]"))
        val metadata = listOf(TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = "script"))
        assertNull(binder.render("/a.py", metadata, sourceFile()))
    }

    @Test
    fun `copies in-memory artifact stream to temp file before generate`() = runBlocking {
        val captured = mutableListOf<Path>()
        whenever(generateService.generate(any(), any(), any())).thenAnswer { invocation ->
            val input = invocation.getArgument<Path>(0)
            captured.add(input)
            assertTrue(Files.exists(input))
            assertEquals(SOURCE_BYTES.size.toLong(), Files.size(input))
            ThumbnailGenerateResult.Success
        }
        val artifactFile = mock<CoArtifactFile>()
        whenever(artifactFile.getFile()).thenReturn(null)
        whenever(artifactFile.getInputStream()).thenReturn(SOURCE_BYTES.inputStream())
        val metadata = listOf(
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = "封面"),
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_TYPE, value = "image"),
        )
        binder.render("/docs/hero.png", metadata, artifactFile)?.let { Files.deleteIfExists(it) }
        assertEquals(1, captured.size)
        assertTrue(captured.single().fileName.toString().endsWith(".png"))
        assertTrue(captured.none { Files.exists(it) })
    }

    private fun sourceFile(): CoArtifactFile {
        val file = tempDir.resolve("src.bin")
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        val artifactFile = mock<CoArtifactFile>()
        whenever(artifactFile.getFile()).thenReturn(file.toFile())
        return artifactFile
    }

    companion object {
        private val SOURCE_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
