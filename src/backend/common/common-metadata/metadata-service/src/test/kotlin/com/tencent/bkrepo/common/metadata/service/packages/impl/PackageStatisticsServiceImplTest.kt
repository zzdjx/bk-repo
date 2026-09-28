package com.tencent.bkrepo.common.metadata.service.packages.impl

import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.LocalConfiguration
import com.tencent.bkrepo.common.metadata.dao.packages.PackageDao
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import com.tencent.bkrepo.repository.pojo.repo.RepoListOption
import com.tencent.bkrepo.repository.pojo.repo.RepositoryInfo
import com.tencent.bkrepo.repository.pojo.software.CountResult
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationResults

@DisplayName("包搜索总览按用户权限统计仓库")
class PackageStatisticsServiceImplTest {

    private val packageDao: PackageDao = mock()
    private val repositoryService: RepositoryService = mock()
    private lateinit var service: PackageStatisticsServiceImpl

    @BeforeEach
    fun setUp() {
        service = PackageStatisticsServiceImpl(packageDao, repositoryService)
    }

    @Test
    @DisplayName("只统计用户有权限的仓库")
    fun `packageOverview only aggregates permitted repos`() {
        whenever(repositoryService.listPermissionRepo(USER_ID, PROJECT_ID, RepoListOption(display = null)))
            .thenReturn(listOf(repo(PERMITTED_REPO)))
        whenever(packageDao.aggregate(any<Aggregation>(), eq(CountResult::class.java)))
            .thenReturn(AggregationResults(listOf(CountResult(PERMITTED_REPO, 3)), Document()))

        val overview = service.packageOverview(USER_ID, "maven", PROJECT_ID, "*spring*")

        val aggregation = argumentCaptor<Aggregation>()
        verify(packageDao).aggregate(aggregation.capture(), eq(CountResult::class.java))
        val match = aggregation.firstValue.toPipeline(Aggregation.DEFAULT_CONTEXT).first()["\$match"] as Document
        assertEquals(Document("\$in", listOf(PERMITTED_REPO)), match["repoName"])
        assertEquals(PERMITTED_REPO, overview.single().repos.single().repoName)
        assertEquals(3L, overview.single().sum)
    }

    @Test
    @DisplayName("没有可读仓库时返回空总览且不查询包")
    fun `packageOverview returns empty overview without permitted repos`() {
        whenever(repositoryService.listPermissionRepo(USER_ID, PROJECT_ID, RepoListOption(display = null)))
            .thenReturn(emptyList())

        val overview = service.packageOverview(USER_ID, "maven", PROJECT_ID, null)

        verifyNoInteractions(packageDao)
        assertEquals(PROJECT_ID, overview.single().projectId)
        assertTrue(overview.single().repos.isEmpty())
        assertEquals(0L, overview.single().sum)
    }

    private fun repo(name: String) = RepositoryInfo(
        projectId = PROJECT_ID,
        name = name,
        type = RepositoryType.MAVEN,
        category = RepositoryCategory.LOCAL,
        public = false,
        description = null,
        configuration = LocalConfiguration(),
        storageCredentialsKey = null,
        createdBy = USER_ID,
        createdDate = "",
        lastModifiedBy = USER_ID,
        lastModifiedDate = "",
        quota = null,
        used = null,
        display = true
    )

    companion object {
        private const val USER_ID = "ut-user"
        private const val PROJECT_ID = "ut-project"
        private const val PERMITTED_REPO = "maven-local"
    }
}
