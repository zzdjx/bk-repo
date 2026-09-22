package com.tencent.bkrepo.preview.pojo.share

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "Admin 分享 downloadToken 重签计数")
data class ArtifactShareDownloadTokenRefreshResult(
    @get:Schema(title = "尝试处理的分享数（含失败与跳过）")
    val attempted: Int,
    @get:Schema(title = "成功重签并写回的条数；dry-run 时为将重签的条数")
    val succeeded: Int,
    @get:Schema(title = "签发失败或并发已改动该记录，保留原 token 的条数")
    val skipped: Int,
    @get:Schema(title = "写库失败的条数")
    val failed: Int,
    @get:Schema(title = "本轮最后处理的 shareId，作为下一轮 afterShareId；为 null 表示已扫到末尾")
    val lastShareId: String? = null,
)
