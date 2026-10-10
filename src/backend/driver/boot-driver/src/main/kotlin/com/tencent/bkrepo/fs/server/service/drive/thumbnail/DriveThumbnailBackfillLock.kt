package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.tencent.bkrepo.fs.server.model.drive.TDriveThumbnailBackfillLock
import com.tencent.bkrepo.fs.server.repository.drive.DriveThumbnailBackfillLockDao
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 缩略图回填的集群单飞锁：tryAcquire 一次，失败即放弃。
 */
@Component
class DriveThumbnailBackfillLock(
    private val lockDao: DriveThumbnailBackfillLockDao,
) {
    suspend fun tryAcquire(ttlMillis: Long): String? {
        val now = System.currentTimeMillis()
        val existing = lockDao.findByKey(LOCK_KEY)
        if (existing != null) {
            if (existing.expireTime >= now) {
                return null
            }
            lockDao.deleteExpired(LOCK_KEY, now)
        }
        val owner = UUID.randomUUID().toString()
        return try {
            lockDao.insert(
                TDriveThumbnailBackfillLock(
                    key = LOCK_KEY,
                    owner = owner,
                    expireTime = now + ttlMillis,
                ),
            )
            owner
        } catch (_: DuplicateKeyException) {
            logger.info("Thumbnail backfill lock already held key[$LOCK_KEY]")
            null
        }
    }

    suspend fun release(owner: String) {
        lockDao.deleteByKeyAndOwner(LOCK_KEY, owner)
    }

    companion object {
        const val LOCK_KEY = "drive:thumbnail:backfill"
        private val logger = LoggerFactory.getLogger(DriveThumbnailBackfillLock::class.java)
    }
}
