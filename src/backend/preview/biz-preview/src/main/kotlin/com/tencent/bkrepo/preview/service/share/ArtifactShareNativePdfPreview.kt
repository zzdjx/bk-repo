package com.tencent.bkrepo.preview.service.share

import com.tencent.bkrepo.common.artifact.api.ArtifactInfo
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.preview.constant.PREVIEW_RESPONSE_FILENAME
import com.tencent.bkrepo.preview.pojo.share.ArtifactShareOpenInfo
import com.tencent.bkrepo.preview.service.FileHandlerService
import com.tencent.bkrepo.preview.service.FilePreviewFactory
import com.tencent.bkrepo.preview.service.PreviewInlineDisposition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 分享短链把原生 PDF 作为当前响应当页输出，
 * 让浏览器内置阅读器成为顶层文档，从而可以使用「演示」。
 * 幻灯片仍走内嵌预览页，由前端 PDF.js 渲染转换结果。
 */
@Component
class ArtifactShareNativePdfPreview(
    private val fileHandlerService: FileHandlerService,
    private val previewFactory: FilePreviewFactory,
) {

    fun writeIfApplicable(openInfo: ArtifactShareOpenInfo): Boolean {
        val share = openInfo.share
        if (!ArtifactShareTypes.usesNativePdfViewer(share.type, share.fullPath)) {
            return false
        }
        val artifactInfo = ArtifactInfo(share.projectId, share.repoName, share.fullPath)
        val fileAttribute = fileHandlerService.getFileAttribute(artifactInfo, null)
        HttpContextHolder.getRequestOrNull()?.setAttribute(
            PREVIEW_RESPONSE_FILENAME,
            PreviewInlineDisposition.resolveFileName(share.fullPath, share.artifactName),
        )
        logger.info(
            "artifact share native pdf preview: shareId=[${share.shareId}], type=[${share.type}], " +
                "projectId=[${share.projectId}], repoName=[${share.repoName}]",
        )
        previewFactory.get(fileAttribute).filePreviewHandle(fileAttribute)
        return true
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtifactShareNativePdfPreview::class.java)
    }
}
