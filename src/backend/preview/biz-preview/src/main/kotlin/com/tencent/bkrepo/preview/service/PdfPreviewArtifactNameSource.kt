package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.metadata.service.drive.DriveNodeService
import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.service.share.DriveShareNodeResolver
import org.springframework.stereotype.Component

/**
 * 打开预览时读取节点上的 IMATE_ARTIFACT_NAME。分享记录里的 artifactName 不参与。
 */
@Component
class PdfPreviewArtifactNameSource(
    private val repositoryService: RepositoryService,
    private val driveNodeService: DriveNodeService,
    private val nodeService: NodeService,
) {

    fun read(fileAttribute: FileAttribute): String? {
        val projectId = fileAttribute.projectId?.trim().orEmpty()
        val repoName = fileAttribute.repoName?.trim().orEmpty()
        val fullPath = fileAttribute.artifactUri?.trim().orEmpty()
        if (projectId.isEmpty() || repoName.isEmpty() || fullPath.isEmpty()) {
            return null
        }
        val repo = repositoryService.getRepoDetail(projectId, repoName) ?: return null
        return if (repo.type == RepositoryType.DRIVE) {
            driveNodeService.resolveFileNode(projectId, repoName, fullPath)
                ?.metadata
                ?.firstOrNull { it.key == METADATA_KEY }
                ?.value
                ?.toString()
        } else {
            nodeService.getNodeDetail(ArtifactInfo(projectId, repoName, fullPath))
                ?.metadata
                ?.get(METADATA_KEY)
                ?.toString()
        }
    }

    private companion object {
        const val METADATA_KEY = DriveShareNodeResolver.METADATA_ARTIFACT_NAME
    }
}
