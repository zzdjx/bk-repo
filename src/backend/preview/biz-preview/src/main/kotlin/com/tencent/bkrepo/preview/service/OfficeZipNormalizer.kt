package com.tencent.bkrepo.preview.service

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.min

/**
 * Tencent office 等 Windows 生成器会把 ZIP 目录条目写成 `ppt\slides/`。
 * LibreOffice 按 ZIP/OPC 规范只接受正斜杠，遇到反斜杠会立刻 loadDocument 失败。
 *
 * `\` 与 `/` 都是单字节，改名不改变字段长度，因此只原地改 Local Header / Central Directory
 *（以及可选的 Info-ZIP Unicode Path extra）里的文件名，不解压、不重压缩。
 */
internal object OfficeZipNormalizer {
    private const val LOCAL_HEADER_SIZE = 30
    private const val CEN_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22
    private const val MAX_ZIP_COMMENT_LENGTH = 65535
    private const val ZIP64_SENTINEL = 0xFFFFFFFFL
    private const val UNICODE_PATH_EXTRA_ID = 0x7075
    private const val UNICODE_PATH_EXTRA_MIN_DATA = 5
    private const val BACKSLASH: Byte = 0x5C
    private const val FORWARD_SLASH: Byte = 0x2F

    fun hasBackslashEntryNames(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val centralDirectory = locateCentralDirectory(raf) ?: return false
                scanCentralDirectory(raf, centralDirectory)
            }
        } catch (_: IOException) {
            false
        }
    }

    fun rewriteForwardSlashes(source: File, destination: File) {
        try {
            source.copyTo(destination, overwrite = true)
            RandomAccessFile(destination, "rw").use { raf ->
                patchEntryNames(raf)
            }
        } catch (e: IOException) {
            destination.delete()
            throw e
        }
    }

    private fun patchEntryNames(raf: RandomAccessFile) {
        val directory = locateCentralDirectory(raf)
            ?: throw IOException("Failed to locate ZIP central directory")
        raf.seek(directory.offset)
        var remaining = directory.size
        while (remaining >= CEN_HEADER_SIZE) {
            val header = ByteArray(CEN_HEADER_SIZE)
            raf.readFully(header)
            if (!isSignature(header, 0, 0x01, 0x02)) {
                throw IOException("Invalid ZIP central directory signature")
            }
            val fileNameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            val localHeaderOffset = u32(header, 42)
            if (localHeaderOffset == ZIP64_SENTINEL) {
                throw IOException("ZIP64 office zip is not supported for name rewrite")
            }
            val cdNameOffset = raf.filePointer
            val nameBytes = ByteArray(fileNameLength)
            raf.readFully(nameBytes)
            val extraOffset = raf.filePointer
            val newName = replaceBackslash(nameBytes)
            if (!nameBytes.contentEquals(newName)) {
                raf.seek(cdNameOffset)
                raf.write(newName)
                patchUnicodePathExtra(raf, extraOffset, extraLength, nameBytes, newName)
                patchLocalHeader(raf, localHeaderOffset)
            }
            raf.seek(extraOffset + extraLength + commentLength)
            remaining -= (CEN_HEADER_SIZE + fileNameLength + extraLength + commentLength).toLong()
        }
    }

    private fun patchLocalHeader(raf: RandomAccessFile, localHeaderOffset: Long) {
        if (localHeaderOffset + LOCAL_HEADER_SIZE > raf.length()) {
            throw IOException("ZIP local file header offset is out of range")
        }
        raf.seek(localHeaderOffset)
        val header = ByteArray(LOCAL_HEADER_SIZE)
        raf.readFully(header)
        if (!isSignature(header, 0, 0x03, 0x04)) {
            throw IOException("Invalid ZIP local file header")
        }
        val fileNameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        val nameOffset = localHeaderOffset + LOCAL_HEADER_SIZE
        val nameBytes = ByteArray(fileNameLength)
        raf.seek(nameOffset)
        raf.readFully(nameBytes)
        val newName = replaceBackslash(nameBytes)
        if (nameBytes.contentEquals(newName)) {
            return
        }
        raf.seek(nameOffset)
        raf.write(newName)
        patchUnicodePathExtra(raf, nameOffset + fileNameLength, extraLength, nameBytes, newName)
    }

    private fun patchUnicodePathExtra(
        raf: RandomAccessFile,
        extraOffset: Long,
        extraLength: Int,
        originalNameBytes: ByteArray,
        newNameBytes: ByteArray
    ) {
        if (extraLength < 4) {
            return
        }
        raf.seek(extraOffset)
        val extra = ByteArray(extraLength)
        raf.readFully(extra)
        if (!rewriteUnicodePathExtra(extra, originalNameBytes, newNameBytes)) {
            return
        }
        raf.seek(extraOffset)
        raf.write(extra)
    }

    private fun rewriteUnicodePathExtra(
        extra: ByteArray,
        originalNameBytes: ByteArray,
        newNameBytes: ByteArray
    ): Boolean {
        var offset = 0
        var changed = false
        while (offset + 4 <= extra.size) {
            val dataSize = u16(extra, offset + 2)
            val dataStart = offset + 4
            val dataEnd = dataStart + dataSize
            if (dataEnd > extra.size) {
                break
            }
            changed = patchUnicodePathField(
                extra, offset, dataStart, dataEnd, dataSize, originalNameBytes, newNameBytes
            ) || changed
            offset = dataEnd
        }
        return changed
    }

    private fun patchUnicodePathField(
        extra: ByteArray,
        fieldOffset: Int,
        dataStart: Int,
        dataEnd: Int,
        dataSize: Int,
        originalNameBytes: ByteArray,
        newNameBytes: ByteArray
    ): Boolean {
        val headerId = u16(extra, fieldOffset)
        if (headerId != UNICODE_PATH_EXTRA_ID || dataSize < UNICODE_PATH_EXTRA_MIN_DATA) {
            return false
        }
        val storedCrc = u32(extra, dataStart + 1)
        if (storedCrc != crc32(originalNameBytes)) {
            return false
        }
        var changed = false
        val newCrc = crc32(newNameBytes)
        if (storedCrc != newCrc) {
            putU32(extra, dataStart + 1, newCrc)
            changed = true
        }
        val nameStart = dataStart + UNICODE_PATH_EXTRA_MIN_DATA
        val nameChanged = replaceBackslashInRange(extra, nameStart, dataEnd)
        return changed || nameChanged
    }

    private fun locateCentralDirectory(raf: RandomAccessFile): CentralDirectory? {
        val fileLength = raf.length()
        if (fileLength < EOCD_SIZE) {
            return null
        }
        val scanLength = min(fileLength, EOCD_SIZE.toLong() + MAX_ZIP_COMMENT_LENGTH).toInt()
        val tail = ByteArray(scanLength)
        raf.seek(fileLength - scanLength)
        raf.readFully(tail)
        for (index in tail.size - EOCD_SIZE downTo 0) {
            parseEocd(tail, index)?.let { return it }
        }
        return null
    }

    private fun parseEocd(tail: ByteArray, index: Int): CentralDirectory? {
        if (!isSignature(tail, index, 0x05, 0x06)) {
            return null
        }
        val commentLength = u16(tail, index + 20)
        if (index + EOCD_SIZE + commentLength != tail.size) {
            return null
        }
        val cenSize = u32(tail, index + 12)
        val cenOffset = u32(tail, index + 16)
        if (cenSize == ZIP64_SENTINEL || cenOffset == ZIP64_SENTINEL) {
            return null
        }
        return CentralDirectory(offset = cenOffset, size = cenSize)
    }

    private fun scanCentralDirectory(raf: RandomAccessFile, directory: CentralDirectory): Boolean {
        raf.seek(directory.offset)
        var remaining = directory.size
        while (remaining >= CEN_HEADER_SIZE) {
            val header = ByteArray(CEN_HEADER_SIZE)
            raf.readFully(header)
            if (!isSignature(header, 0, 0x01, 0x02)) {
                return false
            }
            val fileNameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            val nameBytes = ByteArray(fileNameLength)
            raf.readFully(nameBytes)
            if (nameBytes.any { it == BACKSLASH }) {
                return true
            }
            val skipLength = extraLength + commentLength
            raf.seek(raf.filePointer + skipLength)
            remaining -= (CEN_HEADER_SIZE + fileNameLength + skipLength).toLong()
        }
        return false
    }

    private fun replaceBackslash(nameBytes: ByteArray): ByteArray {
        if (nameBytes.none { it == BACKSLASH }) {
            return nameBytes
        }
        val copy = nameBytes.copyOf()
        replaceBackslashInRange(copy, 0, copy.size)
        return copy
    }

    private fun replaceBackslashInRange(bytes: ByteArray, start: Int, end: Int): Boolean {
        var changed = false
        for (index in start until end) {
            if (bytes[index] != BACKSLASH) {
                continue
            }
            bytes[index] = FORWARD_SLASH
            changed = true
        }
        return changed
    }

    private fun isSignature(bytes: ByteArray, offset: Int, third: Int, fourth: Int): Boolean {
        return bytes[offset] == 0x50.toByte() &&
            bytes[offset + 1] == 0x4B.toByte() &&
            bytes[offset + 2] == third.toByte() &&
            bytes[offset + 3] == fourth.toByte()
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        return u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private data class CentralDirectory(
        val offset: Long,
        val size: Long
    )
}
