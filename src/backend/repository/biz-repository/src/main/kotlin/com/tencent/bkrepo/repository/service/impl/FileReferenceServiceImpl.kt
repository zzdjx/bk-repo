package com.tencent.bkrepo.repository.service.impl

import com.tencent.bkrepo.repository.dao.FileReferenceDao
import com.tencent.bkrepo.repository.model.TFileReference
import com.tencent.bkrepo.repository.model.TNode
import com.tencent.bkrepo.repository.model.TRepository
import com.tencent.bkrepo.repository.service.FileReferenceService
import com.tencent.bkrepo.repository.service.RepositoryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service

/**
 * 文件引用服务实现类
 */
@Service
class FileReferenceServiceImpl : FileReferenceService {

    @Autowired
    private lateinit var fileReferenceDao: FileReferenceDao

    @Autowired
    private lateinit var repositoryService: RepositoryService

    override fun increment(node: TNode, repository: TRepository?): Boolean {
        if (!validateParameter(node)) return false
        return try {
            val credentialsKey = findCredentialsKey(node, repository)
            increment(node.sha256!!, credentialsKey)
        } catch (exception: NullPointerException) {
            logger.error("Failed to increment reference of node [$node], repository not found.")
            false
        }
    }

    override fun decrement(node: TNode, repository: TRepository?): Boolean {
        if (!validateParameter(node)) return false
        return try {
            val credentialsKey = findCredentialsKey(node, repository)
            decrement(node.sha256!!, credentialsKey)
        } catch (exception: NullPointerException) {
            logger.error("Failed to decrement reference of node [$node], repository not found.")
            false
        }
    }

    override fun increment(sha256: String, credentialsKey: String?): Boolean {
        val query = buildQuery(sha256, credentialsKey)
        val update = Update().inc(TFileReference::count.name, 1)
        try {
            fileReferenceDao.upsert(query, update)
        } catch (exception: DuplicateKeyException) {
            // retry because upsert operation is not atomic
            fileReferenceDao.upsert(query, update)
        }
        logger.info("Increment reference of file [$sha256] on credentialsKey [$credentialsKey].")
        return true
    }

    override fun decrement(sha256: String, credentialsKey: String?): Boolean {
        val query = buildQuery(sha256, credentialsKey)
        val fileReference = fileReferenceDao.findOne(query) ?: run {
            logger.error("Failed to decrement reference of file [$sha256] on credentialsKey [$credentialsKey]: " +
                "reference not found, create new one.")
            return false
        }

        return if (fileReference.count >= 1) {
            val update = Update().apply { inc(TFileReference::count.name, -1) }
            fileReferenceDao.upsert(query, update)
            logger.info("Decrement references of file [$sha256] on credentialsKey [$credentialsKey].")
            true
        } else {
            logger.error("Failed to decrement reference of file [$sha256] on credentialsKey [$credentialsKey]: " +
                "reference count is 0.")
            false
        }
    }

    override fun count(sha256: String, credentialsKey: String?): Long {
        val query = buildQuery(sha256, credentialsKey)
        return fileReferenceDao.findOne(query)?.count ?: 0
    }

    private fun findCredentialsKey(node: TNode, repository: TRepository?): String? {
        return if (repository != null) {
            repository.credentialsKey
        } else {
            repositoryService.getRepoInfo(node.projectId, node.repoName)!!.storageCredentialsKey
        }
    }

    private fun buildQuery(sha256: String, credentialsKey: String?): Query {
        val criteria = Criteria.where(TFileReference::sha256.name).`is`(sha256)
        criteria.and(TFileReference::credentialsKey.name).`is`(credentialsKey)
        return Query.query(criteria)
    }

    private fun validateParameter(node: TNode): Boolean {
        if (node.folder) return false
        if (node.sha256.isNullOrBlank()) {
            logger.warn("Failed to change file reference, node[$node] sha256 is null or blank.")
            return false
        }
        return true
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FileReferenceServiceImpl::class.java)
    }
}
