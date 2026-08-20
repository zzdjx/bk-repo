package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.util.Preconditions
import com.tencent.bkrepo.common.artifact.constant.PROJECT_ID
import com.tencent.bkrepo.common.artifact.constant.REPO_KEY
import com.tencent.bkrepo.common.artifact.constant.REPO_NAME
import com.tencent.bkrepo.common.artifact.message.ArtifactMessageCode
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.common.metadata.model.drive.TDriveNode
import com.tencent.bkrepo.common.metadata.service.repo.RRepositoryService
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.context.ReactiveRequestContextHolder
import com.tencent.bkrepo.fs.server.repository.drive.RDriveNodeDao
import com.tencent.bkrepo.fs.server.request.drive.DriveThumbnailBackfillRequest
import com.tencent.bkrepo.fs.server.response.drive.DriveNode
import com.tencent.bkrepo.fs.server.response.drive.DriveThumbnailBackfillResult
import com.tencent.bkrepo.fs.server.response.drive.toDriveNode
import com.tencent.bkrepo.fs.server.service.drive.DriveFileOperationService
import com.tencent.bkrepo.fs.server.service.drive.DrivePathResolveService
import com.tencent.bkrepo.fs.server.service.drive.DriveUploadService
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.repository.pojo.repo.RepositoryDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Admin 缩略图回填：扫描缺双字段的 IMATE 作品并串行/有限并发生成 sidecar。
 * 写回源节点时只补缩略图元数据，不改 lastModifiedBy / lastModifiedDate。
 */
