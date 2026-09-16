package com.tencent.bkrepo.common.metadata.service.repo.impl

import com.tencent.bkrepo.auth.api.ServicePermissionClient
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.LocalConfiguration
import com.tencent.bkrepo.common.metadata.config.RepositoryProperties
import com.tencent.bkrepo.common.metadata.dao.repo.RepositoryDao
import com.tencent.bkrepo.common.metadata.model.TRepository
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import com.tencent.bkrepo.common.metadata.service.project.ProjectService
import com.tencent.bkrepo.common.metadata.service.repo.ProxyChannelService
import com.tencent.bkrepo.common.metadata.service.repo.ResourceClearService
import com.tencent.bkrepo.common.metadata.service.repo.StorageCredentialService
import com.tencent.bkrepo.common.metadata.util.RepositoryServiceHelper
import com.tencent.bkrepo.common.storage.credentials.FileSystemCredentials
import com.tencent.bkrepo.common.stream.event.supplier.MessageSupplier
import com.tencent.bkrepo.repository.pojo.project.ProjectInfo
import com.tencent.bkrepo.repository.pojo.repo.RepoCreateRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.query.Query
import java.time.LocalDateTime

@DisplayName("仓库创建撞唯一键时不返回明文存储凭据")
class RepositoryServiceImplTest {

    private val repositoryDao: RepositoryDao = mock()
    private val projectService: ProjectService = mock()
    private val storageCredentialService: StorageCredentialService = mock()
    private lateinit var repositoryService: RepositoryServiceImpl

    @BeforeEach
    fun setUp() {
        RepositoryServiceHelper(RepositoryProperties())
        repositoryService = RepositoryServiceImpl(
            repositoryDao = repositoryDao,
            projectService = projectService,
            storageCredentialService = storageCredentialService,
            proxyChannelService = mock<ProxyChannelService>(),
            messageSupplier = mock<MessageSupplier>(),
            servicePermissionClient = mock<ServicePermissionClient>(),
            resourceClearService = mock<ObjectProvider<ResourceClearService>>(),
            permissionManager = mock<ObjectProvider<PermissionManager>>(),
        )
    }

    @Test
    @DisplayName("DuplicateKeyException 路径 storageCredentials 为 null")
    fun `should not leak storage credentials when create hits duplicate key`() {
        val projectId = "ut-project"
        val repoName = "dup-repo"
        val credentialsKey = "cred-key"
        val credentials = FileSystemCredentials(path = "/secret/storage/path", key = credentialsKey)
        val existing = TRepository(
            id = "existing-id",
            createdBy = "other",
            createdDate = LocalDateTime.now(),
            lastModifiedBy = "other",
            lastModifiedDate = LocalDateTime.now(),
            name = repoName,
            type = RepositoryType.GENERIC,
            category = RepositoryCategory.LOCAL,
            public = false,
            configuration = LocalConfiguration().toJsonString(),
            credentialsKey = credentialsKey,
            projectId = projectId,
        )
        whenever(projectService.getProjectInfo(projectId)).thenReturn(
            ProjectInfo(
                name = projectId,
                displayName = projectId,
                description = "",
                createdBy = "ut",
                createdDate = "2020-01-01T00:00:00",
                lastModifiedBy = "ut",
                lastModifiedDate = "2020-01-01T00:00:00",
                tenantId = null,
            ),
        )
        whenever(repositoryDao.findByNameAndType(eq(projectId), eq(repoName), isNull())).thenReturn(null)
        whenever(repositoryDao.findByNameAndType(eq(projectId), eq(repoName), eq("GENERIC")))
            .thenReturn(existing)
        whenever(storageCredentialService.findByKey(credentialsKey)).thenReturn(credentials)
        whenever(repositoryDao.findOne(any<Query>())).thenReturn(null)
        whenever(repositoryDao.insert(any<TRepository>())).thenThrow(DuplicateKeyException("dup"))

        val result = repositoryService.createRepo(
            RepoCreateRequest(
                projectId = projectId,
                name = repoName,
                type = RepositoryType.GENERIC,
                category = RepositoryCategory.LOCAL,
                public = false,
                storageCredentialsKey = credentialsKey,
            ),
        )

        assertEquals(repoName, result.name)
        assertNull(result.storageCredentials)
    }
}
