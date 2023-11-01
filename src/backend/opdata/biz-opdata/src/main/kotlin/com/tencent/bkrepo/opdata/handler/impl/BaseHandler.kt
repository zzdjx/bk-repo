/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2022 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.opdata.handler.impl

import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.opdata.constant.DOCKER_TYPES
import com.tencent.bkrepo.opdata.constant.FILTER_TYPE
import com.tencent.bkrepo.opdata.constant.FILTER_VALUE
import com.tencent.bkrepo.opdata.model.StatDateModel
import com.tencent.bkrepo.opdata.model.TProjectMetrics
import com.tencent.bkrepo.opdata.pojo.RepoMetrics
import com.tencent.bkrepo.opdata.pojo.Target
import com.tencent.bkrepo.opdata.pojo.enums.FilterType
import com.tencent.bkrepo.opdata.pojo.enums.Metrics
import com.tencent.bkrepo.opdata.repository.ProjectMetricsRepository

/**
 * 通过在指标 target 中的 data 字段添加参数，过滤出对应条件下的指标数据
 *       "data": {    "filterType": "REPO_NAME",    "filterValue": "custom"}
 */
open class BaseHandler(
    private val projectMetricsRepository: ProjectMetricsRepository,
    private val statDateModel: StatDateModel
) {

    fun calculateMetricValue(target: Target): HashMap<String, Long> {
        val (filterType, filterValue) = getFilterInfo(target)
        val projects = projectMetricsRepository.findAllByCreatedDate(statDateModel.getShedLockInfo())
        return when (filterType) {
            FilterType.REPO_TYPE -> {
                calculateMetrics(projects, target.target, filterValue)
            }
            FilterType.REPO_NAME -> {
                calculateMetrics(projects, target.target, repoName = filterValue)
            }
            else -> {
                calculateMetrics(projects, target.target)
            }
        }
    }

    private fun calculateMetrics(
        projects: List<TProjectMetrics>, metrics: Metrics,
        repoType: String? = null, repoName: String? = null
    ): HashMap<String, Long> {
        val tmpMap = HashMap<String, Long>()
        val repoTypeList = getRepoTypes(repoType)
        projects.forEach { project ->
            if (repoTypeList.isNullOrEmpty() && repoName.isNullOrEmpty()) {
                addProjectMetrics(
                    metrics = metrics, projectMetrics = project, tmpMap = tmpMap
                )
            } else {
                project.repoMetrics.filter {
                    filterRepo(repoTypeList, repoName, it)
                }.forEach { repo ->
                    addRepoMetrics(
                        metrics = metrics, repoMetrics = repo,
                        projectId = project.projectId, tmpMap = tmpMap
                    )
                }
            }
        }
        return tmpMap
    }

    private fun getFilterInfo(target: Target): Pair<FilterType, String?> {
        val reqData = if (target.data is Map<*, *>) {
            target.data as Map<String, Any>
        } else {
            null
        }
        val filterType = FilterType.valueOf((reqData?.get(FILTER_TYPE) as? String) ?: FilterType.ALL.name)
        val filterValue = reqData?.get(FILTER_VALUE) as? String
        return Pair(filterType, filterValue)
    }


    private fun getRepoTypes(repoType: String? = null): List<String>? {
        return when (repoType) {
            RepositoryType.DOCKER.name -> {
                DOCKER_TYPES
            }
            null -> null
            else -> listOf(repoType)
        }
    }

    private fun filterRepo(repoTypeList: List<String>?, repoName: String?, repoMetrics: RepoMetrics): Boolean {
        return if (repoTypeList.isNullOrEmpty()) {
            if (repoName.isNullOrEmpty()) {
                true
            } else {
                repoMetrics.repoName == repoName
            }
        } else {
            if (repoName.isNullOrEmpty()) {
                repoMetrics.type in repoTypeList
            } else {
                repoMetrics.type in repoTypeList && repoMetrics.repoName == repoName
            }
        }
    }

    private fun addProjectMetrics(
        metrics: Metrics, projectMetrics: TProjectMetrics,
        tmpMap: HashMap<String, Long>
    ) {
        when (metrics) {
            Metrics.PROJECTNODESIZE -> {
                tmpMap[projectMetrics.projectId] = projectMetrics.capSize
            }
            Metrics.PROJECTNODENUM -> {
                tmpMap[projectMetrics.projectId] = projectMetrics.nodeNum
            }
            Metrics.CAPSIZE -> {
                val current = tmpMap[DEFAULT_KEY] ?: 0
                tmpMap[DEFAULT_KEY] = projectMetrics.capSize + current
            }
            Metrics.NODENUM -> {
                val current = tmpMap[DEFAULT_KEY] ?: 0
                tmpMap[DEFAULT_KEY] = projectMetrics.nodeNum + current
            }
            else -> {}
        }
    }

    private fun addRepoMetrics(
        metrics: Metrics, projectId: String, repoMetrics: RepoMetrics,
        tmpMap: HashMap<String, Long>
    ) {
        when (metrics) {
            Metrics.PROJECTNODESIZE -> {
                val current = tmpMap[projectId] ?: 0
                tmpMap[projectId] = current + repoMetrics.size
            }
            Metrics.PROJECTNODENUM -> {
                val current = tmpMap[projectId] ?: 0
                tmpMap[projectId] = current + repoMetrics.num
            }
            Metrics.CAPSIZE -> {
                val current = tmpMap[DEFAULT_KEY] ?: 0
                tmpMap[DEFAULT_KEY] = repoMetrics.size + current
            }
            Metrics.NODENUM -> {
                val current = tmpMap[DEFAULT_KEY] ?: 0
                tmpMap[DEFAULT_KEY] = repoMetrics.num + current
            }
            else -> {}
        }
    }

    companion object {
        private const val DEFAULT_KEY = "ALL*"
    }
}