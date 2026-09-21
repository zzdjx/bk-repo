package com.tencent.bkrepo.fs.server.service.drive

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.artifact.path.PathUtils
import com.tencent.bkrepo.fs.server.constant.DriveUploadConstants.DEFAULT_DIRECTORY_MODE
import com.tencent.bkrepo.fs.server.constant.DriveUploadConstants.DEFAULT_FILE_MODE
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode.Companion.TYPE_DIRECTORY
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode.Companion.TYPE_FILE
import com.tencent.bkrepo.fs.server.repository.drive.RDriveNodeDao
import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.fs.server.request.drive.DriveBlockWriteRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeCreateRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeDeleteRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeUploadRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeUpdateRequest
import com.tencent.bkrepo.fs.server.response.drive.DriveNode
import com.tencent.bkrepo.fs.server.response.drive.toDriveNode
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.DriveArtifactThumbnailBinder
import com.tencent.bkrepo.fs.server.storage.CoArtifactFile
import com.tencent.bkrepo.fs.server.storage.CoArtifactFileFactory
import com.tencent.bkrepo.fs.server.utils.DriveNodeQueryHelper
import com.tencent.bkrepo.fs.server.utils.DriveServiceUtils
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class DriveUploadService(
    private val driveNodeService: DriveNodeService,
    private val driveNodeDao: RDriveNodeDao,
    private val driveFileOperationService: DriveFileOperationService,
    private val driveRepositoryInitService: DriveRepositoryInitService,
    private val driveInoAllocator: DriveInoAllocator,
    private val driveArtifactThumbnailBinder: DriveArtifactThumbnailBinder,
) {

    suspend fun checkUploadTargetBeforeBody(uploadRequest: DriveNodeUploadRequest, userId: String) {
        with(uploadRequest) {
            DriveServiceUtils.validateProjectRepo(projectId, repoName)
            validateUploadPath(fullPath)
            driveRepositoryInitService.ensureInitialized(projectId, repoName, userId)
            val parentIno = lookupExistingParentIno(projectId, repoName, fullPath) ?: return
            val fileName = PathUtils.resolveName(PathUtils.normalizeFullPath(fullPath))
            driveNodeDao.findCurrentNode(projectId, repoName, parentIno, fileName)?.let { existing ->
                validateTargetNameConflict(existing, fileName, overwrite)
            }
        }
    }

    suspend fun uploadCompleteFile(
        uploadRequest: DriveNodeUploadRequest,
        artifactFile: CoArtifactFile,
        metadata: MutableList<TMetadata>?,
        userId: String,
    ): DriveNode {
        with(uploadRequest) {
            verifySha256(expectedSha256, artifactFile.getFileSha256())
            val (parentIno, fileName) = resolveParentIno(projectId, repoName, fullPath, userId)
            val size = artifactFile.getSize()
            val persistedMetadata = ArtifactThumbnailTypes.withoutThumbnailKeys(metadata)
            val fileNode = prepareFileNode(
                projectId = projectId,
                repoName = repoName,
                parentIno = parentIno,
                name = fileName,
                size = size,
                metadata = persistedMetadata,
                overwrite = overwrite,
            )
            // cache store 会 move 接收临时文件，必须先渲染缩略图
            val thumbnailJpeg = driveArtifactThumbnailBinder.render(fullPath, persistedMetadata, artifactFile)
            try {
                writeFileContent(artifactFile, fileNode, userId)
                val result = persistThumbnail(fileNode, fullPath, thumbnailJpeg, userId)
                logger.info("Upload drive file[$projectId/$repoName$fullPath] ino[${result.ino}] size[$size] success.")
                return result
            } finally {
                thumbnailJpeg?.let { Files.deleteIfExists(it) }
            }
        }
    }

    private fun validateUploadPath(fullPath: String) {
        val fileName = PathUtils.resolveName(fullPath)
        if (fileName.isBlank()) {
            throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "fullPath")
        }
    }

    private fun verifySha256(expectedSha256: String?, actualSha256: String) {
        if (expectedSha256.isNullOrBlank()) {
            return
        }
        if (!expectedSha256.equals(actualSha256, ignoreCase = true)) {
            throw ErrorCodeException(ArtifactMessageCode.DIGEST_CHECK_FAILED, "sha256")
        }
    }

    private suspend fun lookupExistingParentIno(
        projectId: String,
        repoName: String,
        fullPath: String,
    ): Long? {
        val normalizedPath = PathUtils.normalizeFullPath(fullPath)
        val parentPath = PathUtils.resolveParent(normalizedPath)
        if (PathUtils.isRoot(parentPath)) {
            return DriveNodeQueryHelper.ROOT_INO
        }
        val segments = parentPath.removePrefix(PathUtils.ROOT)
            .split(PathUtils.UNIX_SEPARATOR)
            .filter { it.isNotBlank() }
        var parentIno = DriveNodeQueryHelper.ROOT_INO
        for (segment in segments) {
            val existing = driveNodeDao.findCurrentNode(projectId, repoName, parentIno, segment)
                ?: return null
            if (existing.type != TYPE_DIRECTORY) {
                throw ErrorCodeException(ArtifactMessageCode.NODE_CONFLICT, segment)
            }
            parentIno = existing.ino
        }
        return parentIno
    }

    private fun validateTargetNameConflict(
        existing: TDriveNode,
        name: String,
        overwrite: Boolean,
    ) {
        if (existing.type == TYPE_DIRECTORY) {
            throw ErrorCodeException(ArtifactMessageCode.NODE_CONFLICT, name)
        }
        if (!overwrite) {
            throw ErrorCodeException(ArtifactMessageCode.NODE_EXISTED, name)
        }
    }

    private suspend fun resolveParentIno(
        projectId: String,
        repoName: String,
        fullPath: String,
        operator: String,
    ): Pair<Long, String> {
        val normalizedPath = PathUtils.normalizeFullPath(fullPath)
        val fileName = PathUtils.resolveName(normalizedPath)
        val parentPath = PathUtils.resolveParent(normalizedPath)
        if (PathUtils.isRoot(parentPath)) {
            return DriveNodeQueryHelper.ROOT_INO to fileName
        }
        val parentIno = ensureDirectoryPath(projectId, repoName, parentPath, operator)
        return parentIno to fileName
    }

    private suspend fun ensureDirectoryPath(
        projectId: String,
        repoName: String,
        directoryPath: String,
        operator: String,
    ): Long {
        val normalizedPath = PathUtils.normalizeFullPath(directoryPath)
        if (PathUtils.isRoot(normalizedPath)) {
            return DriveNodeQueryHelper.ROOT_INO
        }
        val segments = normalizedPath.removePrefix(PathUtils.ROOT)
            .split(PathUtils.UNIX_SEPARATOR)
            .filter { it.isNotBlank() }
        var parentIno = DriveNodeQueryHelper.ROOT_INO
        for (segment in segments) {
            val existing = driveNodeDao.findCurrentNode(projectId, repoName, parentIno, segment)
            parentIno = if (existing != null) {
                if (existing.type != TYPE_DIRECTORY) {
                    throw ErrorCodeException(ArtifactMessageCode.NODE_CONFLICT, segment)
                }
                existing.ino
            } else {
                createDirectoryNode(projectId, repoName, parentIno, segment).ino
            }
        }
        return parentIno
    }

    private suspend fun prepareFileNode(
        projectId: String,
        repoName: String,
        parentIno: Long,
        name: String,
        size: Long,
        metadata: MutableList<TMetadata>?,
        overwrite: Boolean,
    ): DriveNode {
        return createDriveNode(
            projectId = projectId,
            repoName = repoName,
            parentIno = parentIno,
            name = name,
            overwrite = overwrite,
            buildCreateRequest = { ino ->
                DriveNodeCreateRequest(
                    projectId = projectId,
                    repoName = repoName,
                    parent = parentIno,
                    name = name,
                    ino = ino,
                    targetIno = null,
                    size = size,
                    mode = DEFAULT_FILE_MODE,
                    type = TYPE_FILE,
                    nlink = 1,
                    uid = 0,
                    gid = 0,
                    rdev = 0,
                    flags = 0,
                    metadata = metadata,
                )
            },
            overwriteNode = { existing -> overwriteFileNode(existing, size, metadata) },
        )
    }

    private suspend fun createDirectoryNode(
        projectId: String,
        repoName: String,
        parentIno: Long,
        name: String,
    ): DriveNode {
        return createDriveNode(
            projectId = projectId,
            repoName = repoName,
            parentIno = parentIno,
            name = name,
            overwrite = false,
            reuseExistingDirectory = true,
            buildCreateRequest = { ino ->
                DriveNodeCreateRequest(
                    projectId = projectId,
                    repoName = repoName,
                    parent = parentIno,
                    name = name,
                    ino = ino,
                    targetIno = null,
                    size = 0,
                    mode = DEFAULT_DIRECTORY_MODE,
                    type = TYPE_DIRECTORY,
                    nlink = 2,
                    uid = 0,
                    gid = 0,
                    rdev = 0,
                    flags = 0,
                )
            },
            overwriteNode = { existing ->
                if (existing.type == TYPE_DIRECTORY) {
                    existing.toDriveNode()
                } else {
                    throw ErrorCodeException(ArtifactMessageCode.NODE_CONFLICT, name)
                }
            },
        )
    }

    /**
     * 创建节点前分别检查 parent+name 与 ino 冲突：
     * - parent+name 已存在时，根据 overwrite 决定覆盖或拒绝
     * - ino 已存在时，重新分配 identity 后重试
     */
    private suspend fun createDriveNode(
        projectId: String,
        repoName: String,
        parentIno: Long,
        name: String,
        overwrite: Boolean,
        reuseExistingDirectory: Boolean = false,
        buildCreateRequest: (Long) -> DriveNodeCreateRequest,
        overwriteNode: suspend (TDriveNode) -> DriveNode,
    ): DriveNode {
        resolveExistingNode(
            projectId, repoName, parentIno, name, overwrite, reuseExistingDirectory, overwriteNode,
        )?.let { return it }

        repeat(MAX_CREATE_RETRY) {
            val ino = driveInoAllocator.allocate(projectId, repoName)
            try {
                return driveNodeService.createNode(buildCreateRequest(ino), clientId = null)
            } catch (e: ErrorCodeException) {
                if (e.messageCode != ArtifactMessageCode.NODE_EXISTED) {
                    throw e
                }
                resolveExistingNode(
                    projectId, repoName, parentIno, name, overwrite, reuseExistingDirectory, overwriteNode,
                )?.let { return it }
            }
        }
        throw DuplicateKeyException("Failed to create drive node[$projectId/$repoName/$parentIno/$name]")
    }

    private suspend fun resolveExistingNode(
        projectId: String,
        repoName: String,
        parentIno: Long,
        name: String,
        overwrite: Boolean,
        reuseExistingDirectory: Boolean,
        overwriteNode: suspend (TDriveNode) -> DriveNode,
    ): DriveNode? {
        val existing = driveNodeDao.findCurrentNode(projectId, repoName, parentIno, name) ?: return null
        if (!reuseExistingDirectory || existing.type != TYPE_DIRECTORY) {
            validateTargetNameConflict(existing, name, overwrite)
        }
        return overwriteNode(existing)
    }

    private suspend fun overwriteFileNode(
        existingNode: TDriveNode,
        size: Long,
        metadata: MutableList<TMetadata>?,
    ): DriveNode {
        if (existingNode.type == TYPE_DIRECTORY) {
            throw ErrorCodeException(ArtifactMessageCode.NODE_CONFLICT, existingNode.name)
        }
        val parentIno = requireNotNull(existingNode.parent) {
            "Drive file node must have parent ino"
        }
        driveNodeService.delete(
            DriveNodeDeleteRequest(
                projectId = existingNode.projectId,
                repoName = existingNode.repoName,
                ino = existingNode.ino,
            ),
            clientId = null,
        )
        val ino = driveInoAllocator.allocate(existingNode.projectId, existingNode.repoName)
        return driveNodeService.createNode(
            DriveNodeCreateRequest(
                projectId = existingNode.projectId,
                repoName = existingNode.repoName,
                parent = parentIno,
                name = existingNode.name,
                ino = ino,
                targetIno = null,
                size = size,
                mode = DEFAULT_FILE_MODE,
                type = TYPE_FILE,
                nlink = 1,
                uid = 0,
                gid = 0,
                rdev = 0,
                flags = 0,
                metadata = metadata,
            ),
            clientId = null,
        )
    }

    private suspend fun writeFileContent(artifactFile: CoArtifactFile, fileNode: DriveNode, userId: String) {
        driveFileOperationService.write(
            artifactFile = artifactFile,
            request = DriveBlockWriteRequest(
                projectId = fileNode.projectId,
                repoName = fileNode.repoName,
                ino = fileNode.ino,
                offset = 0,
            ),
            user = userId,
        )
    }

    /**
     * 将已渲染的 JPEG 落成 sidecar 并回写源节点双字段。失败时返回原节点。
     * [touchLastModified] 为 false 时只写缩略图元数据，不改源节点更新人、更新时间。
     */
    suspend fun persistThumbnail(
        sourceNode: DriveNode,
        fullPath: String,
        jpeg: Path?,
        userId: String,
        touchLastModified: Boolean = true,
    ): DriveNode {
        if (jpeg == null) {
            return sourceNode
        }
        return try {
            val sidecar = persistThumbnailSidecar(
                projectId = sourceNode.projectId,
                repoName = sourceNode.repoName,
                originalFullPath = fullPath,
                jpeg = jpeg,
                userId = userId,
            )
            writeThumbnailMetadata(sourceNode, sidecar, touchLastModified)
        } catch (e: Exception) {
            logger.warn("Thumbnail sidecar failed for [$fullPath]: ${e.message}")
            sourceNode
        }
    }

    /**
     * 将 JPEG sidecar 落成同目录隐藏文件。节点不写元数据，避免被当成作品或递归生成缩略图。
     */
    private suspend fun persistThumbnailSidecar(
        projectId: String,
        repoName: String,
        originalFullPath: String,
        jpeg: Path,
        userId: String,
    ): ThumbnailSidecar {
        val sidecarName = ArtifactThumbnailTypes.sidecarFileName(originalFullPath.substringAfterLast('/'))
        val parentPath = PathUtils.resolveParent(PathUtils.normalizeFullPath(originalFullPath))
        val sidecarPath = PathUtils.combineFullPath(parentPath, sidecarName)
        val sidecarFile = jpegToArtifactFile(jpeg)
        try {
            val (parentIno, fileName) = resolveParentIno(projectId, repoName, sidecarPath, userId)
            val sidecarNode = prepareFileNode(
                projectId = projectId,
                repoName = repoName,
                parentIno = parentIno,
                name = fileName,
                size = sidecarFile.getSize(),
                metadata = null,
                overwrite = true,
            )
            writeFileContent(sidecarFile, sidecarNode, userId)
            logger.info("Upload drive thumbnail sidecar[$projectId/$repoName$sidecarPath] ino[${sidecarNode.ino}]")
            return ThumbnailSidecar(path = sidecarPath, ino = sidecarNode.ino)
        } finally {
            sidecarFile.close()
        }
    }

    /**
     * 只在源文件写入缩略图路径和 ino。两个字段必须一起写；sidecar 节点本身不写元数据。
     */
    private suspend fun writeThumbnailMetadata(
        sourceNode: DriveNode,
        sidecar: ThumbnailSidecar,
        touchLastModified: Boolean,
    ): DriveNode {
        val withPath = ArtifactThumbnailTypes.upsertMetadata(
            existing = sourceNode.metadata,
            key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL,
            value = sidecar.path,
        )
        val metadata = ArtifactThumbnailTypes.upsertMetadata(
            existing = withPath,
            key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO,
            value = sidecar.ino.toString(),
        )
        return driveNodeService.update(
            DriveNodeUpdateRequest(
                projectId = sourceNode.projectId,
                repoName = sourceNode.repoName,
                ino = sourceNode.ino,
                metadata = metadata,
                touchLastModified = touchLastModified,
            ),
            clientId = null,
        )
    }

    private data class ThumbnailSidecar(
        val path: String,
        val ino: Long,
    )

    private suspend fun jpegToArtifactFile(jpeg: Path): CoArtifactFile {
        val artifactFile = runCatching {
            CoArtifactFileFactory.buildArtifactFile()
        }.getOrElse {
            CoArtifactFileFactory.buildArtifactFileOnNotHttpRequest(null)
        }
        Files.newInputStream(jpeg).use { input ->
            while (true) {
                val chunk = input.readNBytes(JPEG_CHUNK_SIZE)
                if (chunk.isEmpty()) {
                    break
                }
                artifactFile.write(DefaultDataBufferFactory.sharedInstance.wrap(chunk))
            }
        }
        artifactFile.finish()
        return artifactFile
    }

    companion object {
        private const val MAX_CREATE_RETRY = 5
        private const val JPEG_CHUNK_SIZE = 8 * 1024
        private val logger = LoggerFactory.getLogger(DriveUploadService::class.java)
    }
}
