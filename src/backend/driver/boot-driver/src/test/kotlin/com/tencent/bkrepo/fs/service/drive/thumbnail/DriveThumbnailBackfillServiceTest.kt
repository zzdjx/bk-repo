package com.tencent.bkrepo.fs.service.drive.thumbnail

import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.configuration.RepositoryConfiguration
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode.Companion.TYPE_FILE
import com.tencent.bkrepo.common.metadata.service.repo.RRepositoryService
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.repository.drive.RDriveNodeDao
import com.tencent.bkrepo.fs.server.request.drive.DriveThumbnailBackfillRequest
import com.tencent.bkrepo.fs.server.response.drive.DriveNode
import com.tencent.bkrepo.fs.server.service.drive.DriveFileOperationService
import com.tencent.bkrepo.fs.server.service.drive.DriveFilePathInfo
import com.tencent.bkrepo.fs.server.service.drive.DrivePathResolveService
import com.tencent.bkrepo.fs.server.service.drive.DriveUploadService
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ArtifactThumbnailGenerateService
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.DriveThumbnailBackfillLock
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.DriveThumbnailBackfillService
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ThumbnailGenerateResult
import com.tencent.bkrepo.fs.server.utils.DriveNodeQueryHelper
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.LocalDateTime

@DisplayName("Drive 作品缩略图回填")
class DriveThumbnailBackfillServiceTest {

    private val properties = DriveThumbnailProperties().apply {
        enabled = true
        timeout = Duration.ofSeconds(5)
        backfillRepoName = REPO_NAME
    }
    private val lock: DriveThumbnailBackfillLock = mock()
    private val repositoryService: RRepositoryService = mock()
    private val driveNodeDao: RDriveNodeDao = mock()
    private val drivePathResolveService: DrivePathResolveService = mock()
    private val driveFileOperationService: DriveFileOperationService = mock()
    private val generateService: ArtifactThumbnailGenerateService = mock()
    private val driveUploadService: DriveUploadService = mock()

    private val service = DriveThumbnailBackfillService(
        properties = properties,
        lock = lock,
        repositoryService = repositoryService,
        driveNodeDao = driveNodeDao,
        drivePathResolveService = drivePathResolveService,
        driveFileOperationService = driveFileOperationService,
        generateService = generateService,
        driveUploadService = driveUploadService,
    )

