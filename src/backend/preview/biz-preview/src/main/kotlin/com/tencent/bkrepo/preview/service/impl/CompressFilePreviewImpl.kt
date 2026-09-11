/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2025 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.artifact.exception.ArtifactNotFoundException
import com.tencent.bkrepo.common.artifact.exception.NodeNotFoundException
import com.tencent.bkrepo.common.artifact.manager.StorageManager
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.artifact.path.PathUtils
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactDownloadContext
import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResource
import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResourceWriter
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewSystemException
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.cache.impl.PreviewFileCacheServiceImpl
import java.io.BufferedInputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.springframework.stereotype.Service
import org.springframework.util.unit.DataSize

@Service
class CompressFilePreviewImpl(
    private val config: PreviewConfig,
    fileTransferService: FileTransferService,
    previewFileCacheService: PreviewFileCacheServiceImpl,
    private val nodeService: NodeService,
    private val storageManager: StorageManager,
    private val artifactResourceWriter: ArtifactResourceWriter
) : AbstractFilePreview(
    config,
    fileTransferService,
    previewFileCacheService,
    nodeService
) {

    override fun filePreviewHandle(fileAttribute: FileAttribute) {
        val artifactInfo = ArtifactInfo(
            fileAttribute.projectId.orEmpty(),
            fileAttribute.repoName.orEmpty(),
            fileAttribute.artifactUri.orEmpty()
        )
        val maxSizeBytes = DataSize.ofMegabytes(config.maxFileSize).toBytes()
        val maxSizeInt = maxSizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        getArchiveInputStream(artifactInfo).use { inputStream ->
            var entry = inputStream.nextEntry
            while (entry != null) {
                if (entry.name == fileAttribute.zipEntryPath) {
                    rejectIfDeclaredSizeTooLarge(entry, maxSizeBytes)
                    val byteArray = readBoundedEntryBytes(inputStream, maxSizeInt)
                    val artifactResource = ArtifactResource(
                        inputStream = ArtifactInputStream(
                            byteArray.inputStream(),
                            Range.full(byteArray.size.toLong())
                        ),
                        artifactName = entry.name
                    )
                    artifactResourceWriter.write(artifactResource)
                    return
                }
                entry = inputStream.nextEntry
            }
        }
        throw NodeNotFoundException(fileAttribute.zipEntryPath.orEmpty())
    }

    private fun getArchiveInputStream(artifactInfo: ArtifactInfo): ArchiveInputStream {
        with(artifactInfo) {
            val fileExtension = PathUtils.resolveExtension(getArtifactName())
            if (!Regex(COMPRESSED_FILE_TYPE_PATTERN, RegexOption.IGNORE_CASE).matches(fileExtension)) {
                throw ErrorCodeException(ArtifactMessageCode.ARTIFACT_TYPE_UNSUPPORTED, fileExtension)
            }
            val node = nodeService.getNodeDetail(artifactInfo)
                ?: throw NodeNotFoundException(getArtifactFullPath())
            if (node.size > COMPRESSED_FILE_SIZE_LIMIT) {
                throw ErrorCodeException(ArtifactMessageCode.ARTIFACT_SIZE_TOO_LARGE, COMPRESSED_FILE_SIZE_LIMIT_DESC)
            }
            val context = ArtifactDownloadContext()
            var inputStream: InputStream = storageManager.loadArtifactInputStream(node, context.storageCredentials)
                ?: throw ArtifactNotFoundException(getArtifactFullPath())
            if (fileExtension.equals(GZ_FILE_TYPE, ignoreCase = true) ||
                fileExtension.equals(TGZ_FILE_TYPE, ignoreCase = true)
            ) {
                inputStream = GzipCompressorInputStream(inputStream)
            }
            return try {
                ArchiveStreamFactory().createArchiveInputStream(BufferedInputStream(inputStream))
            } catch (e: ArchiveException) {
                throw ErrorCodeException(ArtifactMessageCode.ARTIFACT_TYPE_UNSUPPORTED, StringPool.UNKNOWN)
            }
        }
    }

    companion object {
        private const val COMPRESSED_FILE_TYPE_PATTERN = "(rar|zip|gz|tgz|tar|jar)\$"
        private const val COMPRESSED_FILE_SIZE_LIMIT = 1024 * 1024 * 1024
        private const val COMPRESSED_FILE_SIZE_LIMIT_DESC = "1GB"
        private const val GZ_FILE_TYPE = "gz"
        private const val TGZ_FILE_TYPE = "tgz"

        fun rejectIfDeclaredSizeTooLarge(entry: ArchiveEntry, maxSizeBytes: Long) {
            if (entry.size >= 0 && entry.size > maxSizeBytes) {
                throw fileSizeLimitException(maxSizeBytes)
            }
        }

        /**
         * 按实际上界读取条目内容，避免按 maxFileSize 预分配堆数组。
         * 超出上限时拒绝，防止压缩包条目把堆打满。
         */
        fun readBoundedEntryBytes(input: InputStream, maxSizeBytes: Int): ByteArray {
            val bytes = input.readNBytes(maxSizeBytes)
            if (bytes.size == maxSizeBytes && input.read() != -1) {
                throw fileSizeLimitException(maxSizeBytes.toLong())
            }
            return bytes
        }

        private fun fileSizeLimitException(maxSizeBytes: Long): PreviewSystemException {
            val maxSizeInMb = maxSizeBytes / (1024 * 1024)
            return PreviewSystemException(
                PreviewMessageCode.PREVIEW_FILE_SIZE_LIMIT_ERROR,
                "${maxSizeInMb}M"
            )
        }
    }
}
