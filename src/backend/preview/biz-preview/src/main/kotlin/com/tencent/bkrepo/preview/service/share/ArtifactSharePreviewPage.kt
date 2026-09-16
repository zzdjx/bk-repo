package com.tencent.bkrepo.preview.service.share

import org.springframework.web.util.HtmlUtils

/**
 * 短链 `/a/{shareId}` 与 `/share/{shortShareId}` 返回的 HTML。
 * 避免 302 把地址栏换成带 token 的预览 URL。
 *
 * 原生 PDF 不走 iframe：由短链直接输出 application/pdf，否则阅读器只是 iframe 文档，「演示」不可用。
 * 幻灯片等其它类型走本页 iframe，由 Vue 预览页用 PDF.js 渲染。
 */
object ArtifactSharePreviewPage {

    fun render(previewUrl: String, title: String): String {
        val src = HtmlUtils.htmlEscape(previewUrl, Charsets.UTF_8.name())
        val safeTitle = htmlTitle(title)
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$safeTitle</title>
            <style>
            html,body,iframe{margin:0;padding:0;height:100%;width:100%;border:0}
            body{overflow:hidden}
            </style>
            </head>
            <body>
            <iframe src="$src" title="$safeTitle" allow="autoplay; fullscreen; encrypted-media; picture-in-picture"></iframe>
            </body>
            </html>
        """.trimIndent()
    }

    private fun htmlTitle(title: String): String {
        return HtmlUtils.htmlEscape(title.ifBlank { DEFAULT_TITLE }, Charsets.UTF_8.name())
    }

    private const val DEFAULT_TITLE = "预览"
}