@Service
class DriveThumbnailBackfillService(
    private val properties: DriveThumbnailProperties,
    private val lock: DriveThumbnailBackfillLock,
    private val repositoryService: RRepositoryService,
    private val driveNodeDao: RDriveNodeDao,
    private val drivePathResolveService: DrivePathResolveService,
    private val driveFileOperationService: DriveFileOperationService,
    private val generateService: ArtifactThumbnailGenerateService,
    private val driveUploadService: DriveUploadService,
) {
    suspend fun backfill(userId: String, request: DriveThumbnailBackfillRequest): DriveThumbnailBackfillResult {
        Preconditions.checkArgument(properties.enabled, ENABLED_PARAM)
        val limit = request.limit ?: throw ErrorCodeException(CommonMessageCode.PARAMETER_MISSING, "limit")
        Preconditions.checkArgument(limit > 0, "limit")
        val concurrency = request.concurrency ?: DEFAULT_CONCURRENCY
        Preconditions.checkArgument(concurrency > 0, "concurrency")
        val maxDurationSeconds = request.maxDurationSeconds ?: DEFAULT_MAX_DURATION_SECONDS
        Preconditions.checkArgument(maxDurationSeconds in MAX_DURATION_SECONDS_RANGE, "maxDurationSeconds")
        val projectId = request.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val artifactTypes = parseArtifactTypes(request.artifactTypes)
        val ttlMillis = (maxDurationSeconds + LOCK_TTL_PADDING_SECONDS) * MILLIS_PER_SECOND
        val owner = lock.tryAcquire(ttlMillis)
            ?: throw ErrorCodeException(
                HttpStatus.CONFLICT,
                CommonMessageCode.ACQUIRE_LOCK_FAILED,
                arrayOf(DriveThumbnailBackfillLock.LOCK_KEY),
            )
        try {
            val repos = listTargetRepos(projectId)
            val result = if (repos.isEmpty()) {
                DriveThumbnailBackfillResult(attempted = 0, succeeded = 0, failed = 0)
            } else {
                runBackfill(
                    userId = userId,
                    repos = repos,
                    limit = limit,
                    concurrency = concurrency,
                    dryRun = request.dryRun,
                    force = request.force,
                    artifactTypes = artifactTypes,
                    deadlineNanos = System.nanoTime() + Duration.ofSeconds(maxDurationSeconds.toLong()).toNanos(),
                )
            }
            logger.info(
                "Thumbnail backfill finished user[$userId] project[${projectId ?: "all"}] " +
                    "attempted[${result.attempted}] succeeded[${result.succeeded}] failed[${result.failed}]",
            )
            return result
        } finally {
            lock.release(owner)
        }
    }

    private suspend fun listTargetRepos(projectId: String?): List<RepositoryDetail> {
        val repoName = properties.backfillRepoName
        if (projectId != null) {
            val repo = repositoryService.getRepoDetail(projectId, repoName)
            if (repo == null || repo.type != RepositoryType.DRIVE) {
                throw ErrorCodeException(
                    HttpStatus.NOT_FOUND,
                    ArtifactMessageCode.REPOSITORY_NOT_FOUND,
                    arrayOf(repoName),
                )
            }
            return listOf(repo)
        }
        val matched = mutableListOf<RepositoryDetail>()
        var pageNumber = 0
        while (true) {
            val page = repositoryService.listRepoPageByType(RepositoryType.DRIVE.name, pageNumber, REPO_PAGE_SIZE)
            matched += page.records.filter { it.name == repoName }
            if (page.records.size < REPO_PAGE_SIZE) {
                break
            }
            pageNumber += 1
        }
        return matched.sortedWith(compareBy({ it.projectId }, { it.name }))
    }

    private suspend fun runBackfill(
        userId: String,
        repos: List<RepositoryDetail>,
        limit: Int,
        concurrency: Int,
        dryRun: Boolean,
        force: Boolean,
        artifactTypes: Set<String>?,
        deadlineNanos: Long,
    ): DriveThumbnailBackfillResult {
        val session = BackfillSession(
            userId = userId,
            limit = limit,
            dryRun = dryRun,
            force = force,
            artifactTypes = artifactTypes,
            deadlineNanos = deadlineNanos,
            workers = Semaphore(concurrency),
        )
        for (repo in repos) {
            if (quotaReached(session)) {
                break
            }
            scanRepo(repo, session)
        }
        return DriveThumbnailBackfillResult(
            attempted = session.attempted.get(),
            succeeded = session.succeeded.get(),
            failed = session.failed.get(),
        )
    }

    private suspend fun scanRepo(repo: RepositoryDetail, session: BackfillSession) {
        coroutineScope {
            var lastIno: Long? = null
            while (true) {
                if (quotaReached(session)) {
                    return@coroutineScope
                }
                val page = driveNodeDao.searchArtifactPageByIno(
                    projectId = repo.projectId,
                    repoName = repo.name,
                    pageSize = SCAN_PAGE_SIZE,
                    lastIno = lastIno,
                )
                if (page.isEmpty()) {
                    break
                }
                for (node in page) {
                    lastIno = node.ino
                    if (!offerNode(repo, node, session)) {
                        return@coroutineScope
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.offerNode(
        repo: RepositoryDetail,
        node: TDriveNode,
        session: BackfillSession,
    ): Boolean {
        if (quotaReached(session)) {
            return false
        }
        if (!shouldQueue(node, session.force, session.artifactTypes)) {
            return true
        }
        val nextAttempt = session.attempted.incrementAndGet()
        if (nextAttempt > session.limit) {
            session.attempted.decrementAndGet()
            return false
        }
        if (session.dryRun) {
            return true
        }
        session.workers.acquire()
        if (shouldStop(session.deadlineNanos)) {
            session.attempted.decrementAndGet()
            session.workers.release()
            return false
        }
        launch {
            try {
                if (generateOne(repo, node, session.userId, session.deadlineNanos)) {
                    session.succeeded.incrementAndGet()
                } else {
                    session.failed.incrementAndGet()
                }
            } finally {
                session.workers.release()
            }
        }
        return true
    }

    private fun quotaReached(session: BackfillSession): Boolean {
        return session.attempted.get() >= session.limit || shouldStop(session.deadlineNanos)
    }

    private fun shouldStop(deadlineNanos: Long): Boolean {
        val remaining = remaining(deadlineNanos)
        return remaining.isZero || remaining <= properties.timeout
    }

    private fun remaining(deadlineNanos: Long): Duration {
        val left = deadlineNanos - System.nanoTime()
        return if (left <= 0) Duration.ZERO else Duration.ofNanos(left)
    }

    private fun parseArtifactTypes(raw: List<String>?): Set<String>? {
        if (raw == null) {
            return null
        }
        val types = raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (types.isEmpty()) {
            throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "artifactTypes")
        }
        return types
    }

    private fun shouldQueue(node: TDriveNode, force: Boolean, artifactTypes: Set<String>?): Boolean {
        val rawType = ArtifactThumbnailTypes.metadataValue(node.metadata, ArtifactThumbnailTypes.METADATA_ARTIFACT_TYPE)
        if (rawType.equals(ArtifactThumbnailTypes.TYPE_3D, ignoreCase = true)) {
            return false
        }
        val artifactType = ArtifactThumbnailTypes.resolve(rawType, node.name)
        if (artifactType in properties.skipTypes) {
            return false
        }
        if (artifactTypes != null && artifactType !in artifactTypes) {
            return false
        }
        if (!force && hasThumbnail(node.metadata)) {
            return false
        }
        return ArtifactThumbnailTypes.metadataValue(
            node.metadata,
            ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME,
        ) != null
    }

    private fun hasThumbnail(metadata: List<TMetadata>?): Boolean {
        val path = ArtifactThumbnailTypes.metadataValue(metadata, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL)
        val ino = ArtifactThumbnailTypes.metadataValue(metadata, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO)
            ?.toLongOrNull()
        return !path.isNullOrEmpty() && ino != null && ino > 0
    }

    private suspend fun generateOne(
        repo: RepositoryDetail,
        node: TDriveNode,
        userId: String,
        deadlineNanos: Long,
    ): Boolean {
        return try {
            bindRepo(repo)
            val pathInfo = drivePathResolveService.resolveFilePathInfoByIno(
                projectId = repo.projectId,
                repoName = repo.name,
                ino = node.ino,
            )
            if (pathInfo == null) {
                logger.warn(
                    "Thumbnail backfill skipped missing file [${repo.projectId}/${repo.name}] ino[${node.ino}]",
                )
                return false
            }
            fun fail(reason: String): Boolean {
                logFailure(repo, node.ino, pathInfo.fullPath, reason)
                return false
            }
            val sourceNode = pathInfo.node.toDriveNode()
            if (sourceNode.size <= 0) {
                return fail("empty file")
            }
            val artifactType = ArtifactThumbnailTypes.fromMetadata(pathInfo.node.metadata, pathInfo.fullPath)
            val stream = driveFileOperationService.read(sourceNode, Range.full(sourceNode.size))
                ?: return fail("content missing")
            val input = Files.createTempFile(TEMP_PREFIX, ".src")
            val output = Files.createTempFile(TEMP_PREFIX, ArtifactThumbnailTypes.SIDECAR_SUFFIX)
            try {
                stream.use { Files.copy(it, input, StandardCopyOption.REPLACE_EXISTING) }
                if (remaining(deadlineNanos) <= properties.timeout) {
                    return fail("duration")
                }
                val result = generateService.generateWaiting(input, artifactType, output, remaining(deadlineNanos))
                when (result) {
                    ThumbnailGenerateResult.Success -> persistGenerated(
                        repo = repo,
                        sourceNode = sourceNode,
                        fullPath = pathInfo.fullPath,
                        jpeg = output,
                        userId = userId,
                    )
                    is ThumbnailGenerateResult.Skipped -> fail(result.reason)
                    is ThumbnailGenerateResult.Failed -> fail(result.reason)
                }
            } finally {
                Files.deleteIfExists(input)
                Files.deleteIfExists(output)
            }
        } catch (e: Exception) {
            logger.error(
                "Thumbnail backfill failed project[${repo.projectId}] repo[${repo.name}] ino[${node.ino}]",
                e,
            )
            false
        }
    }

    private suspend fun persistGenerated(
        repo: RepositoryDetail,
        sourceNode: DriveNode,
        fullPath: String,
        jpeg: Path,
        userId: String,
    ): Boolean {
        bindRepo(repo)
        val updated = driveUploadService.persistThumbnail(
            sourceNode = sourceNode,
            fullPath = fullPath,
            jpeg = jpeg,
            userId = userId,
            touchLastModified = false,
        )
        if (hasThumbnail(updated.metadata)) {
            return true
        }
        logFailure(repo, sourceNode.ino, fullPath, "persist sidecar")
        return false
    }

    private fun logFailure(repo: RepositoryDetail, ino: Long, path: String?, reason: String) {
        val pathPart = path?.let { " path[$it]" }.orEmpty()
        logger.warn(
            "Thumbnail backfill failed project[${repo.projectId}] repo[${repo.name}] ino[$ino]$pathPart: [$reason]",
        )
    }

    private suspend fun bindRepo(repo: RepositoryDetail) {
        val exchange = runCatching { ReactiveRequestContextHolder.getWebExchange() }.getOrNull() ?: return
        exchange.attributes[PROJECT_ID] = repo.projectId
        exchange.attributes[REPO_NAME] = repo.name
        exchange.attributes[REPO_KEY] = repo
    }

    private class BackfillSession(
        val userId: String,
        val limit: Int,
        val dryRun: Boolean,
        val force: Boolean,
        val artifactTypes: Set<String>?,
        val deadlineNanos: Long,
        val workers: Semaphore,
    ) {
        val attempted = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DriveThumbnailBackfillService::class.java)
        private const val ENABLED_PARAM = "drive.thumbnail.enabled"
        private const val DEFAULT_CONCURRENCY = 1
        private const val DEFAULT_MAX_DURATION_SECONDS = 60
        private val MAX_DURATION_SECONDS_RANGE = 1..300
        private const val LOCK_TTL_PADDING_SECONDS = 5
        private const val MILLIS_PER_SECOND = 1000L
        private const val REPO_PAGE_SIZE = 100
        private const val SCAN_PAGE_SIZE = 100
        private const val TEMP_PREFIX = "drive-thumb-backfill-"
    }
}