    @Test
    fun `should reject when thumbnail is disabled`() {
        properties.enabled = false
        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 1)) }
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, exception.messageCode)
        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `should reject missing limit`() {
        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest()) }
        }
        assertEquals(CommonMessageCode.PARAMETER_MISSING, exception.messageCode)
    }

    @Test
    fun `should reject non-positive limit and concurrency`() {
        val limitException = assertThrows(ErrorCodeException::class.java) {
            runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 0)) }
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, limitException.messageCode)

        val concurrencyException = assertThrows(ErrorCodeException::class.java) {
            runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 1, concurrency = 0)) }
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, concurrencyException.messageCode)
    }

    @Test
    fun `should reject maxDurationSeconds out of range`() {
        val low = assertThrows(ErrorCodeException::class.java) {
            runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 1, maxDurationSeconds = 0)) }
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, low.messageCode)

        val high = assertThrows(ErrorCodeException::class.java) {
            val request = DriveThumbnailBackfillRequest(limit = 1, maxDurationSeconds = 301)
            runBlocking { service.backfill(USER_ID, request) }
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, high.messageCode)
    }

    @Test
    fun `should conflict when lock is held`() {
        runBlocking { whenever(lock.tryAcquire(any())).thenReturn(null) }
        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 1)) }
        }
        assertEquals(CommonMessageCode.ACQUIRE_LOCK_FAILED, exception.messageCode)
        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `should not found when specified project has no drive target repo`() {
        runBlocking {
            whenever(lock.tryAcquire(any())).thenReturn(LOCK_OWNER)
            whenever(repositoryService.getRepoDetail(PROJECT_ID, REPO_NAME)).thenReturn(null)
        }
        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking {
                service.backfill(USER_ID, DriveThumbnailBackfillRequest(projectId = PROJECT_ID, limit = 1))
            }
        }
        assertEquals(ArtifactMessageCode.REPOSITORY_NOT_FOUND, exception.messageCode)
        assertEquals(HttpStatus.NOT_FOUND, exception.status)
        runBlocking { verify(lock).release(LOCK_OWNER) }
    }

    @Test
    fun `should return zeros when no matching repos exist`() {
        runBlocking {
            whenever(lock.tryAcquire(any())).thenReturn(LOCK_OWNER)
            whenever(repositoryService.listRepoPageByType(eq(RepositoryType.DRIVE.name), eq(0), any()))
                .thenReturn(Page(0, 100, 0, emptyList()))
        }
        val result = runBlocking { service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 10)) }
        assertEquals(0, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.failed)
        runBlocking { verify(lock).release(LOCK_OWNER) }
    }

    @Test
    fun `dry-run counts missing thumbnails and skips skipTypes 3d and existing thumbs`() {
        val missing = artifactNode(ino = 10, type = ArtifactThumbnailTypes.TYPE_IMAGE)
        val partial = artifactNode(
            ino = 11,
            type = ArtifactThumbnailTypes.TYPE_PDF,
            extra = listOf(TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL, value = "/x.jpg")),
        )
        val existing = artifactNode(
            ino = 12,
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            extra = listOf(
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL, value = "/a.jpg"),
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO, value = "99"),
            ),
        )
        val code = artifactNode(ino = 13, type = ArtifactThumbnailTypes.TYPE_CODE)
        val threeD = artifactNode(ino = 14, type = ArtifactThumbnailTypes.TYPE_3D)
        stubSingleRepoScan(listOf(missing, partial, existing, code, threeD))

        val result = runBlocking {
            service.backfill(USER_ID, DriveThumbnailBackfillRequest(projectId = PROJECT_ID, limit = 10, dryRun = true))
        }
        assertEquals(2, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.failed)
        runBlocking { verify(generateService, never()).generateWaiting(any(), any(), any(), any()) }
    }

    @Test
    fun `force dry-run includes existing thumbs of selected types`() {
        val missingHtml = artifactNode(ino = 10, type = ArtifactThumbnailTypes.TYPE_HTML)
        val existingHtml = artifactNode(
            ino = 11,
            type = ArtifactThumbnailTypes.TYPE_HTML,
            extra = listOf(
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL, value = "/a.jpg"),
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO, value = "99"),
            ),
        )
        val existingImage = artifactNode(
            ino = 12,
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            extra = listOf(
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL, value = "/b.jpg"),
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO, value = "100"),
            ),
        )
        stubSingleRepoScan(listOf(missingHtml, existingHtml, existingImage))

        val result = runBlocking {
            service.backfill(
                USER_ID,
                DriveThumbnailBackfillRequest(
                    projectId = PROJECT_ID,
                    limit = 10,
                    dryRun = true,
                    force = true,
                    artifactTypes = listOf("HTML"),
                ),
            )
        }
        assertEquals(2, result.attempted)
        runBlocking { verify(generateService, never()).generateWaiting(any(), any(), any(), any()) }
    }

    @Test
    fun `should reject empty artifactTypes`() {
        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking {
                service.backfill(USER_ID, DriveThumbnailBackfillRequest(limit = 1, artifactTypes = emptyList()))
            }
        }
        assertEquals(CommonMessageCode.PARAMETER_INVALID, exception.messageCode)
    }

    @Test
    fun `should stop at limit even if more missing thumbnails remain`() {
        stubSingleRepoScan(
            listOf(
                artifactNode(ino = 1, type = ArtifactThumbnailTypes.TYPE_IMAGE),
                artifactNode(ino = 2, type = ArtifactThumbnailTypes.TYPE_IMAGE),
                artifactNode(ino = 3, type = ArtifactThumbnailTypes.TYPE_IMAGE),
            ),
        )
        val result = runBlocking {
            service.backfill(USER_ID, DriveThumbnailBackfillRequest(projectId = PROJECT_ID, limit = 2, dryRun = true))
        }
        assertEquals(2, result.attempted)
    }

    @Test
    fun `should not generate when remaining duration is not greater than thumbnail timeout`() {
        stubSingleRepoScan(listOf(artifactNode(ino = 1, type = ArtifactThumbnailTypes.TYPE_IMAGE)))
        val result = runBlocking {
            service.backfill(
                USER_ID,
                DriveThumbnailBackfillRequest(projectId = PROJECT_ID, limit = 10, maxDurationSeconds = 1),
            )
        }
        assertEquals(0, result.attempted)
        runBlocking { verify(generateService, never()).generateWaiting(any(), any(), any(), any()) }
    }

    @Test
    fun `dry-run attempted matches would-process duration gate`() {
        stubSingleRepoScan(listOf(artifactNode(ino = 1, type = ArtifactThumbnailTypes.TYPE_IMAGE)))
        val result = runBlocking {
            service.backfill(
                USER_ID,
                DriveThumbnailBackfillRequest(
                    projectId = PROJECT_ID,
                    limit = 10,
                    maxDurationSeconds = 1,
                    dryRun = true,
                ),
            )
        }
        assertEquals(0, result.attempted)
        runBlocking { verify(generateService, never()).generateWaiting(any(), any(), any(), any()) }
    }

    @Test
    fun `should count generate success and failure`() {
        val successNode = artifactNode(ino = 21, type = ArtifactThumbnailTypes.TYPE_IMAGE, name = "ok.png")
        val failNode = artifactNode(ino = 22, type = ArtifactThumbnailTypes.TYPE_IMAGE, name = "bad.png")
        stubSingleRepoScan(listOf(successNode, failNode))
        runBlocking {
            whenever(
                drivePathResolveService.resolveFilePathInfoByIno(PROJECT_ID, REPO_NAME, successNode.ino),
            ).thenReturn(DriveFilePathInfo("/ok.png", successNode))
            whenever(
                drivePathResolveService.resolveFilePathInfoByIno(PROJECT_ID, REPO_NAME, failNode.ino),
            ).thenReturn(DriveFilePathInfo("/bad.png", failNode))
            whenever(driveFileOperationService.read(any(), any(), anyOrNull())).thenAnswer {
                ArtifactInputStream(byteArrayOf(1, 2, 3, 4).inputStream(), Range.full(4))
            }
            whenever(generateService.generateWaiting(any(), any(), any(), any()))
                .thenReturn(ThumbnailGenerateResult.Success)
                .thenReturn(ThumbnailGenerateResult.Failed("timeout"))
            whenever(driveUploadService.persistThumbnail(any(), any(), any(), any(), eq(false)))
                .thenReturn(driveNodeFrom(successNode, withThumb = true))
        }

        val result = runBlocking {
            service.backfill(USER_ID, DriveThumbnailBackfillRequest(projectId = PROJECT_ID, limit = 10))
        }
        assertEquals(2, result.attempted)
        assertEquals(1, result.succeeded)
        assertEquals(1, result.failed)
        runBlocking { verify(lock).release(LOCK_OWNER) }
        runBlocking {
            verify(driveUploadService, times(1)).persistThumbnail(any(), any(), any(), any(), eq(false))
        }
    }

    @Test
    fun `lock ttl is duration plus five seconds`() {
        runBlocking {
            whenever(lock.tryAcquire(any())).thenReturn(LOCK_OWNER)
            whenever(repositoryService.getRepoDetail(PROJECT_ID, REPO_NAME)).thenReturn(driveRepo())
            whenever(driveNodeDao.searchArtifactPageByIno(eq(PROJECT_ID), eq(REPO_NAME), any(), anyOrNull()))
                .thenReturn(emptyList())
        }
        runBlocking {
            service.backfill(
                USER_ID,
                DriveThumbnailBackfillRequest(projectId = PROJECT_ID, limit = 1, maxDurationSeconds = 60),
            )
        }
        runBlocking { verify(lock).tryAcquire(65_000) }
    }

    private fun stubSingleRepoScan(nodes: List<TDriveNode>) = runBlocking {
        whenever(lock.tryAcquire(any())).thenReturn(LOCK_OWNER)
        whenever(repositoryService.getRepoDetail(PROJECT_ID, REPO_NAME)).thenReturn(driveRepo())
        whenever(
            driveNodeDao.searchArtifactPageByIno(eq(PROJECT_ID), eq(REPO_NAME), any(), isNull()),
        ).thenReturn(nodes)
        val lastIno = nodes.last().ino
        whenever(
            driveNodeDao.searchArtifactPageByIno(eq(PROJECT_ID), eq(REPO_NAME), any(), eq(lastIno)),
        ).thenReturn(emptyList())
    }

    private fun driveNodeFrom(node: TDriveNode, withThumb: Boolean): DriveNode {
        val metadata = if (withThumb) {
            node.metadata.orEmpty() + listOf(
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL, value = "/.x.thumb.jpg"),
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO, value = "100"),
            )
        } else {
            node.metadata
        }
        val now = LocalDateTime.now()
        return DriveNode(
            id = node.id ?: "id",
            createdBy = USER_ID,
            createdDate = now,
            lastModifiedBy = USER_ID,
            lastModifiedDate = now,
            mtime = 0,
            ctime = 0,
            atime = 0,
            projectId = node.projectId,
            repoName = node.repoName,
            ino = node.ino,
            realIno = node.ino,
            parent = node.parent,
            name = node.name,
            size = node.size,
            mode = 0,
            type = TYPE_FILE,
            nlink = 1,
            uid = 0,
            gid = 0,
            rdev = 0,
            flags = 0,
            metadata = metadata,
        )
    }

    private fun artifactNode(
        ino: Long,
        type: String,
        name: String = "$ino.bin",
        extra: List<TMetadata> = emptyList(),
    ): TDriveNode {
        val now = LocalDateTime.now()
        return TDriveNode(
            id = "id-$ino",
            createdBy = USER_ID,
            createdDate = now,
            lastModifiedBy = USER_ID,
            lastModifiedDate = now,
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            ino = ino,
            realIno = ino,
            parent = DriveNodeQueryHelper.ROOT_INO,
            name = name,
            size = 4,
            mode = 0,
            type = TYPE_FILE,
            nlink = 1,
            uid = 0,
            gid = 0,
            rdev = 0,
            flags = 0,
            mtime = 0,
            ctime = 0,
            atime = 0,
            metadata = mutableListOf(
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = name),
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_TYPE, value = type),
            ).also { it.addAll(extra) },
        )
    }

    private fun driveRepo() = RepositoryDetail(
        projectId = PROJECT_ID,
        name = REPO_NAME,
        type = RepositoryType.DRIVE,
        category = RepositoryCategory.LOCAL,
        public = false,
        description = null,
        configuration = RepositoryConfiguration(),
        storageCredentials = null,
        oldCredentialsKey = null,
        createdBy = USER_ID,
        createdDate = "2026-09-18T00:00:00",
        lastModifiedBy = USER_ID,
        lastModifiedDate = "2026-09-18T00:00:00",
        quota = null,
        used = null,
    )

    companion object {
        private const val PROJECT_ID = "demo"
        private const val REPO_NAME = "imate-artifact"
        private const val USER_ID = "admin"
        private const val LOCK_OWNER = "owner-1"
    }
}
