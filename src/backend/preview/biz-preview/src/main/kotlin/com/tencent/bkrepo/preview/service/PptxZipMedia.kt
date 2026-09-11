package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.stream.Range
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * OOXML pptx 是 ZIP。预览只把幻灯片 XML/图片读进内存，音视频走独立 Range 流。
 */
internal object PptxZipMedia {
    const val STORED_METHOD = 0
    const val DEFLATE_METHOD = 8
    private const val SIG_LOCAL = 0x04034b50
    private const val SIG_CENTRAL = 0x02014b50
    private const val SIG_EOCD = 0x06054b50
    private const val SIG_ZIP64_EOCD = 0x06064b50
    private const val SIG_ZIP64_LOCATOR = 0x07064b50
    private const val SIG_DESCRIPTOR = 0x08074b50
    private const val ZIP64_SENTINEL_32 = 0xFFFFFFFFL
    private const val ZIP64_SENTINEL_16 = 0xFFFF
    private const val FLAG_DATA_DESCRIPTOR = 0x0008
    private const val FLAG_UTF8 = 0x0800
    internal const val LOCAL_HEADER_LEN = 30
    private const val CENTRAL_HEADER_LEN = 46
    private const val EOCD_LEN = 22
    private const val ZIP64_LOCATOR_LEN = 20
    private const val MAX_ZIP_COMMENT = 65535
    private val STREAMABLE_EXT = setOf(
        "mp4", "m4v", "mov", "qt", "avi", "wmv", "webm", "mkv", "mpg", "mpeg",
        "3gp", "asf", "flv", "ts", "m2ts", "mp3", "wav", "m4a", "wma", "aac", "ogg", "opus"
    )
    private val PART_PATH = Regex("^ppt/media/[^/]+$", RegexOption.IGNORE_CASE)

    data class ZipCdEntry(
        val fileName: String,
        val nameBytes: ByteArray,
        val flags: Int,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
        val extra: ByteArray,
        val comment: ByteArray,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val diskStart: Int,
        val internalAttr: Int,
        val externalAttr: Int,
        val zip64Sizes: Boolean
    )

    fun normalizePartPath(raw: String): String? {
        val normalized = raw.replace('\\', '/').trim().trimStart('/')
        if (normalized.contains("..") || normalized.contains('\u0000')) {
            return null
        }
        if (!PART_PATH.matches(normalized)) {
            return null
        }
        if (!isStreamableMediaPath(normalized)) {
            return null
        }
        return normalized
    }

