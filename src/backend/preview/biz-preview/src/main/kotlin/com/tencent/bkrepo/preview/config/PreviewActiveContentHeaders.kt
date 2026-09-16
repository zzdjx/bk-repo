package com.tencent.bkrepo.preview.config

import com.tencent.bkrepo.common.artifact.path.PathUtils
import com.tencent.bkrepo.common.service.util.HttpContextHolder

/**
 * SVG 原文 inline 预览时补安全头：保留图片/样式展示，禁止 script 执行，避免存储型 XSS。
 */
object PreviewActiveContentHeaders {
    private const val SVG_EXTENSION = "svg"
    private const val SVG_CONTENT_TYPE = "image/svg+xml"

    /**
     * 允许静态样式/图片，限制 script/object 执行。
     */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; style-src 'unsafe-inline' 'self'; " +
            "img-src 'self' data: https: http:; font-src 'self' data:; " +
            "script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"

    const val HEADER_X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    const val HEADER_CONTENT_SECURITY_POLICY = "Content-Security-Policy"
    const val NOSNIFF = "nosniff"

    fun applyIfActiveContent(artifactName: String, contentType: String? = null) {
        if (!isSvgContent(artifactName, contentType)) {
            return
        }
        val response = HttpContextHolder.getResponseOrNull() ?: return
        response.setHeader(HEADER_X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        response.setHeader(HEADER_CONTENT_SECURITY_POLICY, CONTENT_SECURITY_POLICY)
    }

    fun isSvgContent(artifactName: String, contentType: String? = null): Boolean {
        val extension = PathUtils.resolveExtension(artifactName).lowercase()
        if (extension == SVG_EXTENSION) {
            return true
        }
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return mime == SVG_CONTENT_TYPE
    }
}
