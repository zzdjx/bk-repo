package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode
import com.tencent.bkrepo.common.metadata.service.drive.DriveNodeService
import com.tencent.bkrepo.common.metadata.service.node.NodeService
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import com.tencent.bkrepo.preview.pojo.FileAttribute
import com.tencent.bkrepo.preview.pojo.FileType
import com.tencent.bkrepo.repository.pojo.node.NodeDetail
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PDF 作品名来源")
class PdfPreviewArtifactNameSourceTest {

    private val repositoryService = mockk<RepositoryService>()
    private val driveNodeService = mockk<DriveNodeService>()
    private val nodeService = mockk<NodeService>()
    private val source = PdfPreviewArtifactNameSource(repositoryService, driveNodeService, nodeService)

    @Test
    fun `drive node uses IMATE_ARTIFACT_NAME`() {
        val repo = mockk<RepositoryDetail>()
        every { repo.type } returns RepositoryType.DRIVE
        every { repositoryService.getRepoDetail("p", "drive", null) } returns repo
        val node = mockk<TDriveNode>()
        every { node.metadata } returns mutableListOf(
            TMetadata(key = "OTHER", value = "忽略"),
            TMetadata(key = "IMATE_ARTIFACT_NAME", value = "周报"),
        )
        every { driveNodeService.resolveFileNode("p", "drive", "/works/a.pdf") } returns node

        assertEquals("周报", source.read(localPdf(repoName = "drive")))
    }

    @Test
    fun `drive node without the key returns null`() {
        val repo = mockk<RepositoryDetail>()
        every { repo.type } returns RepositoryType.DRIVE
        every { repositoryService.getRepoDetail("p", "drive", null) } returns repo
        val node = mockk<TDriveNode>()
        every { node.metadata } returns mutableListOf(TMetadata(key = "imate_artifact_name", value = "周报"))
        every { driveNodeService.resolveFileNode("p", "drive", "/works/a.pdf") } returns node

        assertNull(source.read(localPdf(repoName = "drive")))
    }

    @Test
    fun `generic node reads the same metadata key`() {
        val repo = mockk<RepositoryDetail>()
        every { repo.type } returns RepositoryType.GENERIC
        every { repositoryService.getRepoDetail("p", "generic", null) } returns repo
        val detail = mockk<NodeDetail>()
        every { detail.metadata } returns mapOf("IMATE_ARTIFACT_NAME" to "周报")
        every { nodeService.getNodeDetail(any<ArtifactInfo>(), null) } returns detail

        assertEquals("周报", source.read(localPdf(repoName = "generic")))
        verify(exactly = 0) { driveNodeService.resolveFileNode(any(), any(), any()) }
    }
}

private fun localPdf(repoName: String) = FileAttribute(
    storageType = 0,
    projectId = "p",
    repoName = repoName,
    artifactUri = "/works/a.pdf",
    fileName = "a.pdf",
    suffix = "pdf",
    type = FileType.PDF,
)
