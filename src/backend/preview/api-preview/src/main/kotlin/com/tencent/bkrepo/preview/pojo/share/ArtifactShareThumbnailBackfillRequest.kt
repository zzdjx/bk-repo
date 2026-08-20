package com.tencent.bkrepo.preview.pojo.share

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "Admin 回填历史分享缩略图")
data class ArtifactShareThumbnailBackfillRequest(
    @get:Schema(title = "指定则只处理该项目；缺省扫描全部")
    val projectId: String? = null,
    @get:Schema(title = "指定则只处理该仓库；可与 projectId 组合")
    val repoName: String? = null,
    @get:Schema(title = "本次最多处理的分享数，失败与跳过也占额度", required = true)
    val limit: Int? = null,
    @get:Schema(title = "墙钟秒数，默认 60，范围 1..300")
    val maxDurationSeconds: Int? = null,
    @get:Schema(title = "只扫描不写库；succeeded 表示将写入的条数")
    val dryRun: Boolean = false,
    /**
     * 为 true 时覆盖已有缩略图字段，用于源 sidecar 重生成后同步分享记录。
     */
    @get:Schema(title = "覆盖已有缩略图字段；仍不改 lastModifiedBy / lastModifiedDate")
    val force: Boolean = false,
)
