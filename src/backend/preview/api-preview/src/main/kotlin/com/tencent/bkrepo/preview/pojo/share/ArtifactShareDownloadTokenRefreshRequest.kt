package com.tencent.bkrepo.preview.pojo.share

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "Admin 重签分享 downloadToken")
data class ArtifactShareDownloadTokenRefreshRequest(
    @get:Schema(title = "指定则只处理该项目；缺省扫描全部")
    val projectId: String? = null,
    @get:Schema(title = "指定则只处理该仓库；可与 projectId 组合")
    val repoName: String? = null,
    /**
     * 每条记录都会重签，无法按内容判断是否已迁移，需靠游标推进避免重复处理同一批。
     */
    @get:Schema(title = "从该 shareId 之后继续扫描，取上一轮返回的 lastShareId；缺省从头开始")
    val afterShareId: String? = null,
    @get:Schema(title = "本次最多处理的分享数，失败与跳过也占额度", required = true)
    val limit: Int? = null,
    @get:Schema(title = "墙钟秒数，默认 60，范围 1..300")
    val maxDurationSeconds: Int? = null,
    @get:Schema(title = "只扫描不写库、不签发 token；succeeded 表示将重签的条数")
    val dryRun: Boolean = false,
)
