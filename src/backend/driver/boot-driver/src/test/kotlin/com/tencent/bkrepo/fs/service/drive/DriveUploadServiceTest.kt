package com.tencent.bkrepo.fs.service.drive

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode.Companion.TYPE_DIRECTORY
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode.Companion.TYPE_FILE
import com.tencent.bkrepo.common.storage.config.StorageProperties
import com.tencent.bkrepo.common.storage.config.UploadProperties
import com.tencent.bkrepo.common.storage.credentials.FileSystemCredentials
import com.tencent.bkrepo.common.storage.monitor.StorageHealthMonitorHelper
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.repository.drive.RDriveNodeDao
import com.tencent.bkrepo.fs.server.request.drive.DriveBlockWriteRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeCreateRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeDeleteRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeUpdateRequest
import com.tencent.bkrepo.fs.server.request.drive.DriveNodeUploadRequest
import com.tencent.bkrepo.fs.server.response.drive.DriveBlockNode
import com.tencent.bkrepo.fs.server.response.drive.DriveNode
import com.tencent.bkrepo.fs.server.service.drive.DriveFileOperationService
import com.tencent.bkrepo.fs.server.service.drive.DriveInoAllocator
import com.tencent.bkrepo.fs.server.service.drive.DriveNodeService
import com.tencent.bkrepo.fs.server.service.drive.DriveRepositoryInitService
import com.tencent.bkrepo.fs.server.service.drive.DriveUploadService
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ArtifactThumbnailGenerateService
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.DriveArtifactThumbnailBinder
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ThumbnailGenerateResult
import com.tencent.bkrepo.fs.server.storage.CoArtifactFile
import com.tencent.bkrepo.fs.server.storage.CoArtifactFileFactory
import com.tencent.bkrepo.fs.server.utils.DriveNodeQueryHelper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@DisplayName("Drive 完整文件上传")
class DriveUploadServiceTest {

    private val driveNodeService: DriveNodeService = mock()
    private val driveNodeDao: RDriveNodeDao = mock()
    private val driveFileOperationService: DriveFileOperationService = mock()
    private val driveRepositoryInitService: DriveRepositoryInitService = mock()
    private val driveInoAllocator: DriveInoAllocator = mock()
    private val driveArtifactThumbnailBinder: DriveArtifactThumbnailBinder = mock()

    private val driveUploadService = DriveUploadService(
        driveNodeService = driveNodeService,
        driveNodeDao = driveNodeDao,
        driveFileOperationService = driveFileOperationService,
        driveRepositoryInitService = driveRepositoryInitService,
        driveInoAllocator = driveInoAllocator,
        driveArtifactThumbnailBinder = driveArtifactThumbnailBinder,
    )

    @Test
    fun `should reject existing file before body read when overwrite is false`() {
        val existingNode = existingFileNode()
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/a.txt",
            overwrite = false,
        )

