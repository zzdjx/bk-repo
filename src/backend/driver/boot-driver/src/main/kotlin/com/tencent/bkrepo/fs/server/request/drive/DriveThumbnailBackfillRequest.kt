package com.tencent.bkrepo.fs.server.request.drive

/**
 * Admin 缩略图回填请求。仓库名由配置 [drive.thumbnail.backfillRepoName] 决定，不在请求中传递。
 */
data class DriveThumbnailBackfillRequest(
    /**
     * 可选。指定后只处理该项目下的目标仓；缺省扫描全部匹配仓。
     */
    val projectId: String? = null,
    /**
     * 本次最多尝试生成的作品数，失败也占额度。
     */
    val limit: Int? = null,
    /**
     * 并发生成数，默认 1。
     */
    val concurrency: Int? = null,
    /**
     * 墙钟秒数，默认 60，上限 300。
     */
    val maxDurationSeconds: Int? = null,
    /**
     * 只扫描不生成。
     */
    val dryRun: Boolean = false,
    /**
     * 为 true 时覆盖已有 sidecar，用于按新清晰度重生成历史缩略图。
     */
    val force: Boolean = false,
    /**
     * 只处理这些作品类型；缺省处理全部可生成类型。
     */
    val artifactTypes: List<String>? = null,
)
