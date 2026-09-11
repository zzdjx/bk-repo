package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResource
import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResourceWriter
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.BoundedInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.artifact.util.http.HttpRangeUtils
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PREVIEW_RESPONSE_CONTENT_TYPE
import com.tencent.bkrepo.preview.constant.PREVIEW_RESPONSE_FILENAME
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import com.tencent.bkrepo.preview.exception.PreviewNotFoundException
import com.tencent.bkrepo.preview.exception.PreviewSystemException
import com.tencent.bkrepo.preview.pojo.FileAttribute
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 从 pptx ZIP 里按 Range 流出 `ppt/media` 音视频，并把包里的媒体条目换成空文件后再返回幻灯片包。
 */
@Component
class PptxEmbeddedMediaService(
    private val fileTransferService: FileTransferService,
    private val artifactResourceWriter: ArtifactResourceWriter,
    private val config: PreviewConfig
) {

    fun streamPart(fileAttribute: FileAttribute) {
        val partPath = fileAttribute.embeddedPartPath
            ?: throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "part")
        val (fileSize, entries) = try {
            loadZipIndex(fileAttribute)
        } catch (_: IllegalArgumentException) {
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "part")
        }
        val entry = PptxZipMedia.findEntry(entries, partPath)
            ?: throw PreviewNotFoundException(PreviewMessageCode.PREVIEW_FILE_NOT_FOUND, partPath)
        val header = readSlice(fileAttribute, entry.localOffset, PptxZipMedia.LOCAL_HEADER_LEN.toLong(), fileSize)
        val mediaRange = resolveMediaRange(entry.uncompressedSize)
        val stream = when (entry.method) {
            PptxZipMedia.STORED_METHOD -> streamStored(fileAttribute, entry, header, mediaRange, fileSize)
            PptxZipMedia.DEFLATE_METHOD -> streamDeflate(fileAttribute, entry, header, mediaRange, fileSize)
            else -> throw PreviewSystemException(
                PreviewMessageCode.PREVIEW_FILE_NOT_SUPPORT_ERROR,
                partPath
            )
        }
        val fileName = partPath.substringAfterLast('/')
        val contentType = MediaPreviewContentType.fromSuffix(fileName.substringAfterLast('.', ""))
            ?: OCTET_STREAM
        writeStream(fileAttribute, stream, fileName, contentType)
    }

    fun streamSlimPackage(fileAttribute: FileAttribute) {
        val fileSize = try {
            fileTransferService.originalFileSize(fileAttribute)
        } catch (exception: IllegalArgumentException) {
            fallbackToOriginal(fileAttribute, "Failed to stat pptx", exception)
            return
        }
        if (fileSize < MIN_ZIP_SIZE) {
            fallbackToOriginal(fileAttribute)
            return
        }
        val entries = try {
            loadZipIndex(fileAttribute).second
        } catch (exception: IllegalArgumentException) {
            fallbackToOriginal(fileAttribute, "Failed to index pptx zip", exception)
            return
        } catch (exception: PreviewInvalidException) {
            fallbackToOriginal(fileAttribute, "Failed to index pptx zip", exception)
            return
        }
        if (!PptxZipMedia.hasStreamableMedia(entries)) {
            fallbackToOriginal(fileAttribute)
            return
        }
        val slim = try {
            PptxZipMedia.rebuildSlim(entries) { entry ->
                loadLocalRecord(fileAttribute, entry, fileSize)
            }
        } catch (exception: IllegalArgumentException) {
            fallbackToOriginal(fileAttribute, "Failed to slim pptx", exception)
            return
        }
        if (slim == null || slim.isEmpty()) {
            fallbackToOriginal(fileAttribute)
            return
        }
        writePackage(fileAttribute, slim)
    }

    /**
     * 只读非媒体本地记录。音视频条目由 [PptxZipMedia.rebuildSlim] 写成空文件，不会进这里。
     */
    private fun loadLocalRecord(
        fileAttribute: FileAttribute,
        entry: PptxZipMedia.ZipCdEntry,
        fileSize: Long
    ): ByteArray {
        val header = readSlice(
            fileAttribute,
            entry.localOffset,
            PptxZipMedia.LOCAL_HEADER_LEN.toLong(),
            fileSize
        )
        val prefixRel = PptxZipMedia.descriptorPrefixOffset(header, entry)
        val prefix = if (prefixRel != null) {
            val descStart = entry.localOffset + prefixRel
            if (descStart >= 0 && descStart + 4 <= fileSize) {
                readSlice(fileAttribute, descStart, 4, fileSize)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        val recordLen = PptxZipMedia.localRecordLength(header, entry, prefix)
        val maxBytes = config.maxFileSize * 1024 * 1024
        require(recordLen > 0 && recordLen <= maxBytes) { "local record too large" }
        return readSlice(fileAttribute, entry.localOffset, recordLen, fileSize)
    }

    private fun streamStored(
        fileAttribute: FileAttribute,
        entry: PptxZipMedia.ZipCdEntry,
        header: ByteArray,
        mediaRange: Range,
        fileSize: Long
    ): ArtifactInputStream {
        if (mediaRange.length <= 0L) {
            return ArtifactInputStream(ByteArrayInputStream(ByteArray(0)), mediaRange)
        }
        val storageRange = PptxZipMedia.mapStoredMediaRange(entry, header, mediaRange, fileSize)
        val storageStream = fileTransferService.loadOriginalRange(fileAttribute, storageRange)
        return ArtifactInputStream(storageStream, mediaRange)
    }

    private fun streamDeflate(
        fileAttribute: FileAttribute,
        entry: PptxZipMedia.ZipCdEntry,
        header: ByteArray,
        mediaRange: Range,
        fileSize: Long
    ): ArtifactInputStream {
        if (entry.compressedSize <= 0L) {
            return ArtifactInputStream(ByteArrayInputStream(ByteArray(0)), mediaRange)
        }
        val dataStart = PptxZipMedia.dataStart(entry.localOffset, header)
        val compressedRange = Range(dataStart, dataStart + entry.compressedSize - 1, fileSize)
        val compressed = fileTransferService.loadOriginalRange(fileAttribute, compressedRange)
        val inflated = openRawInflaterStream(compressed)
        try {
            skipFully(inflated, mediaRange.start)
        } catch (exception: Exception) {
            inflated.close()
            throw exception
        }
        return ArtifactInputStream(BoundedInputStream(inflated, mediaRange.length), mediaRange)
    }

    /**
     * ZIP 条目是 raw deflate，必须 `Inflater(nowrap=true)`。
     * 自己传入的 Inflater 不会在 [InflaterInputStream.close] 里 `end()`，这里关流时补上。
     */
    internal fun openRawInflaterStream(compressed: InputStream): InputStream {
        val inflater = Inflater(true)
        return object : InflaterInputStream(compressed, inflater) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    inflater.end()
                }
            }
        }
    }

    private fun loadZipIndex(fileAttribute: FileAttribute): Pair<Long, List<PptxZipMedia.ZipCdEntry>> {
        val fileSize = fileTransferService.originalFileSize(fileAttribute)
        if (fileSize < MIN_ZIP_SIZE) {
            throw PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "part")
        }
        val tailSizes = linkedSetOf(min(fileSize, ZIP_TAIL_BYTES), min(fileSize, ZIP_TAIL_RETRY_BYTES))
        var lastError: IllegalArgumentException? = null
        for (tailLen in tailSizes) {
            val tailStart = fileSize - tailLen
            val tail = readSlice(fileAttribute, tailStart, tailLen, fileSize)
            val directory = try {
                PptxZipMedia.locateCentralDirectory(tail, fileSize, tailStart)
            } catch (exception: IllegalArgumentException) {
                lastError = exception
                continue
            }
            val cdInTail = directory.cdOffset >= tailStart &&
                directory.cdOffset + directory.cdSize <= tailStart + tail.size
            if (cdInTail) {
                return fileSize to PptxZipMedia.readEntries(tail, fileSize, tailStart)
            }
            val cdLength = fileSize - directory.cdOffset
            if (directory.cdSize > MAX_CENTRAL_DIRECTORY_BYTES ||
                cdLength > directory.cdSize + ZIP_TAIL_RETRY_BYTES
            ) {
                lastError = IllegalArgumentException("central directory span invalid")
                continue
            }
            val located = readSlice(fileAttribute, directory.cdOffset, cdLength, fileSize)
            return fileSize to PptxZipMedia.readEntries(located, fileSize, directory.cdOffset)
        }
        throw lastError ?: PreviewInvalidException(PreviewMessageCode.PREVIEW_PARAMETER_INVALID, "part")
    }

    private fun readSlice(fileAttribute: FileAttribute, start: Long, length: Long, fileSize: Long): ByteArray {
        if (length <= 0L) {
            return ByteArray(0)
        }
        require(length <= Int.MAX_VALUE) { "slice too large" }
        val range = Range(start, start + length - 1, fileSize)
        return fileTransferService.loadOriginalRange(fileAttribute, range).use { stream ->
            val bytes = stream.readNBytes(length.toInt())
            if (bytes.size.toLong() != length) {
                throw PreviewNotFoundException(
                    PreviewMessageCode.PREVIEW_FILE_NOT_FOUND,
                    fileAttribute.fileName.orEmpty()
                )
            }
            bytes
        }
    }

    private fun resolveMediaRange(total: Long): Range {
        val request = HttpContextHolder.getRequestOrNull() ?: return Range.full(total)
        return try {
            HttpRangeUtils.resolveRange(request, total)
        } catch (_: IllegalArgumentException) {
            throw ErrorCodeException(
                status = HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                messageCode = CommonMessageCode.REQUEST_RANGE_INVALID
            )
        }
    }

    private fun writePackage(fileAttribute: FileAttribute, bytes: ByteArray) {
        val fileName = fileAttribute.fileName?.trim()?.takeIf { it.isNotEmpty() } ?: "preview.pptx"
        val contentType = MediaPreviewContentType.fromSuffix(fileAttribute.suffix.orEmpty())
            ?: OCTET_STREAM
        val stream = ArtifactInputStream(ByteArrayInputStream(bytes), Range.full(bytes.size.toLong()))
        writeStream(fileAttribute, stream, fileName, contentType)
    }

    private fun writeStream(
        fileAttribute: FileAttribute,
        stream: ArtifactInputStream,
        fileName: String,
        contentType: String
    ) {
        fileTransferService.prepareOriginalContext(fileAttribute)
        val request = HttpContextHolder.getRequest()
        request.setAttribute(PREVIEW_RESPONSE_CONTENT_TYPE, contentType)
        request.setAttribute(PREVIEW_RESPONSE_FILENAME, fileName)
        val resource = ArtifactResource(stream, fileName)
        resource.contentType = contentType
        resource.useDisposition = false
        artifactResourceWriter.write(resource)
    }

    private fun fallbackToOriginal(
        fileAttribute: FileAttribute,
        action: String? = null,
        exception: Exception? = null
    ) {
        if (action != null && exception != null) {
            logger.warn("$action [${fileAttribute.artifactUri}], fallback to original", exception)
        }
        fileTransferService.sendOriginalFileAsResponse(fileAttribute)
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(SKIP_BUFFER_SIZE)
        while (remaining > 0) {
            val n = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (n < 0) {
                throw EOFException("deflate media truncated")
            }
            remaining -= n
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PptxEmbeddedMediaService::class.java)
        internal const val ZIP_TAIL_BYTES = 66L * 1024
        internal const val ZIP_TAIL_RETRY_BYTES = 1024L * 1024
        private const val MAX_CENTRAL_DIRECTORY_BYTES = 16L * 1024 * 1024
        private const val MIN_ZIP_SIZE = 22L
        private const val SKIP_BUFFER_SIZE = 8192
        private const val OCTET_STREAM = "application/octet-stream"
    }
}