        runBlocking {
            whenever(driveRepositoryInitService.ensureInitialized(any(), any(), any())).thenReturn(Unit)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DriveNodeQueryHelper.ROOT_INO, "a.txt"),
            ).thenReturn(existingNode)
        }

        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking {
                driveUploadService.checkUploadTargetBeforeBody(uploadRequest, USER_ID)
            }
        }

        assertEquals(ArtifactMessageCode.NODE_EXISTED, exception.messageCode)
    }

    @Test
    fun `should allow existing file before body read when overwrite is true`() {
        val existingNode = existingFileNode()
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/a.txt",
            overwrite = true,
        )

        runBlocking {
            whenever(driveRepositoryInitService.ensureInitialized(any(), any(), any())).thenReturn(Unit)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DriveNodeQueryHelper.ROOT_INO, "a.txt"),
            ).thenReturn(existingNode)
        }

        runBlocking {
            driveUploadService.checkUploadTargetBeforeBody(uploadRequest, USER_ID)
        }
    }

    @Test
    fun `should skip pre-check when parent directory path does not exist`() {
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/missing-dir/a.txt",
            overwrite = false,
        )

        runBlocking {
            whenever(driveRepositoryInitService.ensureInitialized(any(), any(), any())).thenReturn(Unit)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DriveNodeQueryHelper.ROOT_INO, "missing-dir"),
            ).thenReturn(null)
        }

        runBlocking {
            driveUploadService.checkUploadTargetBeforeBody(uploadRequest, USER_ID)
        }
    }

    @Test
    fun `should reject sha256 mismatch`() {
        val artifactFile = artifactFile(sha256 = "actual", size = 4)
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/a.txt",
            expectedSha256 = "expected",
        )

        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking {
                driveUploadService.uploadCompleteFile(uploadRequest, artifactFile, null, USER_ID)
            }
        }

        assertEquals(ArtifactMessageCode.DIGEST_CHECK_FAILED, exception.messageCode)
        runBlocking {
            verify(driveRepositoryInitService, never()).ensureInitialized(any(), any(), any())
            verify(driveArtifactThumbnailBinder, never()).render(any(), anyOrNull(), any())
        }
    }

    @Test
    fun `should reject existing file when overwrite is false`() {
        val existingNode = existingFileNode()
        val artifactFile = artifactFile(sha256 = "abc", size = 4)
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/a.txt",
            overwrite = false,
        )

        runBlocking {
            whenever(driveRepositoryInitService.ensureInitialized(any(), any(), any())).thenReturn(Unit)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DriveNodeQueryHelper.ROOT_INO, "a.txt"),
            ).thenReturn(existingNode)
        }

        val exception = assertThrows(ErrorCodeException::class.java) {
            runBlocking {
                driveUploadService.uploadCompleteFile(uploadRequest, artifactFile, null, USER_ID)
            }
        }

        assertEquals(ArtifactMessageCode.NODE_EXISTED, exception.messageCode)
        runBlocking {
            verify(driveNodeService, never()).createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull())
            verify(
                driveFileOperationService, never()
            ).write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any())
        }
    }

    @Test
    fun `should overwrite existing file when overwrite is true`() {
        val existingNode = existingFileNode()
        val artifactFile = artifactFile(sha256 = "abc", size = 4)
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/a.txt",
            overwrite = true,
        )
        val newIno = 2002L
        val createdNode = driveNode(ino = newIno)

        runBlocking {
            whenever(driveRepositoryInitService.ensureInitialized(any(), any(), any())).thenReturn(Unit)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DriveNodeQueryHelper.ROOT_INO, "a.txt"),
            ).thenReturn(existingNode)
            whenever(driveInoAllocator.allocate(PROJECT_ID, REPO_NAME)).thenReturn(newIno)
            whenever(driveNodeService.delete(any<DriveNodeDeleteRequest>(), anyOrNull(), anyOrNull()))
                .thenReturn(driveNode(ino = existingNode.ino))
            whenever(driveNodeService.createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull()))
                .thenReturn(createdNode)
            whenever(
                driveFileOperationService.write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any()),
            ).thenReturn(mock<DriveBlockNode>())
        }

        val result = runBlocking {
            driveUploadService.uploadCompleteFile(uploadRequest, artifactFile, null, USER_ID)
        }

        assertEquals(newIno, result.ino)
        runBlocking {
            verify(driveNodeService).delete(any<DriveNodeDeleteRequest>(), anyOrNull(), anyOrNull())
            verify(driveNodeService).createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull())
            verify(driveFileOperationService).write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any())
            verify(driveNodeService, never()).update(any(), anyOrNull(), anyOrNull())
            verify(driveArtifactThumbnailBinder).render(any(), anyOrNull(), any())
        }
    }

    @Test
    fun `should reuse concurrently created parent directory`() {
        val directoryNode = existingDirectoryNode()
        val artifactFile = artifactFile(sha256 = "abc", size = 4)
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/new-dir/a.txt",
            overwrite = false,
        )
        val fileIno = 2002L
        val createdNode = driveNode(ino = fileIno)

        runBlocking {
            whenever(driveRepositoryInitService.ensureInitialized(any(), any(), any())).thenReturn(Unit)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DriveNodeQueryHelper.ROOT_INO, "new-dir"),
            ).thenReturn(null, directoryNode)
            whenever(
                driveNodeDao.findCurrentNode(PROJECT_ID, REPO_NAME, DIRECTORY_INO, "a.txt"),
            ).thenReturn(null)
            whenever(driveInoAllocator.allocate(PROJECT_ID, REPO_NAME)).thenReturn(2001L, fileIno)
            whenever(driveNodeService.createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull()))
                .thenThrow(ErrorCodeException(ArtifactMessageCode.NODE_EXISTED, "new-dir"))
                .thenReturn(createdNode)
            whenever(
                driveFileOperationService.write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any()),
            ).thenReturn(mock<DriveBlockNode>())
        }

        val result = runBlocking {
            driveUploadService.uploadCompleteFile(uploadRequest, artifactFile, null, USER_ID)
        }

        assertEquals(fileIno, result.ino)
        runBlocking {
            verify(driveFileOperationService).write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any())
            verify(driveArtifactThumbnailBinder).render(any(), anyOrNull(), any())
        }
    }

    @Test
    fun `writes thumbnail path only to source metadata`(@TempDir tempDir: Path) {
        initCoArtifactFileFactory(tempDir)
        val generateService: ArtifactThumbnailGenerateService = mock()
        val uploadService = uploadServiceWithBinder(generateService)
        val sourcePath = tempDir.resolve("hero.png")
        Files.write(sourcePath, byteArrayOf(1, 2, 3, 4))
        val artifactFile = artifactFile(sha256 = "abc", size = 4)
        whenever(artifactFile.getFile()).thenReturn(sourcePath.toFile())
        val artifactMetadata = imageMetadata().apply {
            add(TMetadata(key = "imate_artifact_thumbnail", value = "/stolen/.other.thumb.jpg"))
            add(TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO, value = "999"))
        }
        val sourceIno = 2002L
        val sidecarIno = 3003L
        val createdSource = driveNode(ino = sourceIno).copy(name = "hero.png", metadata = artifactMetadata)
        val createdSidecar = driveNode(ino = sidecarIno).copy(name = ".hero.png.thumb.jpg", metadata = null)
        val sidecarPath = "/.hero.png.thumb.jpg"
        val updatedSource = createdSource.copy(
            metadata = artifactMetadata + TMetadata(
                key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL,
                value = sidecarPath,
            ),
        )
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/hero.png",
            overwrite = false,
        )

        runBlocking {
            whenever(generateService.generate(any(), any(), any())).thenReturn(ThumbnailGenerateResult.Success)
            whenever(driveNodeDao.findCurrentNode(any(), any(), any(), any())).thenReturn(null)
            whenever(driveInoAllocator.allocate(PROJECT_ID, REPO_NAME)).thenReturn(sourceIno, sidecarIno)
            whenever(driveNodeService.createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull()))
                .thenAnswer { invocation ->
                    val request = invocation.getArgument<DriveNodeCreateRequest>(0)
                    if (ArtifactThumbnailTypes.isSidecarFileName(request.name)) createdSidecar else createdSource
                }
            whenever(
                driveFileOperationService.write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any()),
            ).thenReturn(mock<DriveBlockNode>())
            whenever(driveNodeService.update(any(), anyOrNull(), anyOrNull())).thenReturn(updatedSource)
        }

        val result = runBlocking {
            uploadService.uploadCompleteFile(uploadRequest, artifactFile, artifactMetadata, USER_ID)
        }

        assertEquals(sourceIno, result.ino)
        val createCaptor = argumentCaptor<DriveNodeCreateRequest>()
        val updateCaptor = argumentCaptor<DriveNodeUpdateRequest>()
        runBlocking {
            verify(driveNodeService, times(2)).createNode(createCaptor.capture(), anyOrNull(), anyOrNull())
            verify(driveNodeService).update(updateCaptor.capture(), anyOrNull(), anyOrNull())
            val order = inOrder(generateService, driveFileOperationService)
            order.verify(generateService).generate(any(), any(), any())
            order.verify(driveFileOperationService, times(2)).write(
                any<CoArtifactFile>(),
                any<DriveBlockWriteRequest>(),
                any(),
            )
        }
        val sourceCreate = createCaptor.allValues.single { it.name == "hero.png" }
        val sidecarCreate = createCaptor.allValues.single { it.name == ".hero.png.thumb.jpg" }
        assertEquals(
            "封面",
            ArtifactThumbnailTypes.metadataValue(sourceCreate.metadata, ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME),
        )
        assertNull(
            ArtifactThumbnailTypes.metadataValue(
                sourceCreate.metadata,
                ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL,
            ),
        )
        assertNull(
            ArtifactThumbnailTypes.metadataValue(
                sourceCreate.metadata,
                ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO,
            ),
        )
        assertNull(sidecarCreate.metadata)
        assertEquals(sourceIno, updateCaptor.firstValue.ino)
        assertEquals(true, updateCaptor.firstValue.touchLastModified)
        assertEquals(
            sidecarPath,
            ArtifactThumbnailTypes.metadataValue(
                updateCaptor.firstValue.metadata,
                ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL,
            ),
        )
        assertEquals(
            sidecarIno.toString(),
            ArtifactThumbnailTypes.metadataValue(
                updateCaptor.firstValue.metadata,
                ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO,
            ),
        )
        assertEquals(
            "封面",
            ArtifactThumbnailTypes.metadataValue(
                updateCaptor.firstValue.metadata,
                ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME,
            ),
        )
    }

    @Test
    fun `drops client thumbnail metadata when generate does not produce jpeg`() {
        val forged = imageMetadata().apply {
            add(TMetadata(key = "IMATE_ARTIFACT_THUMBNAIL", value = "/docs/.victim.thumb.jpg"))
            add(TMetadata(key = "imate_artifact_thumbnail_ino", value = "42"))
        }
        val sourceIno = 2004L
        val uploadRequest = DriveNodeUploadRequest(
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            fullPath = "/hero.png",
            overwrite = false,
        )
        val artifactFile = artifactFile(sha256 = "abc", size = 4)
        runBlocking {
            whenever(driveArtifactThumbnailBinder.render(any(), anyOrNull(), any())).thenReturn(null)
            whenever(driveNodeDao.findCurrentNode(any(), any(), any(), any())).thenReturn(null)
            whenever(driveInoAllocator.allocate(PROJECT_ID, REPO_NAME)).thenReturn(sourceIno)
            whenever(driveNodeService.createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull()))
                .thenAnswer { invocation ->
                    val request = invocation.getArgument<DriveNodeCreateRequest>(0)
                    driveNode(sourceIno).copy(name = request.name, metadata = request.metadata)
                }
            whenever(
                driveFileOperationService.write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any()),
            ).thenReturn(mock<DriveBlockNode>())
        }

        val result = runBlocking {
            driveUploadService.uploadCompleteFile(uploadRequest, artifactFile, forged, USER_ID)
        }

        assertEquals(sourceIno, result.ino)
        assertEquals(
            "封面",
            ArtifactThumbnailTypes.metadataValue(result.metadata, ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME),
        )
        assertNull(
            ArtifactThumbnailTypes.metadataValue(result.metadata, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL),
        )
        assertNull(
            ArtifactThumbnailTypes.metadataValue(
                result.metadata,
                ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO,
            ),
        )
        runBlocking {
            verify(driveNodeService, never()).update(any(), anyOrNull(), anyOrNull())
            val createCaptor = argumentCaptor<DriveNodeCreateRequest>()
            verify(driveNodeService).createNode(createCaptor.capture(), anyOrNull(), anyOrNull())
            assertNull(
                ArtifactThumbnailTypes.metadataValue(
                    createCaptor.firstValue.metadata,
                    ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL,
                ),
            )
        }
    }

    @Test
    fun `persistThumbnail backfill does not touch lastModifiedBy or lastModifiedDate`(@TempDir tempDir: Path) {
        initCoArtifactFileFactory(tempDir)
        val jpeg = tempDir.resolve("thumb.jpg")
        Files.write(jpeg, byteArrayOf(1, 2, 3, 4))
        val source = driveNode(ino = 2002L).copy(name = "hero.png")
        val sidecar = driveNode(ino = 3003L).copy(name = ".hero.png.thumb.jpg", metadata = null)
        runBlocking {
            whenever(driveNodeDao.findCurrentNode(any(), any(), any(), any())).thenReturn(null)
            whenever(driveInoAllocator.allocate(PROJECT_ID, REPO_NAME)).thenReturn(3003L)
            whenever(driveNodeService.createNode(any<DriveNodeCreateRequest>(), anyOrNull(), anyOrNull()))
                .thenReturn(sidecar)
            whenever(
                driveFileOperationService.write(any<CoArtifactFile>(), any<DriveBlockWriteRequest>(), any()),
            ).thenReturn(mock<DriveBlockNode>())
            whenever(driveNodeService.update(any(), anyOrNull(), anyOrNull())).thenReturn(source)
        }

        runBlocking {
            driveUploadService.persistThumbnail(source, "/hero.png", jpeg, USER_ID, touchLastModified = false)
        }

        val updateCaptor = argumentCaptor<DriveNodeUpdateRequest>()
        runBlocking {
            verify(driveNodeService).update(updateCaptor.capture(), anyOrNull(), anyOrNull())
        }
        assertEquals(false, updateCaptor.firstValue.touchLastModified)
        assertEquals(null, updateCaptor.firstValue.name)
        assertEquals(null, updateCaptor.firstValue.size)
    }

    private fun uploadServiceWithBinder(generateService: ArtifactThumbnailGenerateService): DriveUploadService {
        return DriveUploadService(
            driveNodeService = driveNodeService,
            driveNodeDao = driveNodeDao,
            driveFileOperationService = driveFileOperationService,
            driveRepositoryInitService = driveRepositoryInitService,
            driveInoAllocator = driveInoAllocator,
            driveArtifactThumbnailBinder = DriveArtifactThumbnailBinder(
                generateService,
                DriveThumbnailProperties().apply { enabled = true },
            ),
        )
    }

    private fun imageMetadata(): MutableList<TMetadata> {
        return mutableListOf(
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = "封面"),
            TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_TYPE, value = "image"),
        )
    }

    private fun existingFileNode(): TDriveNode {
        val now = LocalDateTime.now()
        return TDriveNode(
            createdBy = USER_ID,
            createdDate = now,
            lastModifiedBy = USER_ID,
            lastModifiedDate = now,
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            ino = 1001L,
            realIno = 1001L,
            parent = DriveNodeQueryHelper.ROOT_INO,
            name = "a.txt",
            size = 1,
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
        )
    }

    private fun existingDirectoryNode(): TDriveNode {
        val now = LocalDateTime.now()
        return TDriveNode(
            id = "directory-id",
            createdBy = USER_ID,
            createdDate = now,
            lastModifiedBy = USER_ID,
            lastModifiedDate = now,
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            ino = DIRECTORY_INO,
            realIno = DIRECTORY_INO,
            parent = DriveNodeQueryHelper.ROOT_INO,
            name = "new-dir",
            size = 0,
            mode = 0,
            type = TYPE_DIRECTORY,
            nlink = 2,
            uid = 0,
            gid = 0,
            rdev = 0,
            flags = 0,
            mtime = 0,
            ctime = 0,
            atime = 0,
        )
    }

    private fun driveNode(ino: Long): DriveNode {
        val now = LocalDateTime.now()
        return DriveNode(
            id = "node-id",
            createdBy = USER_ID,
            createdDate = now,
            lastModifiedBy = USER_ID,
            lastModifiedDate = now,
            mtime = 0,
            ctime = 0,
            atime = 0,
            projectId = PROJECT_ID,
            repoName = REPO_NAME,
            ino = ino,
            realIno = ino,
            parent = DriveNodeQueryHelper.ROOT_INO,
            name = "a.txt",
            size = 4,
            mode = 0,
            type = TYPE_FILE,
            nlink = 1,
            uid = 0,
            gid = 0,
            rdev = 0,
            flags = 0,
        )
    }

    private fun artifactFile(sha256: String, size: Long): CoArtifactFile {
        val artifactFile = mock<CoArtifactFile>()
        whenever(artifactFile.getFileSha256()).thenReturn(sha256)
        whenever(artifactFile.getSize()).thenReturn(size)
        return artifactFile
    }

    private fun initCoArtifactFileFactory(tempDir: Path) {
        val storageProperties = StorageProperties().apply {
            filesystem = FileSystemCredentials(upload = UploadProperties(location = tempDir.toString()))
        }
        CoArtifactFileFactory.storageProperties = storageProperties
        CoArtifactFileFactory.storageHealthMonitorHelper = StorageHealthMonitorHelper(ConcurrentHashMap())
    }

    companion object {
        private const val PROJECT_ID = "demo"
        private const val REPO_NAME = "drive-local"
        private const val USER_ID = "admin"
        private const val DIRECTORY_INO = 1000L
    }
}
