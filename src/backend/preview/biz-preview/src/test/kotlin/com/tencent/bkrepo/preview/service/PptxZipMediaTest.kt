package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.stream.Range
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@DisplayName("PPTX ZIP 媒体索引")
class PptxZipMediaTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    fun `normalizePartPath only allows ppt media audio video files`() {
        assertEquals("ppt/media/media1.mp4", PptxZipMedia.normalizePartPath("ppt/media/media1.mp4"))
        assertEquals("ppt/media/media1.mp4", PptxZipMedia.normalizePartPath("/ppt/media/media1.mp4"))
        assertNull(PptxZipMedia.normalizePartPath("ppt/media/../slides/slide1.xml"))
        assertNull(PptxZipMedia.normalizePartPath("ppt/slides/slide1.xml"))
        assertNull(PptxZipMedia.normalizePartPath("ppt/media/image1.png"))
        assertNull(PptxZipMedia.normalizePartPath("ppt/media/foo\u0000.mp4"))
    }

    @Test
    fun `rebuildSlim replaces video payload and keeps slides`() {
        val zip = storedZip(
            mapOf(
                "ppt/slides/slide1.xml" to "<p:sld/>".toByteArray(),
                "ppt/media/media1.mp4" to ByteArray(64) { 7 }
            )
        )
        val entries = PptxZipMedia.readEntries(zip)
        assertTrue(PptxZipMedia.hasStreamableMedia(entries))
        val slim = PptxZipMedia.rebuildSlim(zip, entries)
        assertNotNull(slim)
        val files = readStored(slim!!)
        assertEquals("<p:sld/>", files.getValue("ppt/slides/slide1.xml").decodeToString())
        assertEquals(0, files.getValue("ppt/media/media1.mp4").size)
    }

    @Test
    fun `rebuildSlim keeps deflate entries that use signed data descriptors`() {
        val zip = deflateZip(
            mapOf(
                "ppt/slides/slide1.xml" to "<p:sld/>".toByteArray(),
                "ppt/media/media1.mp4" to ByteArray(64) { 7 }
            )
        )
        val entries = PptxZipMedia.readEntries(zip)
        val slide = requireNotNull(PptxZipMedia.findEntry(entries, "ppt/slides/slide1.xml"))
        assertTrue(slide.flags and 0x0008 != 0)
        val localStart = slide.localOffset.toInt()
        val header = zip.copyOfRange(localStart, localStart + PptxZipMedia.LOCAL_HEADER_LEN)
        val headerLen = PptxZipMedia.localHeaderLength(header)
        val recordLen = PptxZipMedia.localRecordLength(zip, localStart, header, slide)
        assertEquals(headerLen + slide.compressedSize + 16, recordLen)

        val slim = requireNotNull(PptxZipMedia.rebuildSlim(zip, entries))
        val files = readStored(slim)
        assertEquals("<p:sld/>", files.getValue("ppt/slides/slide1.xml").decodeToString())
        assertEquals(0, files.getValue("ppt/media/media1.mp4").size)
    }

    @Test
    fun `rebuildSlim is null when there is no embedded av`() {
        val zip = storedZip(mapOf("ppt/slides/slide1.xml" to "<p:sld/>".toByteArray()))
        val entries = PptxZipMedia.readEntries(zip)
        assertFalse(PptxZipMedia.hasStreamableMedia(entries))
        assertNull(PptxZipMedia.rebuildSlim(zip, entries))
    }

    @Test
    fun `readEntries can parse from tail buffer`() {
        val zip = storedZip(
            mapOf(
                "ppt/slides/slide1.xml" to ByteArray(2048) { 1 },
                "ppt/media/media1.mp4" to ByteArray(4096) { 2 }
            )
        )
        val directory = PptxZipMedia.locateCentralDirectory(zip, zip.size.toLong(), 0)
        val tailStart = directory.cdOffset
        val tail = zip.copyOfRange(tailStart.toInt(), zip.size)
        val entries = PptxZipMedia.readEntries(tail, zip.size.toLong(), tailStart)
        assertNotNull(PptxZipMedia.findEntry(entries, "ppt/media/media1.mp4"))
        assertEquals(4096, PptxZipMedia.findEntry(entries, "ppt/media/media1.mp4")?.uncompressedSize)
    }

    @Test
    fun `mapStoredMediaRange maps http range onto zip payload`() {
        val payload = ByteArray(32) { 9 }
        val zip = storedZip(mapOf("ppt/media/media1.mp4" to payload))
        val entry = requireNotNull(PptxZipMedia.findEntry(PptxZipMedia.readEntries(zip), "ppt/media/media1.mp4"))
        val headerStart = entry.localOffset.toInt()
        val header = zip.copyOfRange(headerStart, headerStart + PptxZipMedia.LOCAL_HEADER_LEN)
        val mediaRange = Range(4, 11, payload.size.toLong())
        val storage = PptxZipMedia.mapStoredMediaRange(entry, header, mediaRange, zip.size.toLong())
        assertEquals(8, storage.length)
        val extracted = zip.copyOfRange(storage.start.toInt(), storage.end.toInt() + 1)
        assertTrue(extracted.contentEquals(payload.copyOfRange(4, 12)))
    }

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

    private fun deflateZip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readStored(bytes: ByteArray): Map<String, ByteArray> {
        val file = workspace.resolve("slim.pptx")
        Files.write(file, bytes)
        ZipFile(file.toFile()).use { zip ->
            return zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).readBytes()
            }
        }
    }
}
