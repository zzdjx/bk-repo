/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2024 Tencent.  All rights reserved.
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

package com.tencent.bkrepo.repository.service.metrics

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.metrics.push.custom.CustomMetricsExporter
import com.tencent.bkrepo.common.metrics.push.custom.base.MetricsItem
import com.tencent.bkrepo.common.metrics.push.custom.enums.DataModel
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.repository.pojo.metrics.UiMetricsReportRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * UI客户端指标上报服务
 *
 * 参考 driver 模块中 ClientService.pushMetrics 的实现，
 * 为 UI 客户端提供下载速度、运营数据等自定义指标的上报能力。
 */
@Service
class UiMetricsService(
    private val customMetricsExporter: CustomMetricsExporter? = null
) {

    /**
     * 上报UI客户端自定义指标
     *
     * @param userId 当前用户ID
     * @param request 指标上报请求
     */
    fun pushMetrics(userId: String, request: UiMetricsReportRequest) {
        val clientIp = HttpContextHolder.getClientAddress()
        with(request) {
            metrics.forEach {
                val newLabels = mutableMapOf<String, String>()
                newLabels.putAll(it.labels)
                newLabels["clientIp"] = clientIp
                newLabels["userId"] = userId
                val metricItem = try {
                    MetricsItem(
                        it.metricName, it.metricHelp, DataModel.valueOf(it.metricDataModel),
                        it.keepHistory, it.value.toDouble(), newLabels
                        )
                } catch (e: Exception) {
                    logger.warn("Invalid UI metrics content: $it", e)
                    throw ErrorCodeException(CommonMessageCode.REQUEST_CONTENT_INVALID, it)
                }
                customMetricsExporter?.reportMetrics(metricItem)
            }
        }
        logger.info(
            "User[$userId] from[$clientIp] reported ${request.metrics.size} UI metrics"
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UiMetricsService::class.java)
    }
}
