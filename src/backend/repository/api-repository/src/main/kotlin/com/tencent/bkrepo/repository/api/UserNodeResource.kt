package com.tencent.bkrepo.repository.api

import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.artifact.api.ArtifactPathVariable
import com.tencent.bkrepo.common.artifact.api.DefaultArtifactInfo.Companion.DEFAULT_MAPPING_URI
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.repository.pojo.node.NodeDetail
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.NodeSizeInfo
import com.tencent.bkrepo.repository.pojo.node.user.UserNodeCopyRequest
import com.tencent.bkrepo.repository.pojo.node.user.UserNodeMoveRequest
import com.tencent.bkrepo.repository.pojo.node.user.UserNodeRenameRequest
import com.tencent.bkrepo.repository.pojo.node.user.UserNodeUpdateRequest
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * 用户节点服务接口
 *
 * @author: carrypan
 * @date: 2019-11-18
 */
@Api("用户节点服务接口")
@RequestMapping("/api/node")
interface UserNodeResource {

    @ApiOperation("根据路径查看节点详情")
    @GetMapping(DEFAULT_MAPPING_URI)
    fun detail(
        @RequestAttribute userId: String,
        @ArtifactPathVariable artifactInfo: ArtifactInfo
    ): Response<NodeDetail>

    @ApiOperation("创建文件夹")
    @PostMapping(DEFAULT_MAPPING_URI)
    fun mkdir(
        @RequestAttribute userId: String,
        @ArtifactPathVariable artifactInfo: ArtifactInfo
    ): Response<Void>

    @ApiOperation("删除节点")
    @DeleteMapping(DEFAULT_MAPPING_URI)
    fun delete(
        @RequestAttribute userId: String,
        @ArtifactPathVariable artifactInfo: ArtifactInfo
    ): Response<Void>

    @ApiOperation("更新节点")
    @PostMapping("/update")
    fun update(
        @RequestAttribute userId: String,
        @RequestBody request: UserNodeUpdateRequest
    ): Response<Void>

    @ApiOperation("重命名节点")
    @PostMapping("/rename")
    fun rename(
        @RequestAttribute userId: String,
        @RequestBody request: UserNodeRenameRequest
    ): Response<Void>

    @ApiOperation("移动节点")
    @PostMapping("/move")
    fun move(
        @RequestAttribute userId: String,
        @RequestBody request: UserNodeMoveRequest
    ): Response<Void>

    @ApiOperation("复制节点")
    @PostMapping("/copy")
    fun copy(
        @RequestAttribute userId: String,
        @RequestBody request: UserNodeCopyRequest
    ): Response<Void>

    @ApiOperation("查询节点大小信息")
    @GetMapping("/size/$DEFAULT_MAPPING_URI")
    fun computeSize(
        @RequestAttribute userId: String,
        @ArtifactPathVariable artifactInfo: ArtifactInfo
    ): Response<NodeSizeInfo>

    @ApiOperation("列表文件")
    @GetMapping("/page/$DEFAULT_MAPPING_URI")
    fun page(
        @RequestAttribute userId: String,
        @ArtifactPathVariable artifactInfo: ArtifactInfo,
        @ApiParam(value = "当前页", required = true, defaultValue = "0")
        @RequestParam page: Int = 0,
        @ApiParam(value = "分页大小", required = true, defaultValue = "20")
        @RequestParam size: Int = 20,
        @ApiParam("是否包含目录", required = false, defaultValue = "true")
        @RequestParam includeFolder: Boolean = true,
        @ApiParam("是否包含元数据", required = false, defaultValue = "false")
        @RequestParam includeMetadata: Boolean = false,
        @ApiParam("是否深度查询文件", required = false, defaultValue = "false")
        @RequestParam deep: Boolean = false
    ): Response<Page<NodeInfo>>

    @ApiOperation("自定义查询节点")
    @PostMapping("/query")
    fun query(
        @RequestAttribute userId: String,
        @RequestBody queryModel: QueryModel
    ): Response<Page<Map<String, Any>>>
}
