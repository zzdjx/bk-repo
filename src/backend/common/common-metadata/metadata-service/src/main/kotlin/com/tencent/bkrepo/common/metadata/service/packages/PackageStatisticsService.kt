package com.tencent.bkrepo.common.metadata.service.packages

import com.tencent.bkrepo.repository.pojo.software.ProjectPackageOverview

interface PackageStatisticsService {

    /**
     * 包搜索总览，只统计[userId]有权限的仓库
     */
    fun packageOverview(
        userId: String,
        repoType: String,
        projectId: String,
        packageName: String?
    ): List<ProjectPackageOverview>

}
