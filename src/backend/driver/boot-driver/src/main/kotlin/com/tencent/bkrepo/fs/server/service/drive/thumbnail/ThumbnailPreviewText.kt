package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 缩略图预览文本：跳过 UTF-8 BOM，并按字节上限截断。
 */
internal object ThumbnailPreviewText {

    fun read(input: Path, maxBytes: Int): String {
        return Files.newInputStream(input).use { raw ->
            val stream = BufferedInputStream(raw)
            skipUtf8Bom(stream)
            String(stream.readNBytes(maxBytes.coerceAtLeast(1)), StandardCharsets.UTF_8)
        }
    }

    fun skipUtf8Bom(stream: BufferedInputStream) {
        stream.mark(UTF8_BOM.size)
        val bom = ByteArray(UTF8_BOM.size)
        val n = stream.read(bom)
        if (n == UTF8_BOM.size && bom.contentEquals(UTF8_BOM)) {
            return
        }
        stream.reset()
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
