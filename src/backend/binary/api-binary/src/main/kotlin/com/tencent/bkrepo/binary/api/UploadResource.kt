package com.tencent.bkrepo.binary.api

import com.tencent.bkrepo.binary.constant.SERVICE_NAME
import com.tencent.bkrepo.binary.pojo.SimpleUploadRequest
import com.tencent.bkrepo.binary.pojo.UploadPrecheckRequest
import com.tencent.bkrepo.common.api.pojo.Response
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.multipart.MultipartFile

/**
 * 元数据服务接口
 *
 * @author: carrypan
 * @date: 2019-09-10
 */
@Api("上传接口")
@FeignClient(SERVICE_NAME, contextId = "UploadResource")
@RequestMapping("/upload")
interface UploadResource {

    @ApiOperation("简单上传")
    @PostMapping("/simple")
    fun simple(
        request: SimpleUploadRequest,
        @ApiParam("文件", required = true)
        file: MultipartFile
    ): Response<String>

    @ApiOperation("分块上传预检")
    @GetMapping("/precheck")
    fun precheck(request: UploadPrecheckRequest): Response<String>



}
