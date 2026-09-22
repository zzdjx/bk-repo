package com.tencent.bkrepo.preview.dao

import com.tencent.bkrepo.common.api.util.EscapeUtils
import com.tencent.bkrepo.preview.model.TArtifactShare
import com.tencent.bkrepo.preview.pojo.share.AccessibleShareChannel
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareKind
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareResourceType
import com.tencent.bkrepo.preview.pojo.share.ShareVisibility
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Repository

@Repository
class ArtifactShareDao : PreviewSimpleMongoDao<TArtifactShare>() {

    fun findActiveByProjectRepoResourceId(
        projectId: String,
        repoName: String,
        resourceId: Long,
    ): TArtifactShare? {
        val query = Query(
            Criteria.where(TArtifactShare::projectId.name).isEqualTo(projectId)
                .and(TArtifactShare::repoName.name).isEqualTo(repoName)
                .and(TArtifactShare::resourceId.name).isEqualTo(resourceId)
                .and(TArtifactShare::shareKind.name).isEqualTo(ArtifactShareKind.MATERIAL)
                .and(TArtifactShare::resourceType.name).isEqualTo(ArtifactShareResourceType.DRIVE_NODE),
        )
        return findOne(query)
    }

    fun findActiveByResourceIds(
        projectId: String,
        repoName: String,
        resourceIds: Collection<Long>,
    ): List<TArtifactShare> {
        if (resourceIds.isEmpty()) {
            return emptyList()
        }
        val query = Query(
            Criteria.where(TArtifactShare::projectId.name).isEqualTo(projectId)
                .and(TArtifactShare::repoName.name).isEqualTo(repoName)
                .and(TArtifactShare::resourceId.name).`in`(resourceIds)
                .and(TArtifactShare::shareKind.name).isEqualTo(ArtifactShareKind.MATERIAL)
                .and(TArtifactShare::resourceType.name).isEqualTo(ArtifactShareResourceType.DRIVE_NODE),
        )
        return find(query)
    }

    /**
     * 按对外 shareId 查找。兼容 `_id` 为自定义字符串或 Mongo ObjectId 的历史数据。
     */
    fun findByShareId(shareId: String): TArtifactShare? {
        val id = shareId.trim()
        if (id.isEmpty()) {
            return null
        }
        findById(id)?.let { return it }
        if (!ObjectId.isValid(id)) {
            return null
        }
        return findOne(Query.query(Criteria.where(ID).isEqualTo(ObjectId(id))))
    }

    fun findByShortShareId(shortShareId: String): TArtifactShare? {
        val id = shortShareId.trim()
        if (id.isEmpty()) {
            return null
        }
        return findOne(Query.query(Criteria.where(TArtifactShare::shortShareId.name).isEqualTo(id)))
    }

    /**
     * 仅当短码仍缺失时写入，避免并发惰性补齐互相覆盖。
     */
    fun assignShortShareIdIfAbsent(shareId: String, shortShareId: String): Boolean {
        val query = Query(
            Criteria.where(ID).isEqualTo(shareId).andOperator(
                Criteria().orOperator(
                    Criteria.where(TArtifactShare::shortShareId.name).exists(false),
                    Criteria.where(TArtifactShare::shortShareId.name).isEqualTo(null),
                    Criteria.where(TArtifactShare::shortShareId.name).isEqualTo(""),
                ),
            ),
        )
        val update = Update().set(TArtifactShare::shortShareId.name, shortShareId)
        return updateFirst(query, update).modifiedCount > 0
    }

    fun removeByProjectRepoResourceId(projectId: String, repoName: String, resourceId: Long) {
        val query = Query(
            Criteria.where(TArtifactShare::projectId.name).isEqualTo(projectId)
                .and(TArtifactShare::repoName.name).isEqualTo(repoName)
                .and(TArtifactShare::shareKind.name).isEqualTo(ArtifactShareKind.MATERIAL)
                .and(TArtifactShare::resourceType.name).isEqualTo(ArtifactShareResourceType.DRIVE_NODE)
                .and(TArtifactShare::resourceId.name).isEqualTo(resourceId),
        )
        remove(query)
    }

    fun listMine(
        createdBy: String,
        nameKeyword: String?,
        cursor: ArtifactShareListCursor?,
        limit: Int,
    ): List<TArtifactShare> {
        val criteria = combine(
            Criteria.where(TArtifactShare::createdBy.name).isEqualTo(createdBy)
                .and(TArtifactShare::shareKind.name).isEqualTo(ArtifactShareKind.MATERIAL)
                .and(TArtifactShare::resourceType.name).isEqualTo(ArtifactShareResourceType.DRIVE_NODE),
            nameCriteria(nameKeyword),
            cursorCriteria(cursor),
        )
        return find(Query(criteria).with(SORT).limit(limit + 1))
    }

