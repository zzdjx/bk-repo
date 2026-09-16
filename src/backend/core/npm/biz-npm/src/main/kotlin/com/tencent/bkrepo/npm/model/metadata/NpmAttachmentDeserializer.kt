package com.tencent.bkrepo.npm.model.metadata

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.api.toArtifactFile
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.resolve.file.chunk.ChunkedArtifactFile
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Files

/**
 * 将 npm `_attachments.*.data` 的 Base64 流式解码到磁盘，避免先物化成巨型 String。
 *
 * Jackson 在 `VALUE_STRING` 上默认会把整段文本读进 `_textBuffer` 并校验 `maxStringLength`。
 * 必须在 token 仍处于 incomplete 时调用 [JsonParser.readBinaryValue]，才能绕过字符串上限。
 */
class NpmAttachmentDeserializer : JsonDeserializer<NpmPackageMetaData.Attachment>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): NpmPackageMetaData.Attachment {
        if (parser.currentToken != JsonToken.START_OBJECT) {
            parser.nextToken()
        }
        if (parser.currentToken != JsonToken.START_OBJECT) {
            return ctxt.reportInputMismatch(
                NpmPackageMetaData.Attachment::class.java,
                "Expected START_OBJECT for npm attachment"
            )
        }
        val attachment = NpmPackageMetaData.Attachment()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val field = parser.currentName()
            val valueToken = parser.nextToken()
            when (field) {
                CONTENT_TYPE_FIELD -> attachment.contentType = parser.valueAsString
                LENGTH_FIELD -> attachment.length = parser.valueAsInt
                DATA_FIELD -> if (valueToken != JsonToken.VALUE_NULL) {
                    attachment.artifactFile = consumeDataField(parser)
                }
                else -> parser.skipChildren()
            }
        }
        return attachment
    }

    /**
     * 必须用 [JsonParser.readBinaryValue] 消费 incomplete 的 `data` 字符串。
     * 查询/迁移/修数等读路径不在 [withCleanup] 内，解码到空流即可，避免落盘后无人清理。
     */
    private fun consumeDataField(parser: JsonParser): ArtifactFile? {
        if (!capturing()) {
            parser.readBinaryValue(OutputStream.nullOutputStream())
            return null
        }
        return readArtifactFile(parser)
    }

    private fun readArtifactFile(parser: JsonParser): ArtifactFile {
        val chunked = tryCreateChunkedFile()
        val artifactFile = if (chunked != null) {
            readToChunkedFile(parser, chunked)
        } else {
            readToTempFile(parser)
        }
        track(artifactFile)
        return artifactFile
    }

    private fun tryCreateChunkedFile(): ChunkedArtifactFile? {
        return try {
            ArtifactFileFactory.buildChunked()
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
    }

    private fun readToChunkedFile(parser: JsonParser, chunked: ChunkedArtifactFile): ArtifactFile {
        try {
            // 不要 close OutputStream：ChunkedFileOutputStream.close() 会清理未 finish 的文件
            parser.readBinaryValue(ChunkedWriteStream(chunked))
            chunked.finish()
            return chunked
        } catch (exception: Exception) {
            chunked.close()
            throw exception
        }
    }

    private fun readToTempFile(parser: JsonParser): ArtifactFile {
        val temp = Files.createTempFile("npm-attachment-", ".tgz")
        try {
            Files.newOutputStream(temp).use { parser.readBinaryValue(it) }
            return temp.toFile().toArtifactFile()
        } catch (exception: Exception) {
            Files.deleteIfExists(temp)
            throw exception
        }
    }

    /**
     * 只转发写入，close 时不销毁 [ChunkedArtifactFile]，由 [ChunkedArtifactFile.finish] 收尾。
     */
    private class ChunkedWriteStream(
        private val file: ChunkedArtifactFile
    ) : OutputStream() {
        override fun write(b: Int) {
            file.write(b)
        }

        override fun write(b: ByteArray) {
            file.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            file.write(b, off, len)
        }
    }

    companion object {
        private const val CONTENT_TYPE_FIELD = "content_type"
        private const val LENGTH_FIELD = "length"
        private const val DATA_FIELD = "data"
        private val logger = LoggerFactory.getLogger(NpmAttachmentDeserializer::class.java)
        private val captureEnabled = ThreadLocal<Boolean>()
        private val trackedFiles = ThreadLocal<MutableList<ArtifactFile>>()

        /**
         * 解析 npm metadata 并在成功或失败后清理 `_attachments.data` 落盘产生的临时文件。
         * 仅在此作用域内才会把 `data` 落到磁盘；覆盖 Jackson 中途失败以及后续业务异常。
         */
        fun <T> withCleanup(
            parse: () -> NpmPackageMetaData,
            action: (NpmPackageMetaData) -> T
        ): T {
            val previous = captureEnabled.get()
            captureEnabled.set(true)
            var meta: NpmPackageMetaData? = null
            try {
                meta = parse()
                return action(meta)
            } finally {
                try {
                    meta?.attachments?.deleteArtifactFiles()
                } catch (exception: Exception) {
                    logger.warn("Failed to delete npm attachment files from metadata", exception)
                }
                cleanupTrackedFiles()
                if (previous == true) {
                    captureEnabled.set(true)
                } else {
                    captureEnabled.remove()
                }
            }
        }

        private fun capturing(): Boolean = captureEnabled.get() == true

        fun cleanupTrackedFiles() {
            val files = trackedFiles.get() ?: return
            trackedFiles.remove()
            files.forEach { file ->
                try {
                    file.delete()
                } catch (exception: Exception) {
                    logger.warn("Failed to delete npm attachment temp file", exception)
                }
            }
        }

        private fun track(file: ArtifactFile) {
            val files = trackedFiles.get()
            if (files == null) {
                trackedFiles.set(mutableListOf(file))
            } else {
                files.add(file)
            }
        }
    }
}
