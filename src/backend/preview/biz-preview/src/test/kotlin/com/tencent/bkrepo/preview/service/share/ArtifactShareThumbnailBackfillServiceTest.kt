package com.tencent.bkrepo.preview.service.share

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode
import com.tencent.bkrepo.preview.dao.ArtifactShareDao
import com.tencent.bkrepo.preview.model.TArtifactShare
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareKind
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareResourceType
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareThumbnailBackfillRequest
import com.tencent.bkrepo.preview.pojo.share.ShareVisibility
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@DisplayName("作品分享缩略图回填")
class ArtifactShareThumbnailBackfillServiceTest {

    private val artifactShareDao = mockk<ArtifactShareDao>()
    private val driveShareNodeResolver = mockk<DriveShareNodeResolver>()
    private lateinit var service: ArtifactShareThumbnailBackfillService

    @BeforeEach
    fun setUp() {
        service = ArtifactShareThumbnailBackfillService(artifactShareDao, driveShareNodeResolver)
        every { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) } returns true
        every { artifactShareDao.updateThumbnail(any(), any(), any()) } returns true
    }

    @Test
    fun `should reject missing limit`() {
        val exception = assertThrows(ErrorCodeException::class.java) {
            service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest())
        }
        assertEquals(CommonMessageCode.PARAMETER_MISSING, exception.messageCode)
    }

    @Test
    fun `should reject non-positive limit`() {
        val exception = assertThrows(ErrorCodeException::class.java) {
            service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 0))
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `should reject maxDurationSeconds below minimum`() {
        val exception = assertThrows(ErrorCodeException::class.java) {
            service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1, maxDurationSeconds = 0))
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `should reject maxDurationSeconds above maximum`() {
        val exception = assertThrows(ErrorCodeException::class.java) {
            service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1, maxDurationSeconds = 301))
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `should return zeros when no missing thumbnail shares exist`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns emptyList()

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(0, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `should copy thumbnail fields from source node`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(record("id-1"))
        stubThumbnailSource()

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        verify(exactly = 1) {
            artifactShareDao.updateThumbnailIfAbsent("id-1", THUMBNAIL_INO, THUMBNAIL_PATH)
        }
        verify(exactly = 0) { artifactShareDao.save(any()) }
    }

    @Test
    fun `dry-run counts writable shares without updating`() {
        every {
            artifactShareDao.listMissingThumbnailById(null, 1, PROJECT_ID, REPO_NAME, true)
        } returns listOf(record("id-1"))
        stubThumbnailSource()

        val result = service.backfill(
            USER_ID,
            ArtifactShareThumbnailBackfillRequest(
                projectId = PROJECT_ID,
                repoName = REPO_NAME,
                limit = 1,
                dryRun = true,
            ),
        )

        assertEquals(1, result.attempted)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `should skip when source node has no thumbnail`() {
        val node = mockk<TDriveNode>()
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(record("id-1"))
        stubSourceNode(node)
        every { driveShareNodeResolver.resolveThumbnail(node) } returns null

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `should fail when source node is missing`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(record("id-1"))
        every { driveShareNodeResolver.resolveFileByIno(PROJECT_ID, REPO_NAME, RESOURCE_ID) } returns null

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failed)
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `should continue after one share fails`() {
        every { artifactShareDao.listMissingThumbnailById(null, 2, null, null, true) } returns listOf(
            record("id-1"),
            record("id-2"),
        )
        val node = stubThumbnailSource()
        every { driveShareNodeResolver.resolveFileByIno(PROJECT_ID, REPO_NAME, RESOURCE_ID) } returnsMany listOf(
            null,
            DriveShareNodeInfo(node = node, fullPath = "/a.html"),
        )

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 2))

        assertEquals(2, result.attempted)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failed)
        verify(exactly = 1) {
            artifactShareDao.updateThumbnailIfAbsent("id-2", THUMBNAIL_INO, THUMBNAIL_PATH)
        }
    }

    @Test
    fun `should stop at limit even if more missing shares exist`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(record("id-1"))
        every { driveShareNodeResolver.resolveFileByIno(PROJECT_ID, REPO_NAME, RESOURCE_ID) } returns null

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        verify(exactly = 1) { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) }
    }

    @Test
    fun `should pass project and repo filters to dao`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, PROJECT_ID, REPO_NAME, true) } returns emptyList()

        service.backfill(
            USER_ID,
            ArtifactShareThumbnailBackfillRequest(projectId = PROJECT_ID, repoName = REPO_NAME, limit = 1),
        )

        verify(exactly = 1) { artifactShareDao.listMissingThumbnailById(null, 1, PROJECT_ID, REPO_NAME, true) }
    }

    @Test
    fun `should skip share that already has thumbnail fields`() {
        val filled = record("id-1").copy(thumbnailIno = THUMBNAIL_INO, thumbnailFullPath = THUMBNAIL_PATH)
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(filled)

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        verify(exactly = 0) { driveShareNodeResolver.resolveFileByIno(any(), any(), any()) }
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
        verify(exactly = 0) { artifactShareDao.updateThumbnail(any(), any(), any()) }
    }

    @Test
    fun `force overwrites existing thumbnail fields`() {
        val filled = record("id-1").copy(thumbnailIno = 1L, thumbnailFullPath = "/old.jpg")
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, false) } returns listOf(filled)
        stubThumbnailSource()

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1, force = true))

        assertEquals(1, result.attempted)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        verify(exactly = 1) { artifactShareDao.updateThumbnail("id-1", THUMBNAIL_INO, THUMBNAIL_PATH) }
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
        verify(exactly = 0) { artifactShareDao.save(any()) }
    }

    @Test
    fun `force dry-run includes existing thumbs without updating`() {
        val filled = record("id-1").copy(thumbnailIno = 1L, thumbnailFullPath = "/old.jpg")
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, false) } returns listOf(filled)
        stubThumbnailSource()

        val result = service.backfill(
            USER_ID,
            ArtifactShareThumbnailBackfillRequest(limit = 1, dryRun = true, force = true),
        )

        assertEquals(1, result.attempted)
        assertEquals(1, result.succeeded)
        verify(exactly = 0) { artifactShareDao.updateThumbnail(any(), any(), any()) }
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `should fail when share id is missing`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(record(id = null))

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        assertEquals(1, result.failed)
        verify(exactly = 0) { artifactShareDao.updateThumbnailIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `should fail when update throws`() {
        every { artifactShareDao.listMissingThumbnailById(null, 1, null, null, true) } returns listOf(record("id-1"))
        stubThumbnailSource()
        every { artifactShareDao.updateThumbnailIfAbsent("id-1", THUMBNAIL_INO, THUMBNAIL_PATH) } throws
            RuntimeException("mongo")

        val result = service.backfill(USER_ID, ArtifactShareThumbnailBackfillRequest(limit = 1))

        assertEquals(1, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(1, result.failed)
    }

    private fun stubSourceNode(node: TDriveNode) {
        every {
            driveShareNodeResolver.resolveFileByIno(PROJECT_ID, REPO_NAME, RESOURCE_ID)
        } returns DriveShareNodeInfo(node = node, fullPath = "/a.html")
    }

    private fun stubThumbnailSource(node: TDriveNode = mockk()): TDriveNode {
        stubSourceNode(node)
        every { driveShareNodeResolver.resolveThumbnail(node) } returns ArtifactShareThumbnail(
            ino = THUMBNAIL_INO,
            fullPath = THUMBNAIL_PATH,
        )
        return node
    }

    private fun record(id: String?): TArtifactShare {
        return TArtifactShare(
            id = id,
            shareKind = ArtifactShareKind.MATERIAL,
            resourceType = ArtifactShareResourceType.DRIVE_NODE,
            createdBy = USER_ID,
            createdDate = TIME,
            lastModifiedBy = USER_ID,
            lastModifiedDate = TIME,
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            resourceId = RESOURCE_ID,
            fullPath = "/a.html",
            visibility = ShareVisibility.PUBLIC,
        )
    }

    companion object {
        private const val USER_ID = "admin"
        private const val PROJECT_ID = "p"
        private const val REPO_NAME = "r"
        private const val RESOURCE_ID = 9L
        private const val THUMBNAIL_INO = 3003L
        private const val THUMBNAIL_PATH = "/.a.html.thumb.jpg"
        private val TIME = LocalDateTime.of(2026, 9, 19, 10, 0)
    }
}