    fun isStreamableMediaPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        if (!normalized.contains("/media/")) {
            return false
        }
        val ext = normalized.substringAfterLast('.', "")
        return ext in STREAMABLE_EXT
    }

    data class ZipDirectory(
        val totalEntries: Int,
        val cdSize: Long,
        val cdOffset: Long
    )

    fun readEntries(bytes: ByteArray): List<ZipCdEntry> {
        return readEntries(bytes, bytes.size.toLong(), 0L)
    }

    fun readEntries(bytes: ByteArray, fileSize: Long, tailStart: Long): List<ZipCdEntry> {
        if (bytes.size < EOCD_LEN) {
            throw IllegalArgumentException("zip too small")
        }
        val directory = locateCentralDirectory(bytes, fileSize, tailStart)
        require(directory.cdOffset >= tailStart) { "central directory not in buffer" }
        require(directory.cdOffset + directory.cdSize <= tailStart + bytes.size) { "central directory truncated" }
        return readCentralEntries(bytes, directory, tailStart)
    }

    fun locateCentralDirectory(bytes: ByteArray, fileSize: Long, tailStart: Long): ZipDirectory {
        if (bytes.size < EOCD_LEN) {
            throw IllegalArgumentException("zip too small")
        }
        return readEocd(bytes, fileSize, tailStart)
    }

    fun hasStreamableMedia(entries: List<ZipCdEntry>): Boolean {
        return entries.any { isStreamableMediaPath(it.fileName) && maxOf(it.uncompressedSize, it.compressedSize) > 0 }
    }

    fun findEntry(entries: List<ZipCdEntry>, partPath: String): ZipCdEntry? {
        val expected = partPath.replace('\\', '/')
        return entries.firstOrNull { it.fileName.replace('\\', '/').equals(expected, ignoreCase = true) }
    }

    fun mapStoredMediaRange(entry: ZipCdEntry, localHeader: ByteArray, mediaRange: Range, zipFileSize: Long): Range {
        require(entry.method == STORED_METHOD) { "not stored" }
        val start = dataStart(entry.localOffset, localHeader)
        return Range(start + mediaRange.start, start + mediaRange.end, zipFileSize)
    }

    fun localHeaderLength(localHeader: ByteArray): Int {
        require(localHeader.size >= LOCAL_HEADER_LEN)
        require(u32(localHeader, 0) == SIG_LOCAL.toLong()) { "local signature" }
        val nameLen = u16(localHeader, 26)
        val extraLen = u16(localHeader, 28)
        return LOCAL_HEADER_LEN + nameLen + extraLen
    }

    /**
     * data descriptor 相对本地头起点的偏移。无 descriptor 时为 null。
     */
    fun descriptorPrefixOffset(localHeader: ByteArray, entry: ZipCdEntry): Long? {
        if (u16(localHeader, 6) and FLAG_DATA_DESCRIPTOR == 0) {
            return null
        }
        return localHeaderLength(localHeader).toLong() + entry.compressedSize
    }

    fun localRecordLength(
        localHeader: ByteArray,
        entry: ZipCdEntry,
        descriptorPrefix: ByteArray = ByteArray(0)
    ): Long {
        val headerLen = localHeaderLength(localHeader)
        var descLen = 0L
        if (descriptorPrefixOffset(localHeader, entry) != null) {
            val hasSig = descriptorPrefix.size >= 4 &&
                u32(descriptorPrefix, 0) == SIG_DESCRIPTOR.toLong()
            descLen = when {
                entry.zip64Sizes && hasSig -> 24L
                entry.zip64Sizes -> 20L
                hasSig -> 16L
                else -> 12L
            }
        }
        return headerLen + entry.compressedSize + descLen
    }

    fun localRecordLength(
        zipBytes: ByteArray,
        localStart: Int,
        localHeader: ByteArray,
        entry: ZipCdEntry
    ): Long {
        val prefixRel = descriptorPrefixOffset(localHeader, entry)
        val prefix = if (prefixRel != null) {
            val descStart = localStart.toLong() + prefixRel
            require(descStart >= 0 && descStart <= zipBytes.size) { "data descriptor truncated" }
            if (descStart + 4 <= zipBytes.size) {
                val descOff = descStart.toInt()
                zipBytes.copyOfRange(descOff, descOff + 4)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        val recordLen = localRecordLength(localHeader, entry, prefix)
        require(localStart.toLong() + recordLen <= zipBytes.size) { "local record truncated" }
        return recordLen
    }

    fun dataStart(localOffset: Long, localHeader: ByteArray): Long {
        return localOffset + localHeaderLength(localHeader)
    }

    fun rebuildSlim(bytes: ByteArray, entries: List<ZipCdEntry>): ByteArray? {
        return rebuildSlim(entries) { entry ->
            require(entry.localOffset <= Int.MAX_VALUE) { "local offset exceeds buffer" }
            val localStart = entry.localOffset.toInt()
            val header = bytes.copyOfRange(localStart, localStart + LOCAL_HEADER_LEN)
            val recordLen = localRecordLength(bytes, localStart, header, entry)
            require(recordLen <= Int.MAX_VALUE) { "local record too large" }
            val recordEnd = localStart + recordLen.toInt()
            require(recordEnd <= bytes.size) { "local record truncated" }
            bytes.copyOfRange(localStart, recordEnd)
        }
    }

    fun rebuildSlim(entries: List<ZipCdEntry>, loadRecord: (ZipCdEntry) -> ByteArray): ByteArray? {
        val locals = ArrayList<ByteArray>()
        val centrals = ArrayList<ByteArray>()
        var offset = 0
        var stripped = false
        for (entry in entries) {
            val size = maxOf(entry.uncompressedSize, entry.compressedSize)
            if (isStreamableMediaPath(entry.fileName) && size > 0) {
                val local = encodeEmptyLocal(entry.nameBytes)
                centrals.add(
                    encodeCentral(
                        entry,
                        offset.toLong(),
                        0,
                        0,
                        0,
                        STORED_METHOD,
                        ByteArray(0),
                        utf8Flag(entry.nameBytes)
                    )
                )
                locals.add(local)
                offset += local.size
                stripped = true
                continue
            }
            val record = loadRecord(entry)
            locals.add(record)
            centrals.add(
                encodeCentral(
                    entry,
                    offset.toLong(),
                    entry.compressedSize,
                    entry.uncompressedSize,
                    entry.crc,
                    entry.method,
                    stripZip64Extra(entry.extra),
                    entry.flags
                )
            )
            offset += record.size
        }
        if (!stripped) {
            return null
        }
        val out = ByteArrayOutputStream(offset + centrals.sumOf { it.size } + EOCD_LEN)
        locals.forEach { out.write(it) }
        centrals.forEach { out.write(it) }
        val cdSize = centrals.sumOf { it.size }
        out.write(encodeEocd(centrals.size, cdSize, offset))
        return out.toByteArray()
    }

    private fun readEocd(bytes: ByteArray, fileSize: Long, tailStart: Long): ZipDirectory {
        val min = maxOf(0, bytes.size - EOCD_LEN - MAX_ZIP_COMMENT)
        var eocdOffset = -1
        for (i in bytes.size - EOCD_LEN downTo min) {
            if (u32(bytes, i) != SIG_EOCD.toLong()) {
                continue
            }
            val commentLen = u16(bytes, i + 20)
            if (i + EOCD_LEN + commentLen == bytes.size) {
                eocdOffset = i
                break
            }
        }
        require(eocdOffset >= 0) { "eocd not found" }
        val disk = u16(bytes, eocdOffset + 4)
        val cdDisk = u16(bytes, eocdOffset + 6)
        var totalEntries = u16(bytes, eocdOffset + 10)
        var cdSize = u32(bytes, eocdOffset + 12)
        var cdOffset = u32(bytes, eocdOffset + 16)
        if (totalEntries == ZIP64_SENTINEL_16 || cdSize == ZIP64_SENTINEL_32 || cdOffset == ZIP64_SENTINEL_32) {
            val zip64 = readZip64Eocd(bytes, eocdOffset, tailStart)
            totalEntries = zip64.totalEntries
            cdSize = zip64.cdSize
            cdOffset = zip64.cdOffset
            require(zip64.disk == 0 && zip64.cdDisk == 0) { "multi-disk zip" }
        } else {
            require(disk == 0 && cdDisk == 0) { "multi-disk zip" }
        }
        require(cdOffset + cdSize <= fileSize) { "central directory truncated" }
        return ZipDirectory(totalEntries, cdSize, cdOffset)
    }

    private data class Zip64Eocd(
        val disk: Int,
        val cdDisk: Int,
        val totalEntries: Int,
        val cdSize: Long,
        val cdOffset: Long
    )

    private fun readZip64Eocd(bytes: ByteArray, eocdOffset: Int, tailStart: Long): Zip64Eocd {
        require(eocdOffset >= ZIP64_LOCATOR_LEN) { "zip64 locator missing" }
        val locator = eocdOffset - ZIP64_LOCATOR_LEN
        require(u32(bytes, locator) == SIG_ZIP64_LOCATOR.toLong()) { "zip64 locator signature" }
        val zip64Offset = u64(bytes, locator + 8)
        val viewOffset = viewIndex(zip64Offset, tailStart, bytes.size)
        require(viewOffset + 56 <= bytes.size) { "zip64 eocd truncated" }
        require(u32(bytes, viewOffset) == SIG_ZIP64_EOCD.toLong()) { "zip64 eocd signature" }
        return Zip64Eocd(
            disk = u32(bytes, viewOffset + 16).toInt(),
            cdDisk = u32(bytes, viewOffset + 20).toInt(),
            totalEntries = u64(bytes, viewOffset + 32).toInt(),
            cdSize = u64(bytes, viewOffset + 40),
            cdOffset = u64(bytes, viewOffset + 48)
        )
    }

    private fun readCentralEntries(bytes: ByteArray, directory: ZipDirectory, tailStart: Long): List<ZipCdEntry> {
        val entries = ArrayList<ZipCdEntry>(directory.totalEntries)
        var cursor = viewIndex(directory.cdOffset, tailStart, bytes.size)
        val end = viewIndex(directory.cdOffset + directory.cdSize, tailStart, bytes.size)
        require(end <= bytes.size) { "central directory truncated" }
        repeat(directory.totalEntries) {
            require(cursor + CENTRAL_HEADER_LEN <= end) { "central entry truncated" }
            require(u32(bytes, cursor) == SIG_CENTRAL.toLong()) { "central signature" }
            val nameLen = u16(bytes, cursor + 28)
            val extraLen = u16(bytes, cursor + 30)
            val commentLen = u16(bytes, cursor + 32)
            val recordEnd = cursor + CENTRAL_HEADER_LEN + nameLen + extraLen + commentLen
            require(recordEnd <= end) { "central name truncated" }
            val nameBytes = bytes.copyOfRange(cursor + CENTRAL_HEADER_LEN, cursor + CENTRAL_HEADER_LEN + nameLen)
            val extra = if (extraLen > 0) {
                bytes.copyOfRange(
                    cursor + CENTRAL_HEADER_LEN + nameLen,
                    cursor + CENTRAL_HEADER_LEN + nameLen + extraLen
                )
            } else {
                ByteArray(0)
            }
            val comment = if (commentLen > 0) {
                bytes.copyOfRange(recordEnd - commentLen, recordEnd)
            } else {
                ByteArray(0)
            }
            var compressedSize = u32(bytes, cursor + 20)
            var uncompressedSize = u32(bytes, cursor + 24)
            var localOffset = u32(bytes, cursor + 42)
            val zip64Sizes = compressedSize == ZIP64_SENTINEL_32 || uncompressedSize == ZIP64_SENTINEL_32
            val resolved = applyZip64Extra(uncompressedSize, compressedSize, localOffset, extra)
            compressedSize = resolved.compressedSize
            uncompressedSize = resolved.uncompressedSize
            localOffset = resolved.localOffset
            entries.add(
                ZipCdEntry(
                    fileName = String(nameBytes, StandardCharsets.UTF_8),
                    nameBytes = nameBytes,
                    flags = u16(bytes, cursor + 8),
                    method = u16(bytes, cursor + 10),
                    time = u16(bytes, cursor + 12),
                    date = u16(bytes, cursor + 14),
                    crc = u32(bytes, cursor + 16),
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localOffset = localOffset,
                    extra = extra,
                    comment = comment,
                    versionMadeBy = u16(bytes, cursor + 4),
                    versionNeeded = u16(bytes, cursor + 6),
                    diskStart = u16(bytes, cursor + 34),
                    internalAttr = u16(bytes, cursor + 36),
                    externalAttr = u32(bytes, cursor + 38).toInt(),
                    zip64Sizes = zip64Sizes
                )
            )
            cursor = recordEnd
        }
        return entries
    }

    private data class Zip64Sizes(
        val uncompressedSize: Long,
        val compressedSize: Long,
        val localOffset: Long
    )

    private fun applyZip64Extra(
        uncompressedSize: Long,
        compressedSize: Long,
        localOffset: Long,
        extra: ByteArray
    ): Zip64Sizes {
        var usize = uncompressedSize
        var csize = compressedSize
        var offset = localOffset
        if (extra.size < 4) {
            return Zip64Sizes(usize, csize, offset)
        }
        var i = 0
        while (i + 4 <= extra.size) {
            val id = u16(extra, i)
            val size = u16(extra, i + 2)
            val next = i + 4 + size
            if (next > extra.size) {
                break
            }
            if (id == 0x0001) {
                var cursor = i + 4
                if (usize == ZIP64_SENTINEL_32 && cursor + 8 <= next) {
                    usize = u64(extra, cursor)
                    cursor += 8
                }
                if (csize == ZIP64_SENTINEL_32 && cursor + 8 <= next) {
                    csize = u64(extra, cursor)
                    cursor += 8
                }
                if (offset == ZIP64_SENTINEL_32 && cursor + 8 <= next) {
                    offset = u64(extra, cursor)
                }
                return Zip64Sizes(usize, csize, offset)
            }
            i = next
        }
        return Zip64Sizes(usize, csize, offset)
    }

    private fun stripZip64Extra(extra: ByteArray): ByteArray {
        if (extra.isEmpty()) {
            return extra
        }
        val kept = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= extra.size) {
            val id = u16(extra, i)
            val size = u16(extra, i + 2)
            val next = i + 4 + size
            if (next > extra.size) {
                return extra
            }
            if (id != 0x0001) {
                kept.add(extra.copyOfRange(i, next))
            }
            i = next
        }
        if (kept.isEmpty()) {
            return ByteArray(0)
        }
        val total = kept.sumOf { it.size }
        val out = ByteArray(total)
        var cursor = 0
        for (part in kept) {
            part.copyInto(out, cursor)
            cursor += part.size
        }
        return out
    }

    private fun utf8Flag(nameBytes: ByteArray): Int {
        return if (nameBytes.any { it.toInt() and 0xFF > 127 }) FLAG_UTF8 else 0
    }

    private fun encodeEmptyLocal(nameBytes: ByteArray): ByteArray {
        val buf = ByteArray(LOCAL_HEADER_LEN + nameBytes.size)
        val view = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        view.putInt(SIG_LOCAL)
        view.putShort(20.toShort())
        view.putShort(utf8Flag(nameBytes).toShort())
        view.putShort(STORED_METHOD.toShort())
        view.position(26)
        view.putShort(nameBytes.size.toShort())
        nameBytes.copyInto(buf, LOCAL_HEADER_LEN)
        return buf
    }

    private fun encodeCentral(
        entry: ZipCdEntry,
        localOffset: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        crc: Long,
        method: Int,
        extra: ByteArray,
        flags: Int
    ): ByteArray {
        val buf = ByteArray(CENTRAL_HEADER_LEN + entry.nameBytes.size + extra.size + entry.comment.size)
        val view = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        view.putInt(SIG_CENTRAL)
        view.putShort(entry.versionMadeBy.toShort())
        view.putShort(entry.versionNeeded.toShort())
        view.putShort(flags.toShort())
        view.putShort(method.toShort())
        view.putShort(entry.time.toShort())
        view.putShort(entry.date.toShort())
        view.putInt(crc.toInt())
        view.putInt(compressedSize.toInt())
        view.putInt(uncompressedSize.toInt())
        view.putShort(entry.nameBytes.size.toShort())
        view.putShort(extra.size.toShort())
        view.putShort(entry.comment.size.toShort())
        view.putShort(entry.diskStart.toShort())
        view.putShort(entry.internalAttr.toShort())
        view.putInt(entry.externalAttr)
        view.putInt(localOffset.toInt())
        var cursor = CENTRAL_HEADER_LEN
        entry.nameBytes.copyInto(buf, cursor)
        cursor += entry.nameBytes.size
        if (extra.isNotEmpty()) {
            extra.copyInto(buf, cursor)
            cursor += extra.size
        }
        if (entry.comment.isNotEmpty()) {
            entry.comment.copyInto(buf, cursor)
        }
        return buf
    }

    private fun encodeEocd(entryCount: Int, cdSize: Int, cdOffset: Int): ByteArray {
        val buf = ByteArray(EOCD_LEN)
        val view = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        view.putInt(SIG_EOCD)
        view.putShort(0)
        view.putShort(0)
        view.putShort(entryCount.toShort())
        view.putShort(entryCount.toShort())
        view.putInt(cdSize)
        view.putInt(cdOffset)
        return buf
    }

    private fun viewIndex(absolute: Long, tailStart: Long, bufferSize: Int): Int {
        val relative = absolute - tailStart
        require(relative >= 0 && relative <= bufferSize) { "offset $absolute not in buffer" }
        require(relative <= Int.MAX_VALUE) { "offset exceeds buffer" }
        return relative.toInt()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        return u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        val lo = u32(bytes, offset)
        val hi = u32(bytes, offset + 4)
        require(hi <= 0x1FFFFFL) { "zip64 size exceeds safe integer" }
        return (hi shl 32) or lo
    }
}
