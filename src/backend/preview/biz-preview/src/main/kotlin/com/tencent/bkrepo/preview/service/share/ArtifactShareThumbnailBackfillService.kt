package com.tencent.bkrepo.preview.service.share

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.util.Preconditions
import com.tencent.bkrepo.preview.dao.ArtifactShareDao
import com.tencent.bkrepo.preview.model.TArtifactShare
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareThumbnailBackfillRequest
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareThumbnailBackfillResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Admin 回填：把源节点已有的缩略图 sidecar 元数据写回历史分享记录。
 * 不生成 sidecar，不改 lastModifiedBy / lastModifiedDate。
 */
@Service
class ArtifactShareThumbnailBackfillService(
    private val artifactShareDao: ArtifactShareDao,
    private val driveShareNodeResolver: DriveShareNodeResolver,
) {

    /**
     * 扫描分享记录，从源节点 `IMATE_ARTIFACT_THUMBNAIL(_INO)` 回填。
     * 默认只处理缺字段；[ArtifactShareThumbnailBackfillRequest.force] 为 true 时覆盖已有字段。
     * [ArtifactShareThumbnailBackfillRequest.limit] 必填；到 limit 或墙钟后返回计数。
     * 只补字段，不改 lastModifiedBy / lastModifiedDate，不影响按更新时间排序的列表。
     */
    fun backfill(
        userId: String,
        request: ArtifactShareThumbnailBackfillRequest,
    ): ArtifactShareThumbnailBackfillResult {
        val limit = request.limit ?: throw ErrorCodeException(CommonMessageCode.PARAMETER_MISSING, "limit")
        Preconditions.checkArgument(limit > 0, "limit")
        val maxDurationSeconds = request.maxDurationSeconds ?: DEFAULT_MAX_DURATION_SECONDS
        Preconditions.checkArgument(maxDurationSeconds in MAX_DURATION_SECONDS_RANGE, "maxDurationSeconds")
        val projectId = request.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val repoName = request.repoName?.trim()?.takeIf { it.isNotEmpty() }
        val result = runBackfill(limit, maxDurationSeconds, projectId, repoName, request.dryRun, request.force)
        logger.info(
            "artifact share thumbnail backfill finished, user=[$userId], projectId=[${projectId ?: "all"}], " +
                "repoName=[${repoName ?: "all"}], dryRun=[${request.dryRun}], force=[${request.force}], " +
                "attempted=[${result.attempted}], succeeded=[${result.succeeded}], skipped=[${result.skipped}], " +
                "failed=[${result.failed}]",
        )
        return result
    }

    private fun runBackfill(
        limit: Int,
        maxDurationSeconds: Int,
        projectId: String?,
        repoName: String?,
        dryRun: Boolean,
        force: Boolean,
    ): ArtifactShareThumbnailBackfillResult {
        val deadlineNanos = System.nanoTime() + Duration.ofSeconds(maxDurationSeconds.toLong()).toNanos()
        var attempted = 0
        var succeeded = 0
        var skipped = 0
        var failed = 0
        var afterId: String? = null
        while (attempted < limit && System.nanoTime() < deadlineNanos) {
            val pageSize = minOf(limit - attempted, SCAN_PAGE_SIZE)
            val page = artifactShareDao.listMissingThumbnailById(
                afterId = afterId,
                limit = pageSize,
                projectId = projectId,
                repoName = repoName,
                missingOnly = !force,
            )
            if (page.isEmpty()) {
                break
            }
            for (record in page) {
                if (attempted >= limit || System.nanoTime() >= deadlineNanos) {
                    break
                }
                attempted += 1
                when (processOne(record, dryRun, force)) {
                    BackfillOutcome.SUCCEEDED -> succeeded += 1
                    BackfillOutcome.SKIPPED -> skipped += 1
                    BackfillOutcome.FAILED -> failed += 1
                }
            }
            afterId = page.last().id
            if (afterId.isNullOrBlank() || page.size < pageSize) {
                break
            }
        }
        return ArtifactShareThumbnailBackfillResult(
            attempted = attempted,
            succeeded = succeeded,
            skipped = skipped,
            failed = failed,
        )
    }

    private fun processOne(record: TArtifactShare, dryRun: Boolean, force: Boolean): BackfillOutcome {
        val shareId = record.id?.trim().orEmpty()
        if (shareId.isEmpty()) {
            logger.warn(
                "artifact share thumbnail backfill failed, shareId=[null], " +
                    "projectId=[${record.projectId}], repoName=[${record.repoName}]",
            )
            return BackfillOutcome.FAILED
        }
        if (!force && !needsBackfill(record)) {
            return BackfillOutcome.SKIPPED
        }
        val nodeInfo = try {
            driveShareNodeResolver.resolveFileByIno(record.projectId, record.repoName, record.resourceId)
        } catch (ex: Exception) {
            return failed(record, shareId, ex)
        }
        if (nodeInfo == null) {
            logger.warn(
                "artifact share thumbnail backfill failed, shareId=[$shareId], " +
                    "projectId=[${record.projectId}], repoName=[${record.repoName}], " +
                    "resourceId=[${record.resourceId}], reason=[node not found]",
            )
            return BackfillOutcome.FAILED
        }
        val thumbnail = driveShareNodeResolver.resolveThumbnail(nodeInfo.node)
        if (thumbnail == null) {
            return BackfillOutcome.SKIPPED
        }
        if (dryRun) {
            return BackfillOutcome.SUCCEEDED
        }
        return writeThumbnail(shareId, record, thumbnail, force)
    }

    private fun writeThumbnail(
        shareId: String,
        record: TArtifactShare,
        thumbnail: ArtifactShareThumbnail,
        force: Boolean,
    ): BackfillOutcome {
        return try {
            if (force) {
                artifactShareDao.updateThumbnail(shareId, thumbnail.ino, thumbnail.fullPath)
            } else {
                artifactShareDao.updateThumbnailIfAbsent(shareId, thumbnail.ino, thumbnail.fullPath)
            }
            BackfillOutcome.SUCCEEDED
        } catch (ex: Exception) {
            failed(record, shareId, ex)
        }
    }

    private fun needsBackfill(record: TArtifactShare): Boolean {
        val ino = record.thumbnailIno
        val path = record.thumbnailFullPath?.trim().orEmpty()
        return ino == null || ino <= 0L || path.isEmpty()
    }

    private fun failed(record: TArtifactShare, shareId: String, error: Exception): BackfillOutcome {
        logger.error(
            "artifact share thumbnail backfill failed, shareId=[$shareId], " +
                "projectId=[${record.projectId}], repoName=[${record.repoName}]",
            error,
        )
        return BackfillOutcome.FAILED
    }

    private enum class BackfillOutcome {
        SUCCEEDED,
        SKIPPED,
        FAILED,
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtifactShareThumbnailBackfillService::class.java)
        private const val DEFAULT_MAX_DURATION_SECONDS = 60
        private val MAX_DURATION_SECONDS_RANGE = 1..300
        private const val SCAN_PAGE_SIZE = 100
    }
}
