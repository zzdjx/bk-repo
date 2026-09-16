package com.tencent.bkrepo.preview.service

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("Office ZIP 反斜杠条目规范化")
class OfficeZipNormalizerTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    fun `detects backslash directory entries and rewrites to forward slashes`() {
        val source = workspace.resolve("tencent.pptx").toFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ppt\\slides/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write("<p:presentation/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zip.write("<p:sld/>".toByteArray())
            zip.closeEntry()
        }
        val originalCompressedSize = compressedSize(source, "ppt/presentation.xml")

        assertTrue(OfficeZipNormalizer.hasBackslashEntryNames(source))

        val normalized = workspace.resolve("normalized.pptx").toFile()
        OfficeZipNormalizer.rewriteForwardSlashes(source, normalized)

        assertFalse(OfficeZipNormalizer.hasBackslashEntryNames(normalized))
        assertEquals(source.length(), normalized.length())
        assertEquals(originalCompressedSize, compressedSize(normalized, "ppt/presentation.xml"))
        ZipFile(normalized).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("ppt/slides/" in names)
            assertTrue("ppt/presentation.xml" in names)
            assertTrue("ppt/slides/slide1.xml" in names)
            assertFalse(names.any { it.contains('\\') })
            assertEquals(
                "<p:presentation/>",
                zip.getInputStream(zip.getEntry("ppt/presentation.xml")).readBytes().decodeToString()
            )
        }
    }

    @Test
    fun `forward-slash zip is left undetected`() {
        val source = workspace.resolve("normal.pptx").toFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write("<p:presentation/>".toByteArray())
            zip.closeEntry()
        }

        assertFalse(OfficeZipNormalizer.hasBackslashEntryNames(source))
    }

    @Test
    fun `detects backslash entry after data descriptor payload`() {
        val source = workspace.resolve("descriptor.pptx").toFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write("<p:presentation/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ppt\\slides/"))
            zip.closeEntry()
        }

        assertTrue(OfficeZipNormalizer.hasBackslashEntryNames(source))

        val normalized = workspace.resolve("descriptor-normalized.pptx").toFile()
        OfficeZipNormalizer.rewriteForwardSlashes(source, normalized)
        assertFalse(OfficeZipNormalizer.hasBackslashEntryNames(normalized))
        assertEquals(source.length(), normalized.length())
    }

    @Test
    fun `detects and rewrites backslash names when zip has a comment`() {
        val source = workspace.resolve("comment.pptx").toFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.setComment("preview")
            zip.putNextEntry(ZipEntry("ppt\\slides/"))
            zip.closeEntry()
        }

        assertTrue(OfficeZipNormalizer.hasBackslashEntryNames(source))
        val normalized = workspace.resolve("comment-normalized.pptx").toFile()
        OfficeZipNormalizer.rewriteForwardSlashes(source, normalized)
        assertFalse(OfficeZipNormalizer.hasBackslashEntryNames(normalized))
    }

    @Test
    fun `unreadable zip is treated as no backslash entries`() {
        val source = workspace.resolve("invalid.pptx").toFile()
        source.writeBytes("not a zip".toByteArray())

        assertFalse(OfficeZipNormalizer.hasBackslashEntryNames(source))
    }

    @Test
    fun `zip64 eocd sentinel is treated as no backslash entries`() {
        val source = workspace.resolve("zip64.pptx").toFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write("<p:presentation/>".toByteArray())
            zip.closeEntry()
        }
        patchEocdZip64Sentinels(source)

        assertFalse(OfficeZipNormalizer.hasBackslashEntryNames(source))
    }

    @Test
    fun `stale unicode path extra crc is not revived`() {
        val source = workspace.resolve("stale-unicode.pptx").toFile()
        val headerName = "ppt/presentation.xml".toByteArray()
        val staleName = "old\\name.xml".toByteArray()
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ppt\\slides/"))
            zip.closeEntry()
            val entry = ZipEntry("ppt/presentation.xml")
            entry.extra = unicodePathExtra(unicodeName = staleName, crcOf = staleName)
            zip.putNextEntry(entry)
            zip.write("<p:presentation/>".toByteArray())
            zip.closeEntry()
        }
        val originalExtra = ZipFile(source).use { it.getEntry("ppt/presentation.xml").extra }

        val normalized = workspace.resolve("stale-unicode-normalized.pptx").toFile()
        OfficeZipNormalizer.rewriteForwardSlashes(source, normalized)

        val rewrittenExtra = ZipFile(normalized).use { it.getEntry("ppt/presentation.xml").extra }
        assertTrue(originalExtra.contentEquals(rewrittenExtra))
        val unicode = requireNotNull(readUnicodePath(rewrittenExtra))
        assertNotEquals(crc32(headerName), unicode.first)
        assertEquals("old\\name.xml", unicode.second)
    }

    @Test
    fun `valid unicode path extra is updated with the rewritten name`() {
        val originalName = "ppt\\slides/".toByteArray()
        val source = workspace.resolve("unicode.pptx").toFile()
        ZipOutputStream(source.outputStream()).use { zip ->
            val entry = ZipEntry("ppt\\slides/")
            entry.extra = unicodePathExtra(unicodeName = originalName, crcOf = originalName)
            zip.putNextEntry(entry)
            zip.closeEntry()
        }

        val normalized = workspace.resolve("unicode-normalized.pptx").toFile()
        OfficeZipNormalizer.rewriteForwardSlashes(source, normalized)

        val extra = ZipFile(normalized).use { zip ->
            zip.entries().asSequence().first { it.name == "ppt/slides/" || it.name == "ppt\\slides/" }.extra
        }
        val unicode = requireNotNull(readUnicodePath(extra))
        assertEquals(crc32("ppt/slides/".toByteArray()), unicode.first)
        assertEquals("ppt/slides/", unicode.second)
    }

    private fun compressedSize(file: File, entryName: String): Long {
        return ZipFile(file).use { zip ->
            zip.getEntry(entryName).compressedSize
        }
    }

    private fun patchEocdZip64Sentinels(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val fileLength = raf.length()
            val scanLength = min(fileLength, EOCD_SIZE + MAX_ZIP_COMMENT_LENGTH).toInt()
            raf.seek(fileLength - scanLength)
            val tail = ByteArray(scanLength)
            raf.readFully(tail)
            for (index in tail.size - EOCD_SIZE.toInt() downTo 0) {
                val isEocd = tail[index] == 0x50.toByte() &&
                    tail[index + 1] == 0x4B.toByte() &&
                    tail[index + 2] == 0x05.toByte() &&
                    tail[index + 3] == 0x06.toByte()
                if (!isEocd) {
                    continue
                }
                val commentLength = (tail[index + 20].toInt() and 0xFF) or
                    ((tail[index + 21].toInt() and 0xFF) shl 8)
                if (index + EOCD_SIZE.toInt() + commentLength != tail.size) {
                    continue
                }
                raf.seek(fileLength - scanLength + index + 12)
                raf.write(ByteArray(8) { 0xFF.toByte() })
                return
            }
        }
        error("EOCD not found")
    }

    private fun unicodePathExtra(unicodeName: ByteArray, crcOf: ByteArray): ByteArray {
        val crc = crc32(crcOf)
        val dataSize = UNICODE_PATH_EXTRA_MIN_DATA + unicodeName.size
        val extra = ByteArray(4 + dataSize)
        extra[0] = 0x75
        extra[1] = 0x70
        extra[2] = (dataSize and 0xFF).toByte()
        extra[3] = ((dataSize shr 8) and 0xFF).toByte()
        extra[4] = 1
        extra[5] = (crc and 0xFF).toByte()
        extra[6] = ((crc shr 8) and 0xFF).toByte()
        extra[7] = ((crc shr 16) and 0xFF).toByte()
        extra[8] = ((crc shr 24) and 0xFF).toByte()
        unicodeName.copyInto(extra, UNICODE_PATH_EXTRA_MIN_DATA + 4)
        return extra
    }

    private fun readUnicodePath(extra: ByteArray?): Pair<Long, String>? {
        extra ?: return null
        var offset = 0
        while (offset + 4 <= extra.size) {
            val headerId = (extra[offset].toInt() and 0xFF) or ((extra[offset + 1].toInt() and 0xFF) shl 8)
            val dataSize = (extra[offset + 2].toInt() and 0xFF) or ((extra[offset + 3].toInt() and 0xFF) shl 8)
            val dataStart = offset + 4
            val dataEnd = dataStart + dataSize
            if (dataEnd > extra.size) {
                break
            }
            if (headerId == UNICODE_PATH_EXTRA_ID && dataSize >= UNICODE_PATH_EXTRA_MIN_DATA) {
                val crc = (extra[dataStart + 1].toLong() and 0xFF) or
                    ((extra[dataStart + 2].toLong() and 0xFF) shl 8) or
                    ((extra[dataStart + 3].toLong() and 0xFF) shl 16) or
                    ((extra[dataStart + 4].toLong() and 0xFF) shl 24)
                val name = extra.copyOfRange(dataStart + UNICODE_PATH_EXTRA_MIN_DATA, dataEnd).decodeToString()
                return crc to name
            }
            offset = dataEnd
        }
        return null
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    companion object {
        private const val EOCD_SIZE = 22L
        private const val MAX_ZIP_COMMENT_LENGTH = 65535L
        private const val UNICODE_PATH_EXTRA_ID = 0x7075
        private const val UNICODE_PATH_EXTRA_MIN_DATA = 5
    }
}
