package com.tencent.bkrepo.fs.server.repository.drive

import com.tencent.bkrepo.common.metadata.condition.ReactiveCondition
import com.tencent.bkrepo.fs.server.model.drive.TDriveThumbnailBackfillLock
import org.springframework.context.annotation.Conditional
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

@Component
@Conditional(ReactiveCondition::class)
class DriveThumbnailBackfillLockDao : DriveSimpleMongoReactiveDao<TDriveThumbnailBackfillLock>() {

    suspend fun findByKey(key: String): TDriveThumbnailBackfillLock? {
        return findById(key)
    }

    suspend fun deleteByKeyAndOwner(key: String, owner: String) {
        val criteria = Criteria.where(ID).`is`(key)
            .and(TDriveThumbnailBackfillLock::owner.name).`is`(owner)
        remove(Query(criteria))
    }

    suspend fun deleteExpired(key: String, now: Long) {
        val criteria = Criteria.where(ID).`is`(key)
            .and(TDriveThumbnailBackfillLock::expireTime.name).lt(now)
        remove(Query(criteria))
    }
}
