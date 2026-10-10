package com.tencent.bkrepo.fs.server.handler.drive

import com.tencent.bkrepo.common.security.exception.PermissionException
import com.tencent.bkrepo.fs.server.readBody
import com.tencent.bkrepo.fs.server.request.drive.DriveThumbnailBackfillRequest
import com.tencent.bkrepo.fs.server.service.PermissionService
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.DriveThumbnailBackfillService
import com.tencent.bkrepo.fs.server.utils.ReactiveResponseBuilder
import com.tencent.bkrepo.fs.server.utils.ReactiveSecurityUtils
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * Admin 作品缩略图回填。历史补全：只写缩略图，不改更新人、更新时间。
 */
@Component
class DriveThumbnailBackfillHandler(
    private val permissionService: PermissionService,
    private val backfillService: DriveThumbnailBackfillService,
) {
    /**
     * 平台管理员扫描目标 DRIVE 仓并回填缺失（或 force 覆盖）的作品缩略图 sidecar。
     */
    suspend fun backfill(request: ServerRequest): ServerResponse {
        val userId = ReactiveSecurityUtils.getUser()
        if (!permissionService.checkAdmin(userId)) {
            throw PermissionException()
        }
        val payload = request.readBody(DriveThumbnailBackfillRequest::class.java)
        val result = backfillService.backfill(userId, payload)
        return ReactiveResponseBuilder.success(result)
    }
}