    fun listAccessible(
        userId: String,
        orgIds: Collection<String>,
        nameKeyword: String?,
        cursor: ArtifactShareListCursor?,
        limit: Int,
        channel: AccessibleShareChannel? = null,
        featured: Boolean? = null,
    ): List<TArtifactShare> {
        val criteria = combine(
            Criteria.where(TArtifactShare::shareKind.name).isEqualTo(ArtifactShareKind.MATERIAL)
                .and(TArtifactShare::resourceType.name).isEqualTo(ArtifactShareResourceType.DRIVE_NODE),
            accessiblePermissionCriteria(userId, orgIds, channel),
            featuredCriteria(featured),
            nameCriteria(nameKeyword),
            cursorCriteria(cursor),
        )
        return find(Query(criteria).with(SORT).limit(limit + 1))
    }

    /**
     * 按 `_id` 升序列出分享，供 admin 重签 downloadToken 分页扫描。
     */
    fun listAllById(
        afterId: String?,
        limit: Int,
        projectId: String? = null,
        repoName: String? = null,
    ): List<TArtifactShare> {
        val parts = listOfNotNull(
            projectId?.let { Criteria.where(TArtifactShare::projectId.name).isEqualTo(it) },
            repoName?.let { Criteria.where(TArtifactShare::repoName.name).isEqualTo(it) },
            afterId?.takeIf { it.isNotBlank() }?.let { Criteria.where(ID).gt(it) },
        )
        val criteria = if (parts.isEmpty()) Criteria() else combine(*parts.toTypedArray())
        return find(Query(criteria).with(ID_ASC).limit(limit))
    }

    /**
     * 按 `_id` 升序列出作品分享，供 admin 回填分页扫描。
     * [missingOnly] 为 true 时只返回缺缩略图双字段的记录。
     */
    fun listMissingThumbnailById(
        afterId: String?,
        limit: Int,
        projectId: String? = null,
        repoName: String? = null,
        missingOnly: Boolean = true,
    ): List<TArtifactShare> {
        val criteria = combine(
            Criteria.where(TArtifactShare::shareKind.name).isEqualTo(ArtifactShareKind.MATERIAL)
                .and(TArtifactShare::resourceType.name).isEqualTo(ArtifactShareResourceType.DRIVE_NODE),
            projectId?.let { Criteria.where(TArtifactShare::projectId.name).isEqualTo(it) },
            repoName?.let { Criteria.where(TArtifactShare::repoName.name).isEqualTo(it) },
            if (missingOnly) missingThumbnailCriteria() else null,
            afterId?.takeIf { it.isNotBlank() }?.let { Criteria.where(ID).gt(it) },
        )
        return find(Query(criteria).with(ID_ASC).limit(limit))
    }

    /**
     * 仅当 downloadToken 仍为 [expectedToken] 时替换，避免整文档写回覆盖扫描期间的并发修改。
     */
    fun replaceDownloadToken(shareId: String, expectedToken: String?, downloadToken: String): Boolean {
        val update = Update().set(TArtifactShare::downloadToken.name, downloadToken)
        return updateByShareId(shareId) { key ->
            val query = Query(
                Criteria.where(ID).isEqualTo(key)
                    .and(TArtifactShare::downloadToken.name).isEqualTo(expectedToken),
            )
            updateFirst(query, update).matchedCount > 0
        }
    }

    /**
     * 仅当缩略图仍缺失时写入 sidecar 字段，不改 lastModifiedBy / lastModifiedDate。
     */
    fun updateThumbnailIfAbsent(shareId: String, thumbnailIno: Long, thumbnailFullPath: String): Boolean {
        return updateThumbnailFields(shareId, thumbnailIno, thumbnailFullPath, onlyIfAbsent = true)
    }

    /**
     * 覆盖写入 sidecar 字段，不改 lastModifiedBy / lastModifiedDate。
     */
    fun updateThumbnail(shareId: String, thumbnailIno: Long, thumbnailFullPath: String): Boolean {
        return updateThumbnailFields(shareId, thumbnailIno, thumbnailFullPath, onlyIfAbsent = false)
    }

