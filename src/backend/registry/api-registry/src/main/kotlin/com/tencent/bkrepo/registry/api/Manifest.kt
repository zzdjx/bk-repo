package com.tencent.bkrepo.registry.api

// import com.tencent.bkrepo.common.api.pojo.Response
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping

/**
 *  docker image manifest 文件处理接口
 *
 * @author: owenlxu
 * @date: 2019-10-03
 */
@Api("docker镜像manifest文件处理接口")
@RequestMapping("/v2")
interface Manifest {

    @ApiOperation("查看元数据详情")
    @PutMapping("/{name}/manifests/{reference}")
    fun putManifest(
        @PathVariable
        @ApiParam(value = "name", required = true)
        name: String,
        @PathVariable
        @ApiParam(value = "reference", required = true)
        reference: String,
        @ApiParam
        @RequestHeader(value = "Content-Type", required = true)
        contentTypeHeader: String,
        @RequestBody
        @ApiParam(value = "body", required = false)
        body: String
    ): String
}
