package com.tencent.bkrepo.fs.server.response.drive

/**
 * 缩略图回填计数。dry-run 时 [succeeded] 与 [failed] 均为 0。
 */
data class DriveThumbnailBackfillResult(
    /**
     * 尝试处理的缺缩略图作品数。
     */
    val attempted: Int,
    /**
     * 成功写入 sidecar 与双字段的数量。
     */
    val succeeded: Int,
    /**
     * 生成或落盘失败的数量。
     */
    val failed: Int,
)
