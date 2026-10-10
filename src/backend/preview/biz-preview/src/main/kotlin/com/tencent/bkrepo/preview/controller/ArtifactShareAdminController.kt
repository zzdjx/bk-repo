package com.tencent.bkrepo.preview.controller

import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.security.permission.Principal
import com.tencent.bkrepo.common.security.permission.PrincipalType
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareDownloadTokenRefreshRequest
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareDownloadTokenRefreshResult
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareThumbnailBackfillRequest
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareThumbnailBackfillResult
import com.tencent.bkrepo.preview.service.share.ArtifactShareService
import com.tencent.bkrepo.preview.service.share.ArtifactShareThumbnailBackfillService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 作品分享管理接口，仅平台管理员可调用。
 *
 * 路径不含 projectId/repoName，仓库级过滤器不生效，由 [PrincipalType.ADMIN] 鉴权。
 */
@Tag(name = "作品分享管理")
@RestController
@RequestMapping("/api/artifact/share/admin")
@Principal(PrincipalType.ADMIN)
class ArtifactShareAdminController(
    private val artifactShareThumbnailBackfillService: ArtifactShareThumbnailBackfillService,
    private val artifactShareService: ArtifactShareService,
) {

    /**
     * 扫描历史分享，从源节点元数据回填 sidecar inode 与路径。force 时覆盖已有字段。
     */
    @Operation(summary = "回填历史分享缩略图字段")
    @PostMapping("/thumbnail/backfill")
    fun backfillThumbnails(
        @RequestBody request: ArtifactShareThumbnailBackfillRequest,
    ): Response<ArtifactShareThumbnailBackfillResult> {
        return ResponseBuilder.success(
            artifactShareThumbnailBackfillService.backfill(SecurityUtils.getUserId(), request),
        )
    }

    /**
     * 把历史分享的 downloadToken 按当前规则重签（父目录足够深则绑父目录，否则绑原文件），只改 downloadToken 一个字段。
     * 每条记录都会重签，靠响应里的 lastShareId 作为游标分批推进。
     */
    @Operation(summary = "重签历史分享 downloadToken")
    @PostMapping("/download-token/refresh")
    fun refreshDownloadTokens(
        @RequestBody request: ArtifactShareDownloadTokenRefreshRequest,
    ): Response<ArtifactShareDownloadTokenRefreshResult> {
        return ResponseBuilder.success(
            artifactShareService.refreshDownloadTokens(SecurityUtils.getUserId(), request),
        )
    }
}
