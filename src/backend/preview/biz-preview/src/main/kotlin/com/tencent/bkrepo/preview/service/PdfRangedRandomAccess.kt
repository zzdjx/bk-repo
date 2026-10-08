package com.tencent.bkrepo.preview.service

import org.apache.pdfbox.io.RandomAccessRead
import java.io.EOFException
import java.io.IOException
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * 按页从外部读取 PDF，供 PDFBox 只碰 xref 和 Info，不把正文拉进堆。
 */
class PdfRangedRandomAccess(
    private val fileLength: Long,
    private val readRange: (offset: Long, length: Int) -> ByteArray,
) : RandomAccessRead {

    private var position = 0L
    private var closed = false
    private var sourceReadsSkipped = false
    private val pages = object : LinkedHashMap<Long, ByteArray>(MAX_CACHED_PAGES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > MAX_CACHED_PAGES
        }
    }

    /**
     * 解析结束后调用。saveIncremental 会再把原文顺序读一遍，调用方会丢掉这些字节。
     * 此后 read 只推进位置，不再向存储取数。
     */
    fun skipSourceReads() {
        sourceReadsSkipped = true
    }

    override fun read(): Int {
        if (sourceReadsSkipped) {
            val count = readSkipped(1)
            return if (count < 0) EOF else 0
        }
        val one = readAt(position, 1)
        if (one.isEmpty()) {
            return EOF
        }
        position += 1
        return one[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray): Int {
        return read(buffer, 0, buffer.size)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (sourceReadsSkipped) {
            return readSkipped(length)
        }
        val data = readAt(position, length)
        if (data.isEmpty()) {
            return EOF
        }
        data.copyInto(buffer, offset, 0, data.size)
        position += data.size
        return data.size
    }

    override fun seek(newPosition: Long) {
        ensureOpen()
        if (newPosition < 0L) {
            throw IOException("negative seek")
        }
        position = newPosition
    }

    override fun getPosition(): Long = position

    override fun length(): Long = fileLength

    override fun isClosed(): Boolean = closed

    override fun close() {
        closed = true
        pages.clear()
    }

    override fun peek(): Int {
        val value = read()
        if (value != EOF) {
            rewind(1)
        }
        return value
    }

    override fun rewind(bytes: Int) {
        seek(getPosition() - bytes)
    }

    override fun readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(buffer, offset, length - offset)
            if (count < 0) {
                throw EOFException()
            }
            offset += count
        }
        return buffer
    }

    override fun isEOF(): Boolean = position >= fileLength

    override fun available(): Int {
        return min(fileLength - position, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
    }

    private fun readSkipped(length: Int): Int {
        ensureOpen()
        if (length <= 0 || position >= fileLength) {
            return EOF
        }
        val count = min(length.toLong(), fileLength - position).toInt()
        position += count
        return count
    }

    private fun readAt(offset: Long, length: Int): ByteArray {
        ensureOpen()
        if (length <= 0 || offset >= fileLength) {
            return ByteArray(0)
        }
        val capped = min(length.toLong(), fileLength - offset).toInt()
        val out = ByteArray(capped)
        var filled = 0
        while (filled < capped) {
            val pos = offset + filled
            val pageStart = pos / PAGE_SIZE * PAGE_SIZE
            val page = page(pageStart)
            val pageOffset = (pos - pageStart).toInt()
            if (pageOffset >= page.size) {
                break
            }
            val count = min(capped - filled, page.size - pageOffset)
            page.copyInto(out, filled, pageOffset, pageOffset + count)
            filled += count
        }
        return if (filled == capped) out else out.copyOf(filled)
    }

    private fun page(start: Long): ByteArray {
        pages[start]?.let { return it }
        val length = min(PAGE_SIZE, fileLength - start).toInt()
        val data = readRange(start, length)
        pages[start] = data
        return data
    }

    private fun ensureOpen() {
        if (closed) {
            throw IOException("closed")
        }
    }

    private companion object {
        const val PAGE_SIZE = 64L * 1024
        const val MAX_CACHED_PAGES = 8
        const val LOAD_FACTOR = 0.75f
        const val EOF = -1
        const val BYTE_MASK = 0xFF
    }
}
