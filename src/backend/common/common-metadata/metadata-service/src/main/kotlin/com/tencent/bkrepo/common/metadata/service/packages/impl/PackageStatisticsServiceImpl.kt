package com.tencent.bkrepo.common.metadata.service.packages.impl

import com.tencent.bkrepo.common.api.util.EscapeUtils
import com.tencent.bkrepo.common.metadata.condition.SyncCondition
import com.tencent.bkrepo.common.metadata.dao.packages.PackageDao
import com.tencent.bkrepo.common.metadata.model.TPackage
import com.tencent.bkrepo.repository.pojo.repo.RepoListOption
import com.tencent.bkrepo.repository.pojo.software.CountResult
import com.tencent.bkrepo.repository.pojo.software.ProjectPackageOverview
import com.tencent.bkrepo.common.metadata.service.packages.PackageStatisticsService
import com.tencent.bkrepo.common.metadata.service.repo.RepositoryService
import org.springframework.context.annotation.Conditional
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import java.util.Locale

@Service
@Conditional(SyncCondition::class)
class PackageStatisticsServiceImpl(
    private val packageDao: PackageDao,
    private val repositoryService: RepositoryService
) : PackageStatisticsService {

    override fun packageOverview(
        userId: String,
        repoType: String,
        projectId: String,
        packageName: String?
    ): List<ProjectPackageOverview> {
        // 只按权限收敛统计范围，隐藏仓库（display=false）照常计入
        val repoNames = repositoryService.listPermissionRepo(
            userId = userId,
            projectId = projectId,
            option = RepoListOption(display = null)
        ).map { it.name }
        if (repoNames.isEmpty()) {
            return transTree(projectId, emptyList())
        }
        val criteria = Criteria.where(TPackage::type.name).`is`(repoType.uppercase(Locale.getDefault()))
        projectId.let { criteria.and(TPackage::projectId.name).`is`(projectId) }
        criteria.and(TPackage::repoName.name).`in`(repoNames)
        packageName?.let {
            val escapedValue = EscapeUtils.escapeRegexExceptWildcard(packageName)
            val regexPattern = escapedValue.replace("*", ".*")
            criteria.and(TPackage::name.name).regex("^$regexPattern$")
        }
        val aggregation = Aggregation.newAggregation(
            TPackage::class.java,
            Aggregation.match(criteria),
            Aggregation.group("\$${TPackage::repoName.name}").count().`as`("count")
        )
        val result = packageDao.aggregate(aggregation, CountResult::class.java).mappedResults
        return transTree(projectId, result)
    }

    private fun transTree(projectId: String, list: List<CountResult>): List<ProjectPackageOverview> {
        val projectSet = mutableSetOf<ProjectPackageOverview>()
        projectSet.add(
            ProjectPackageOverview(
                projectId = projectId,
                repos = mutableSetOf(),
                sum = 0L
            )
        )
        list.map { pojo ->
            val repoOverview = ProjectPackageOverview.RepoPackageOverview(
                repoName = pojo.id,
                packages = pojo.count
            )
            projectSet.first().repos.add(repoOverview)
            projectSet.first().sum += pojo.count
        }
        return projectSet.toList()
    }
}
