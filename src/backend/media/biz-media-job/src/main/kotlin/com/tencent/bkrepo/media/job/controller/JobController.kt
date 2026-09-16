package com.tencent.bkrepo.media.job.controller

import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.security.permission.Principal
import com.tencent.bkrepo.common.security.permission.PrincipalType
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.media.job.service.TranscodeJobService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 管理员任务控制器
 * */
@RestController
@RequestMapping("/api/media/job")
@Principal(type = PrincipalType.ADMIN)
class JobController(
    private val transcodeJobService: TranscodeJobService
) {
    /**
     * 重置转码任务为 WAITING 重新排队
     * @param ids 任务 id 集合，不传或为空则重置全部卡在队列中的任务（QUEUE/INIT/RUNNING）
     * @return 重置的任务数量
     */
    @PutMapping("/restart")
    fun restart(
        @RequestAttribute userId: String,
        @RequestBody(required = false) ids: Set<String>?,
    ): Response<Long> {
        return ResponseBuilder.success(transcodeJobService.restartJob(ids ?: emptySet()))
    }
}
