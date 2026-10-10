package com.tencent.bkrepo.fs.server.model.drive

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 缩略图回填全局单飞锁。
 */
@Document("drive_thumbnail_backfill_lock")
data class TDriveThumbnailBackfillLock(
    /**
     * 锁键，同时作为文档主键。
     */
    @Id
    val key: String,
    /**
     * 持有者标识，释放时必须匹配。
     */
    val owner: String,
    /**
     * 过期时间戳（epoch millis）。
     */
    val expireTime: Long,
)