    private fun accessiblePermissionCriteria(
        userId: String,
        orgIds: Collection<String>,
        channel: AccessibleShareChannel?,
    ): Criteria {
        val orgIdList = orgIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val directed = directedAccessCriteria(userId, orgIdList)
        val publicVisibility = Criteria.where(TArtifactShare::visibility.name).isEqualTo(ShareVisibility.PUBLIC)
        return when (channel) {
            null -> Criteria().orOperator(
                publicVisibility,
                Criteria.where(TArtifactShare::createdBy.name).isEqualTo(userId),
                *directed,
            )
            AccessibleShareChannel.ALL -> Criteria().orOperator(publicVisibility, *directed)
            AccessibleShareChannel.PUBLIC -> publicVisibility
            AccessibleShareChannel.CUSTOM -> Criteria().andOperator(
                Criteria.where(TArtifactShare::visibility.name).isEqualTo(ShareVisibility.CUSTOM),
                Criteria().orOperator(*directed),
            )
        }
    }

    private fun featuredCriteria(featured: Boolean?): Criteria? {
        if (featured != true) {
            return null
        }
        return Criteria.where(TArtifactShare::featured.name).isEqualTo(true)
    }

    private fun directedAccessCriteria(userId: String, orgIdList: List<String>): Array<Criteria> {
        val clauses = mutableListOf(
            Criteria.where(TArtifactShare::userIds.name).isEqualTo(userId),
        )
        if (orgIdList.isNotEmpty()) {
            clauses += Criteria.where(TArtifactShare::orgIds.name).`in`(orgIdList)
        }
        return clauses.toTypedArray()
    }

    private fun nameCriteria(nameKeyword: String?): Criteria? {
        val keyword = nameKeyword?.trim().orEmpty()
        if (keyword.isEmpty()) {
            return null
        }
        return Criteria.where(TArtifactShare::artifactName.name).regex(EscapeUtils.escapeRegex(keyword), "i")
    }

    private fun cursorCriteria(cursor: ArtifactShareListCursor?): Criteria? {
        if (cursor == null) {
            return null
        }
        return Criteria().orOperator(
            Criteria.where(TArtifactShare::lastModifiedDate.name).lt(cursor.lastModifiedDate),
            Criteria.where(TArtifactShare::lastModifiedDate.name).isEqualTo(cursor.lastModifiedDate)
                .and(ID).lt(cursor.id),
        )
    }

    private fun updateThumbnailFields(
        shareId: String,
        thumbnailIno: Long,
        thumbnailFullPath: String,
        onlyIfAbsent: Boolean,
    ): Boolean = updateByShareId(shareId) { key ->
        updateThumbnailByKey(key, thumbnailIno, thumbnailFullPath, onlyIfAbsent)
    }

    /**
     * 兼容 `_id` 为自定义字符串或 Mongo ObjectId 的历史数据，依次用两种 key 执行 [update]。
     */
    private fun updateByShareId(shareId: String, update: (Any) -> Boolean): Boolean {
        val id = shareId.trim()
        if (id.isEmpty()) {
            return false
        }
        if (update(id)) {
            return true
        }
        if (!ObjectId.isValid(id)) {
            return false
        }
        return update(ObjectId(id))
    }

    private fun updateThumbnailByKey(
        id: Any,
        thumbnailIno: Long,
        thumbnailFullPath: String,
        onlyIfAbsent: Boolean,
    ): Boolean {
        val idCriteria = Criteria.where(ID).isEqualTo(id)
        val query = if (onlyIfAbsent) {
            Query(idCriteria.andOperator(missingThumbnailCriteria()))
        } else {
            Query(idCriteria)
        }
        val update = Update()
            .set(TArtifactShare::thumbnailIno.name, thumbnailIno)
            .set(TArtifactShare::thumbnailFullPath.name, thumbnailFullPath)
        val result = updateFirst(query, update)
        return if (onlyIfAbsent) {
            result.modifiedCount > 0
        } else {
            result.matchedCount > 0
        }
    }

    private fun missingThumbnailCriteria(): Criteria {
        return Criteria().orOperator(
            Criteria.where(TArtifactShare::thumbnailIno.name).exists(false),
            Criteria.where(TArtifactShare::thumbnailIno.name).isEqualTo(null),
            Criteria.where(TArtifactShare::thumbnailIno.name).lte(0),
            Criteria.where(TArtifactShare::thumbnailFullPath.name).exists(false),
            Criteria.where(TArtifactShare::thumbnailFullPath.name).isEqualTo(null),
            Criteria.where(TArtifactShare::thumbnailFullPath.name).isEqualTo(""),
        )
    }

    private fun combine(vararg parts: Criteria?): Criteria {
        val criteriaList = parts.filterNotNull()
        return if (criteriaList.size == 1) {
            criteriaList[0]
        } else {
            Criteria().andOperator(*criteriaList.toTypedArray())
        }
    }

    companion object {
        private val SORT = Sort.by(
            Sort.Order.desc(TArtifactShare::lastModifiedDate.name),
            Sort.Order.desc(ID),
        )
        private val ID_ASC = Sort.by(Sort.Order.asc(ID))
    }
}
