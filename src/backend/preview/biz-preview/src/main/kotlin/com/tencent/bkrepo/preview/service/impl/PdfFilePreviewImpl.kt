/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.preview.service.impl

import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.service.FileTransferService
import com.tencent.bkrepo.preview.service.PdfPreviewArtifactNameSource
import com.tencent.bkrepo.preview.service.PdfPreviewDisplayName
import com.tencent.bkrepo.preview.service.PdfPreviewTitle
import com.tencent.bkrepo.preview.service.PdfRangedRandomAccess
import com.tencent.bkrepo.preview.service.cache.impl.PreviewFileCacheServiceImpl
import org.springframework.stereotype.Service
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * 本地 PDF 按 Range 流出。有作品名时在文件末尾追加标题补丁，正文仍从存储按 Range 读。
 */
@Service
class PdfFilePreviewImpl(
    config: PreviewConfig,
    private val fileTransferService: FileTransferService,
    previewFileCacheService: PreviewFileCacheServiceImpl,
    nodeService: NodeService,
    private val artifactNameSource: PdfPreviewArtifactNameSource,
) : AbstractFilePreview(
    config,
    fileTransferService,
    previewFileCacheService,
    nodeService
) {
    private val suffixCache = object : LinkedHashMap<String, ByteArray>(CACHE_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > CACHE_CAPACITY
        }
    }

    override fun filePreviewHandle(fileAttribute: FileAttribute) {
        if (fileAttribute.storageType != 0) {
            super.filePreviewHandle(fileAttribute)
            return
        }
        val title = PdfPreviewDisplayName.normalize(artifactNameSource.read(fileAttribute))
        if (title == null) {
            fileTransferService.sendOriginalFileAsResponse(fileAttribute)
            return
        }
        val size = fileTransferService.originalFileSize(fileAttribute)
        val suffix = titleSuffix(fileAttribute, title, size)
        if (suffix == null) {
            fileTransferService.sendOriginalFileAsResponse(fileAttribute)
            return
        }
        fileTransferService.sendAppendedBytes(fileAttribute, size, suffix)
    }

    private fun titleSuffix(fileAttribute: FileAttribute, title: String, size: Long): ByteArray? {
        val key = cacheKey(fileAttribute, title, size)
        synchronized(suffixCache) {
            suffixCache[key]?.let { cached ->
                return cached.takeIf { it.isNotEmpty() }
            }
        }
        val suffix = PdfPreviewTitle.incrementalUpdate(
            PdfRangedRandomAccess(size) { offset, length ->
                fileTransferService.readAt(fileAttribute, offset, length, size)
            },
            title,
        )
        synchronized(suffixCache) {
            suffixCache[key] = suffix ?: ByteArray(0)
        }
        return suffix
    }

    private fun cacheKey(fileAttribute: FileAttribute, title: String, size: Long): String {
        val tailLength = min(TAIL_BYTES, size).toInt()
        val tail = if (tailLength == 0) {
            ByteArray(0)
        } else {
            fileTransferService.readAt(fileAttribute, size - tailLength, tailLength, size)
        }
        return listOf(
            fileAttribute.projectId,
            fileAttribute.repoName,
            fileAttribute.artifactUri,
            size.toString(),
            title,
            hex(tail),
        ).joinToString("|")
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and BYTE_MASK
            out.append(HEX[value ushr NIBBLE])
            out.append(HEX[value and NIBBLE_MASK])
        }
        return out.toString()
    }

    private companion object {
        const val CACHE_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
        const val TAIL_BYTES = 32L
        const val BYTE_MASK = 0xFF
        const val NIBBLE = 4
        const val NIBBLE_MASK = 0x0F
        val HEX = "0123456789abcdef".toCharArray()
    }
}
