package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.common.artifact.util.http.HttpHeaderUtils

/**
 * 内联预览的 Content-Disposition。Chrome 工具栏文件名看 PDF Title / URL，
 * 这个头给下载按钮用。
 */
object PreviewInlineDisposition {

    fun encode(filename: String): String {
        return HttpHeaderUtils.encodeDisposition(filename).replaceFirst("attachment", "inline")
    }

    /**
     * 优先用原路径文件名；没有路径名时再用分享展示名。
     */
    fun resolveFileName(fullPath: String?, artifactName: String?): String {
        val pathName = fullPath?.substringAfterLast('/')?.trim().orEmpty()
        if (pathName.isNotEmpty()) {
            return pathName
        }
        return artifactName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FILE_NAME
    }

    private const val DEFAULT_FILE_NAME = "preview.pdf"
}
