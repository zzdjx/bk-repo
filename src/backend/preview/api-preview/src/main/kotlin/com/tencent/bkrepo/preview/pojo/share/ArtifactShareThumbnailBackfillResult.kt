package com.tencent.bkrepo.preview.pojo.share

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "Admin 分享缩略图回填计数")
data class ArtifactShareThumbnailBackfillResult(
    @get:Schema(title = "尝试处理的缺缩略图分享数（含失败与跳过）")
    val attempted: Int,
    @get:Schema(title = "成功从源节点写入缩略图字段的条数；dry-run 时为将写入的条数")
    val succeeded: Int,
    @get:Schema(title = "源节点仍无缩略图双字段，跳过的条数")
    val skipped: Int,
    @get:Schema(title = "节点不存在或写库失败的条数")
    val failed: Int,
)
