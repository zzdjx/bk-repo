/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
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

package com.tencent.bkrepo.webhook.service.impl

import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.mongo.dao.util.Pages
import com.tencent.bkrepo.webhook.dao.WebHookLogDao
import com.tencent.bkrepo.webhook.model.TWebHookLog
import com.tencent.bkrepo.webhook.pojo.ListWebHookLogOption
import com.tencent.bkrepo.webhook.pojo.WebHookLog
import com.tencent.bkrepo.webhook.service.LogService
import com.tencent.bkrepo.webhook.service.WebHookService
import com.tencent.bkrepo.webhook.util.toWebHookLog
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class LogServiceImpl(
    private val webHookLogDao: WebHookLogDao,
    private val webHookService: WebHookService
) : LogService {

    override fun listLog(userId: String, webHookId: String, option: ListWebHookLogOption): Page<WebHookLog> {
        webHookService.getWebHook(userId, webHookId)
        with(option) {
            val startDateTime = LocalDateTime.parse(
                startDate ?: LocalDateTime.now().minusMonths(3).toString()
            )
            val endDateTime = LocalDateTime.parse(endDate ?: LocalDateTime.now().toString())
            val query = Query(
                Criteria.where(TWebHookLog::webHookId.name).isEqualTo(webHookId)
                    .apply {
                        status?.let { and(TWebHookLog::status.name).isEqualTo(it) }
                        and(TWebHookLog::requestTime.name)
                            .gte(startDateTime)
                            .lte(endDateTime)
                    }
            )
            val pageRequest = Pages.ofRequest(pageNumber, pageSize)
            val sort = Sort.by(Sort.Direction.DESC, TWebHookLog::requestTime.name)
            val totalRecords = webHookLogDao.count(query)
            val records = webHookLogDao.find(query.with(sort).with(pageRequest)).map { it.toWebHookLog() }
            return Pages.ofResponse(pageRequest, totalRecords, records)
        }
    }
}
