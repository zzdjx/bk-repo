/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.oci.controller

import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.security.permission.Permission
import com.tencent.bkrepo.oci.pojo.artifact.OciArtifactInfo.Companion.BOLBS_UPLOAD_FIRST_STEP_URL
import com.tencent.bkrepo.oci.pojo.artifact.OciArtifactInfo.Companion.BOLBS_UPLOAD_SECOND_STEP_URL
import com.tencent.bkrepo.oci.pojo.artifact.OciArtifactInfo.Companion.BOLBS_URL
import com.tencent.bkrepo.oci.pojo.artifact.OciBlobArtifactInfo
import com.tencent.bkrepo.oci.service.OciBlobService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * oci blob controller
 */
@RestController
@Suppress("MVCPathVariableInspection")
class OciBlobController(
    val ociBlobService: OciBlobService
) {

    /**
     * 上传blob文件
     * 分为两种情况：
     * 1 当digest参数存在时，是使用single post直接上传文件
     * 2 当digest参数不存在时，使用post and put方式上传文件,此接口返回追加uuid
     */
    @PostMapping(BOLBS_UPLOAD_FIRST_STEP_URL)
    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun startBlobUpload(
        artifactInfo: OciBlobArtifactInfo,
        artifactFile: ArtifactFile
    ) {
        ociBlobService.startUploadBlob(artifactInfo, artifactFile)
    }

    /**
     * 上传blob文件或者是完成上传，通过请求头[User-Agent]来判断
     * 如果正则匹配成功，则进行上传，执行完成则完成；否则使用的是追加上传的方式，完成最后一块的上传进行合并。
     */
    @RequestMapping(method = [RequestMethod.PATCH, RequestMethod.PUT], value = [BOLBS_UPLOAD_SECOND_STEP_URL])
    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun uploadBlob(
        artifactInfo: OciBlobArtifactInfo,
        artifactFile: ArtifactFile
    ) {
        ociBlobService.uploadBlob(artifactInfo, artifactFile)
    }

    /**
     * 获取Blob文件
     */
    @GetMapping(BOLBS_URL)
    @Permission(type = ResourceType.REPO, action = PermissionAction.READ)
    fun downloadBlob(
        artifactInfo: OciBlobArtifactInfo
    ) {
        ociBlobService.downloadBlob(artifactInfo)
    }

    /**
     * 删除manifest文件
     * 只能通过digest删除
     */
    @DeleteMapping(BOLBS_URL)
    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun deleteBlob(
        artifactInfo: OciBlobArtifactInfo
    ) {
        ociBlobService.deleteBlob(artifactInfo)
    }
}
