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

package com.tencent.bkrepo.repository.pojo.metrics

import io.swagger.v3.oas.annotations.media.Schema

/**
 * UI客户端指标上报请求
 */
@Schema(description = "UI客户端指标上报请求")
data class UiMetricsReportRequest(
    @Schema(description = "指标列表")
    val metrics: List<UiMetricsContent>
)

/**
 * UI客户端上报的指标内容
 */
@Schema(description = "UI客户端上报的指标内容")
data class UiMetricsContent(
    @Schema(description = "指标名称", required = true, example = "ui_download_speed")
    val metricName: String,
    @Schema(description = "指标描述", required = true, example = "UI客户端下载速度")
    val metricHelp: String,
    @Schema(
        description = "数据模型类型，可选值：DATAMODEL_COUNTER, DATAMODEL_GAUGE, DATAMODEL_HISTOGRAM, DATAMODEL_SUMMARY",
        required = true,
        example = "DATAMODEL_GAUGE"
    )
    val metricDataModel: String,
    @Schema(description = "指标值", required = true, example = "1024.5")
    val value: String,
    @Schema(description = "是否保留历史数据", example = "true")
    val keepHistory: Boolean = true,
    @Schema(description = "自定义标签，如项目ID、仓库名等")
    val labels: MutableMap<String, String> = mutableMapOf()
)
