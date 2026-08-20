package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.openhtmltopdf.java2d.api.BufferedImagePageProcessor
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Markdown 缩略图：commonmark-java 转 HTML，再由 OpenHTMLtoPDF Java2D 渲成首屏图像。
 */
internal object MarkdownThumbnailRenderer {

    fun render(input: Path): BufferedImage? {
        val markdown = stripFrontMatter(ThumbnailPreviewText.read(input, MAX_PREVIEW_BYTES)).trim()
        if (markdown.isEmpty()) {
            return null
        }
        val body = HTML_RENDERER.render(PARSER.parse(markdown)).trim()
        if (body.isEmpty()) {
            return null
        }
        return cropToPage(renderHtml(wrapDocument(body)))
    }

    private fun stripFrontMatter(source: String): String {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').lines()
        if (lines.firstOrNull()?.trim() != FRONT_MATTER_MARK) {
            return source
        }
        val end = lines.withIndex().firstOrNull { (index, line) ->
            index > 0 && line.trim() == FRONT_MATTER_MARK
        }?.index ?: return source
        return lines.subList(end + 1, lines.size).joinToString("\n")
    }

    private fun wrapDocument(body: String): String {
        return """
            <html>
            <head>
            <meta charset="UTF-8"/>
            <style>
            @page { size: ${PAGE_WIDTH}px ${PAGE_HEIGHT}px; margin: 0; }
            html, body { margin: 0; padding: 0; background: #ffffff; }
            body {
              font-family: sans-serif;
              font-size: 16px;
              line-height: 1.5;
              color: #333333;
              padding: 32px;
            }
            h1, h2, h3, h4, h5, h6 { color: #1a1a1a; margin: 0 0 12px 0; }
            h1 { font-size: 28px; }
            h2 { font-size: 22px; }
            h3, h4, h5, h6 { font-size: 18px; }
            p, ul, ol, pre, blockquote, table { margin: 0 0 12px 0; }
            pre, code { font-family: monospace; background: #f4f4f4; }
            pre { padding: 12px; white-space: pre-wrap; }
            blockquote { border-left: 4px solid #d0d0d0; padding-left: 12px; color: #555555; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid #d0d0d0; padding: 4px 8px; }
            img { display: none; }
            </style>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderHtml(html: String): BufferedImage {
        val processor = BufferedImagePageProcessor(BufferedImage.TYPE_INT_RGB, RENDER_SCALE)
        val builder = Java2DRendererBuilder()
        builder.withHtmlContent(html, EMPTY_BASE_URI)
        builder.useFastMode()
        builder.useEnvironmentFonts(true)
        builder.useExternalResourceAccessControl(
            { _, _ -> false },
            ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI,
        )
        builder.useExternalResourceAccessControl(
            { _, _ -> false },
            ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI,
        )
        builder.toSinglePage(processor)
        builder.runFirstPage()
        return processor.pageImages.firstOrNull()
            ?: error("openhtmltopdf produced no image")
    }

    private fun cropToPage(image: BufferedImage): BufferedImage {
        val width = min(image.width, (PAGE_WIDTH * RENDER_SCALE).roundToInt())
        val height = min(image.height, (PAGE_HEIGHT * RENDER_SCALE).roundToInt())
        if (width == image.width && height == image.height) {
            return image
        }
        val cropped = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        ThumbnailGraphics.use(cropped) { graphics ->
            graphics.drawImage(image, 0, 0, null)
        }
        return cropped
    }

    private const val MAX_PREVIEW_BYTES = 8 * 1024
    private const val PAGE_WIDTH = 1280
    private const val PAGE_HEIGHT = 720
    private const val RENDER_SCALE = 2.0
    private const val FRONT_MATTER_MARK = "---"
    private const val EMPTY_BASE_URI = "about:blank"
    private val EXTENSIONS = listOf(TablesExtension.create())
    private val PARSER = Parser.builder().extensions(EXTENSIONS).build()
    private val HTML_RENDERER = HtmlRenderer.builder()
        .extensions(EXTENSIONS)
        .escapeHtml(true)
        .build()
}
