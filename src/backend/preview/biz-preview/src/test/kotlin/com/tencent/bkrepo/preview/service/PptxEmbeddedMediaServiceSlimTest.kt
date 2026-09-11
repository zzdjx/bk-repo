package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResourceWriter
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PPTX 瘦包不把音视频读进堆")
class PptxEmbeddedMediaServiceSlimTest {

    private val fileTransferService = mockk<FileTransferService>(relaxed = true)
    private val artifactResourceWriter = mockk<ArtifactResourceWriter>(relaxed = true)
    private val config = mockk<PreviewConfig> {
        every { maxFileSize } returns 50
    }
    private val service = PptxEmbeddedMediaService(fileTransferService, artifactResourceWriter, config)

    @Test
    fun `streamSlimPackage does not load the whole zip or media payload`() {
        val zip = storedZip(
            mapOf(
                "ppt/slides/slide1.xml" to ByteArray(200 * 1024) { 1 },
                "ppt/media/media1.mp4" to ByteArray(1024 * 1024) { 7 }
            )
        )
        val ranges = mutableListOf<Range>()
        every { fileTransferService.originalFileSize(any()) } returns zip.size.toLong()
        every { fileTransferService.loadOriginalRange(any(), any()) } answers {
            val range = invocation.args[1] as Range
            ranges.add(range)
            slice(zip, range)
        }

        try {
            service.streamSlimPackage(localPptx())
        } catch (_: Exception) {
            // writeStream 依赖 HTTP 请求上下文
        }

        verify(exactly = 0) { fileTransferService.sendOriginalFileAsResponse(any()) }
        assertTrue(ranges.isNotEmpty())
        assertTrue(ranges.none { it.start == 0L && it.length == zip.size.toLong() })
        assertTrue(ranges.none { it.length >= 1024 * 1024 })
    }

    @Test
    fun `streamSlimPackage without media streams original`() {
        val zip = storedZip(mapOf("ppt/slides/slide1.xml" to "<p:sld/>".toByteArray()))
        every { fileTransferService.originalFileSize(any()) } returns zip.size.toLong()
        every { fileTransferService.loadOriginalRange(any(), any()) } answers {
            slice(zip, invocation.args[1] as Range)
        }

        service.streamSlimPackage(localPptx())

        verify(exactly = 1) { fileTransferService.sendOriginalFileAsResponse(any()) }
    }

    @Test
    fun `streamPart indexes zip from a 64KiB tail`() {
        val zip = storedZip(
            mapOf(
                "ppt/slides/slide1.xml" to ByteArray(200 * 1024) { 1 },
                "ppt/media/media1.mp4" to ByteArray(32) { 2 }
            )
        )
        val ranges = mutableListOf<Range>()
        every { fileTransferService.originalFileSize(any()) } returns zip.size.toLong()
        every { fileTransferService.loadOriginalRange(any(), any()) } answers {
            val range = invocation.args[1] as Range
            ranges.add(range)
            slice(zip, range)
        }

        try {
            service.streamPart(localPptx().copy(embeddedPartPath = "ppt/media/media1.mp4"))
        } catch (_: Exception) {
            // writeStream 依赖 HTTP 请求上下文
        }

        assertTrue(ranges.isNotEmpty())
        val tail = ranges[0]
        assertEquals(PptxEmbeddedMediaService.ZIP_TAIL_BYTES, tail.length)
        assertEquals(zip.size.toLong() - PptxEmbeddedMediaService.ZIP_TAIL_BYTES, tail.start)
        assertTrue(ranges.none { it.length == zip.size.toLong() })
    }

    @Test
    fun `raw inflater stream can be closed after inflate`() {
        val payload = ByteArray(4096) { it.toByte() }
        val stream = service.openRawInflaterStream(ByteArrayInputStream(rawDeflate(payload)))
        val restored = stream.use { it.readAllBytes() }
        assertTrue(payload.contentEquals(restored))
        stream.close()
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

    private fun storedZip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            files.forEach { (name, data) ->
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                val crc = CRC32()
                crc.update(data)
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun slice(zip: ByteArray, range: Range): ArtifactInputStream {
        val from = range.start.toInt()
        val to = (range.end + 1).toInt()
        return ArtifactInputStream(ByteArrayInputStream(zip.copyOfRange(from, to)), range)
    }

    private fun rawDeflate(payload: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(payload)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n > 0) {
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
