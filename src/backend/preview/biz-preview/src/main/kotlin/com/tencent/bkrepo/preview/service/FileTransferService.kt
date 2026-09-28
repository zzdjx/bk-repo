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

package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.artifact.constant.ARTIFACT_INFO_KEY
import com.tencent.bkrepo.common.artifact.constant.REPO_KEY
import com.tencent.bkrepo.common.artifact.manager.NodeResourceFactory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.properties.EnableMultiTenantProperties
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactContextHolder
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactDownloadContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.core.ArtifactService
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResource
import com.tencent.bkrepo.common.artifact.resolve.response.ArtifactResourceWriter
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.artifact.util.http.HttpRangeUtils
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import com.tencent.bkrepo.common.metadata.util.ProjectServiceHelper
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.preview.config.configuration.PreviewConfig
import com.tencent.bkrepo.preview.constant.PREVIEW_ARTIFACT_TO_FILE
import com.tencent.bkrepo.preview.constant.PREVIEW_NODE_DETAIL
import com.tencent.bkrepo.preview.constant.PREVIEW_RESPONSE_CONTENT_TYPE
import com.tencent.bkrepo.preview.constant.PREVIEW_RESPONSE_FILENAME
import com.tencent.bkrepo.preview.constant.PREVIEW_TMP_FILE_SAVE_PATH
import com.tencent.bkrepo.preview.constant.PreviewMessageCode
import com.tencent.bkrepo.preview.exception.PreviewInvalidException
import com.tencent.bkrepo.preview.exception.PreviewNotFoundException
import com.tencent.bkrepo.preview.pojo.DownloadResult
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.cache.PreviewFileCacheInfo
import com.tencent.bkrepo.preview.utils.DownloadUtils
import com.tencent.bkrepo.preview.utils.FileUtils
import com.tencent.bkrepo.repository.pojo.node.NodeDetail
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import jakarta.servlet.http.HttpServletRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class FileTransferService(
    private val config: PreviewConfig,
    private val downloadUtils: DownloadUtils,
    private val repositoryService: RepositoryService,
    private val createIfAbsentService: CreateIfAbsentService,
    private val enableMultiTenant: EnableMultiTenantProperties,
    private val drivePreviewDownloadService: DrivePreviewDownloadService,
    private val nodeResourceFactory: NodeResourceFactory,
    private val artifactResourceWriter: ArtifactResourceWriter,
) : ArtifactService() {

    /**
     * 按 Range 把源制品流式写出，不落地临时文件、不写入 convert 仓库。
     */
    fun sendOriginalFileAsResponse(fileAttribute: FileAttribute) {
        val artifactInfo = requireArtifactInfo(fileAttribute)
        setFileTransferAttribute(artifactInfo, fileAttribute, false)
        setInlinePreviewContentType(fileAttribute.suffix.orEmpty(), fileAttribute.fileName)
        val context = ArtifactDownloadContext(useDisposition = false)
        // Drive 节点不在通用 TNode 表，不能用 node 判空，否则媒体预览会误报 404
        if (context.repositoryDetail.type != RepositoryType.DRIVE) {
            val node = ArtifactContextHolder.getNodeDetail(artifactInfo)
            if (node == null && context.repositoryDetail.category == RepositoryCategory.LOCAL) {
                throw PreviewNotFoundException(
                    PreviewMessageCode.PREVIEW_NODE_NOT_FOUND,
                    "${artifactInfo.projectId}|${artifactInfo.repoName}|${artifactInfo.getArtifactFullPath()}"
                )
            }
        }
        repository.download(context)
    }

    /**
     * 把仓库上下文写到 request，后续 Range 读取不会走 HTTP Range。
     */
    fun prepareOriginalContext(fileAttribute: FileAttribute) {
        val artifactInfo = requireArtifactInfo(fileAttribute)
        setFileTransferAttribute(artifactInfo, fileAttribute, false)
    }

    fun originalFileSize(fileAttribute: FileAttribute): Long {
        val (artifactInfo, repoDetail) = openOriginal(fileAttribute)
        if (repoDetail.type == RepositoryType.DRIVE) {
            return drivePreviewDownloadService.getFileSize(
                artifactInfo.projectId,
                artifactInfo.repoName,
                artifactInfo.getArtifactFullPath()
            )
        }
        val node = ArtifactContextHolder.getNodeDetail(artifactInfo)
            ?: throw PreviewNotFoundException(
                PreviewMessageCode.PREVIEW_NODE_NOT_FOUND,
                "${artifactInfo.projectId}|${artifactInfo.repoName}|${artifactInfo.getArtifactFullPath()}"
            )
        return node.size
    }

    /**
     * 按存储 Range 读原制品。不要用 HTTP Range，调用方自己映射。
     */
    fun loadOriginalRange(fileAttribute: FileAttribute, range: Range): ArtifactInputStream {
        val (artifactInfo, repoDetail) = openOriginal(fileAttribute)
        if (repoDetail.type == RepositoryType.DRIVE) {
            return drivePreviewDownloadService.loadArtifactInputStream(
                artifactInfo.projectId,
                artifactInfo.repoName,
                artifactInfo.getArtifactFullPath(),
                repoDetail.storageCredentials,
                range
            )
        }
        val node = ArtifactContextHolder.getNodeDetail(artifactInfo)
            ?: throw PreviewNotFoundException(
                PreviewMessageCode.PREVIEW_NODE_NOT_FOUND,
                "${artifactInfo.projectId}|${artifactInfo.repoName}|${artifactInfo.getArtifactFullPath()}"
            )
        return nodeResourceFactory.getNodeResource(node.nodeInfo, range, repoDetail.storageCredentials)
            .getArtifactInputStream()
            ?: throw PreviewNotFoundException(
                PreviewMessageCode.PREVIEW_FILE_NOT_FOUND,
                "${artifactInfo.projectId}|${artifactInfo.repoName}|${artifactInfo.getArtifactFullPath()}"
            )
    }

    /**
     * 读完整原文件，供改写 PDF 标题。存储读取失败继续向外抛。
     */
    fun readOriginalBytes(fileAttribute: FileAttribute): ByteArray {
        val size = originalFileSize(fileAttribute)
        if (size <= 0L) {
            return ByteArray(0)
        }
        return loadOriginalRange(fileAttribute, Range.full(size)).use { it.readBytes() }
    }

    /**
     * 按 HTTP Range 写出已在内存中的预览字节。
     */
    fun sendRangedBytes(fileAttribute: FileAttribute, bytes: ByteArray) {
        prepareOriginalContext(fileAttribute)
        setInlinePreviewContentType(fileAttribute.suffix.orEmpty(), fileAttribute.fileName)
        val total = bytes.size.toLong()
        val range = resolveHttpRange(total)
        val slice = if (range.isPartialContent()) {
            bytes.copyOfRange(range.start.toInt(), (range.end + 1).toInt())
        } else {
            bytes
        }
        val name = fileAttribute.fileName?.trim()?.takeIf { it.isNotEmpty() } ?: "preview.pdf"
        val stream = ArtifactInputStream(ByteArrayInputStream(slice), range)
        val resource = ArtifactResource(stream, name)
        resource.contentType = MediaPreviewContentType.fromSuffix(fileAttribute.suffix.orEmpty())
            ?: "application/pdf"
        resource.useDisposition = false
        artifactResourceWriter.write(resource)
    }

    private fun resolveHttpRange(total: Long): Range {
        val request = HttpContextHolder.getRequestOrNull() ?: return Range.full(total)
        return try {
            HttpRangeUtils.resolveRange(request, total)
        } catch (_: IllegalArgumentException) {
            throw ErrorCodeException(
                status = HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                messageCode = CommonMessageCode.REQUEST_RANGE_INVALID,
            )
        }
    }

    private fun openOriginal(fileAttribute: FileAttribute): Pair<ArtifactInfo, RepositoryDetail> {
        val artifactInfo = requireArtifactInfo(fileAttribute)
        prepareOriginalContext(fileAttribute)
        val repoDetail = ArtifactContextHolder.getRepoDetail()
            ?: throw PreviewNotFoundException(
                PreviewMessageCode.PREVIEW_REPO_NOT_FOUND,
                "${artifactInfo.projectId}|${artifactInfo.repoName}"
            )
        return artifactInfo to repoDetail
    }

    private fun requireArtifactInfo(fileAttribute: FileAttribute): ArtifactInfo {
        val projectId = fileAttribute.projectId
        val repoName = fileAttribute.repoName
        val artifactUri = fileAttribute.artifactUri
        if (projectId.isNullOrBlank() || repoName.isNullOrBlank() || artifactUri.isNullOrBlank()) {
            throw PreviewInvalidException(
                PreviewMessageCode.PREVIEW_PARAMETER_INVALID,
                "projectId/repoName/artifactUri"
            )
        }
        return ArtifactInfo(projectId, repoName, artifactUri)
    }

    /**
     * 输出文件内容
     */
    fun sendFileAsResponse(fileAttribute: FileAttribute, previewFileCacheInfo: PreviewFileCacheInfo) {
        val artifactInfo = ArtifactInfo(
            previewFileCacheInfo.projectId,
            previewFileCacheInfo.repoName,
            previewFileCacheInfo.fullPath
        )
        setFileTransferAttribute(artifactInfo, fileAttribute, false)
        setInlinePreviewContentType(FileUtils.suffixFromFileName(previewFileCacheInfo.fullPath), fileAttribute.fileName)
        val node = ArtifactContextHolder.getNodeDetail(artifactInfo)
        val context = ArtifactDownloadContext(useDisposition = false)
        if (node == null && context.repositoryDetail.category == RepositoryCategory.LOCAL) {
            throw PreviewNotFoundException(
                PreviewMessageCode.PREVIEW_NODE_NOT_FOUND,
                "${artifactInfo.projectId}|${artifactInfo.repoName}|${artifactInfo.getArtifactFullPath()}"
            )
        }
        repository.download(context)
    }

    fun download(fileAttribute: FileAttribute): DownloadResult? {
        val result: DownloadResult? = if (fileAttribute.storageType == 1) {
            downloadUtils.downLoad(fileAttribute, config)
        } else {
            downloadFromRepo(fileAttribute)
        }
        fileAttribute.originFilePath = result?.filePath
        return result
    }

    fun downloadFromRepo(fileAttribute: FileAttribute): DownloadResult? {
        val artifactInfo = requireArtifactInfo(fileAttribute)
        setFileTransferAttribute(artifactInfo, fileAttribute, true)
        val context = ArtifactDownloadContext()
        return if (context.repositoryDetail.type == RepositoryType.DRIVE) {
            downloadFromDriveRepo(context)
        } else {
            downloadFromNodeRepo(artifactInfo, context)
        }
    }

    private fun downloadFromNodeRepo(
        artifactInfo: ArtifactInfo,
        context: ArtifactDownloadContext,
    ): DownloadResult? {
        val node = ArtifactContextHolder.getNodeDetail(artifactInfo)
        if (node == null && context.repositoryDetail.category == RepositoryCategory.LOCAL) {
            throw PreviewNotFoundException(
                PreviewMessageCode.PREVIEW_NODE_NOT_FOUND,
                "${artifactInfo.projectId}|${artifactInfo.repoName}|${artifactInfo.getArtifactFullPath()}"
            )
        }
        val result = downloadArtifactToTempFile(context)
        if (result.code != DownloadResult.CODE_FAIL) {
            result.md5 = node!!.md5
            result.size = node.size
        }
        return result
    }

    private fun downloadFromDriveRepo(context: ArtifactDownloadContext): DownloadResult {
        val result = downloadArtifactToTempFile(context)
        if (result.code != DownloadResult.CODE_FAIL) {
            fillDriveDownloadMetadata(result)
        }
        return result
    }

    private fun downloadArtifactToTempFile(context: ArtifactDownloadContext): DownloadResult {
        val result = DownloadResult()
        try {
            repository.download(context)
            val request: HttpServletRequest = HttpContextHolder.getRequest()
            result.filePath = request.getAttribute(PREVIEW_TMP_FILE_SAVE_PATH)?.toString()
        } catch (e: Exception) {
            result.apply {
                code = DownloadResult.CODE_FAIL
                msg = "Download Faile from bkrepo,$e"
            }
        }
        return result
    }

    private fun fillDriveDownloadMetadata(result: DownloadResult) {
        val filePath = result.filePath ?: return
        val md5AndSize = FileUtils.getFileMd5AndSize(filePath)
        result.md5 = md5AndSize?.first
        result.size = md5AndSize?.second ?: 0
    }

    fun upload(fileAttribute: FileAttribute, sourcePath: String): NodeDetail {
        val file = File(sourcePath)
        require(file.exists()) { "The file does not exist, $sourcePath" }
        // 新版本统一保存在自定义仓库中
        var projectId = config.projectId
        val repoName = config.repoName

        // 多租户模式下，启动时没创建仓库、项目
        if (enableMultiTenant.enabled) {
            createIfAbsentService.createProjectIfAbsent()
            createIfAbsentService.createRepoIfAbsent()
            projectId = "${ProjectServiceHelper.getTenantId()}.$projectId"
        }

        val artifactInfo = ArtifactInfo(projectId!!, repoName!!, buildArtifactUri(fileAttribute))
        setFileTransferAttribute(artifactInfo, fileAttribute, true)
        val artifactFile = ArtifactFileFactory.build(file.inputStream(), file.length())
        val context = ArtifactUploadContext(artifactFile)

        repository.upload(context)

        val nodeDetail: NodeDetail = context.getAttribute(PREVIEW_NODE_DETAIL)!!
        return nodeDetail
    }

    private fun setInlinePreviewContentType(suffix: String, fileName: String?) {
        val contentType = MediaPreviewContentType.fromSuffix(suffix)
        if (contentType != null) {
            val request = HttpContextHolder.getRequest()
            request.setAttribute(PREVIEW_RESPONSE_CONTENT_TYPE, contentType)
            val inlineName = fileName?.trim()?.takeIf { it.isNotEmpty() }
            if (inlineName != null && request.getAttribute(PREVIEW_RESPONSE_FILENAME) == null) {
                request.setAttribute(PREVIEW_RESPONSE_FILENAME, inlineName)
            }
        }
    }

    /**
     * 把project、repo等信息设置到request域，上传、下载要用
     */
    private fun setFileTransferAttribute(
        artifactInfo: ArtifactInfo,
        fileAttribute: FileAttribute,
        toFile: Boolean
    ) {
        val request: HttpServletRequest = HttpContextHolder.getRequest()
        val repoDetail = repositoryService.getRepoDetail(
            artifactInfo.projectId,
            artifactInfo.repoName,
        ) ?: throw PreviewNotFoundException(
            PreviewMessageCode.PREVIEW_REPO_NOT_FOUND,
            "${artifactInfo.projectId}|${artifactInfo.repoName}"
        )
        request.setAttribute(ARTIFACT_INFO_KEY, artifactInfo)
        request.setAttribute(REPO_KEY, repoDetail)

        val fileTmpPath = downloadUtils.getRelFilePath(fileAttribute.fileName, fileAttribute.suffix!!, config.fileDir)
        request.setAttribute(PREVIEW_TMP_FILE_SAVE_PATH, fileTmpPath)
        request.setAttribute(PREVIEW_ARTIFACT_TO_FILE, toFile)
    }

    /**
     * 制品uri：/date/uuid/fileName
     */
    private fun buildArtifactUri(fileAttribute: FileAttribute): String {
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        return if (fileAttribute.storageType == 0)
            "/convert/$date/${UUID.randomUUID()}/${fileAttribute.convertFileName}"
        else
            "/$date/${UUID.randomUUID()}/${fileAttribute.convertFileName}"
    }
}
