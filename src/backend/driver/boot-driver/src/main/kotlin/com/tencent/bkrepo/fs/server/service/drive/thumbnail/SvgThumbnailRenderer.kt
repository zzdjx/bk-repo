package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.parser.resources.ResourcePolicy
import com.github.weisj.jsvg.renderer.SVGRenderingHints
import com.github.weisj.jsvg.view.ViewBox
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SVG 缩略图：JSVG 栅格化成位图，原生支持 SVG2 滤镜（如 `feDropShadow`）。
 * 按文档长边输出，避免卡片放大后文字发糊。只读源文件，不改写节点。
 * 脚本不执行；外链拒绝，仅允许 `data:` 内嵌资源。
 */
internal object SvgThumbnailRenderer {

    fun looksLikeSvg(input: Path): Boolean {
        return runCatching {
            SVG_MARK.containsMatchIn(ThumbnailPreviewText.read(input, SNIFF_BYTES))
        }.getOrDefault(false)
    }

    fun render(input: Path, maxEdge: Int): BufferedImage? {
        val size = Files.size(input)
        if (size <= 0L || size > MAX_SVG_BYTES) {
            return null
        }
        val document = load(input) ?: return null
        return rasterize(document, maxEdge.coerceAtLeast(1))
    }

    private fun load(input: Path): SVGDocument? {
        return Files.newInputStream(input).use { raw ->
            val stream = BufferedInputStream(raw)
            ThumbnailPreviewText.skipUtf8Bom(stream)
            SVGLoader().load(stream, BLANK_BASE, LOADER_CONTEXT)
        }
    }

    private fun rasterize(document: SVGDocument, maxEdge: Int): BufferedImage? {
        val size = document.size()
        val width = size.width
        val height = size.height
        if (!width.isRenderableSize() || !height.isRenderableSize()) {
            return null
        }
        val scale = maxEdge / max(width, height)
        val outWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val outHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val image = BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB)
        ThumbnailGraphics.use(image) { graphics ->
            paintSvg(document, graphics, outWidth, outHeight)
        }
        return image
    }

    private fun paintSvg(document: SVGDocument, graphics: Graphics2D, width: Int, height: Int) {
        ThumbnailGraphics.fillWhite(graphics, width, height)
        ThumbnailGraphics.applyQualityHints(graphics, text = true)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.setRenderingHint(SVGRenderingHints.KEY_SOFT_CLIPPING, SVGRenderingHints.VALUE_SOFT_CLIPPING_ON)
        graphics.setRenderingHint(SVGRenderingHints.KEY_CACHE_OFFSCREEN_IMAGE, SVGRenderingHints.VALUE_NO_CACHE)
        document.render(null, graphics, ViewBox(0f, 0f, width.toFloat(), height.toFloat()))
    }

    private fun Float.isRenderableSize(): Boolean = isFinite() && this > 0f

    private const val SNIFF_BYTES = 8192
    private const val MAX_SVG_BYTES = 8 * 1024 * 1024
    private val BLANK_BASE = URI.create("about:blank")
    private val SVG_MARK = Regex("(?i)<svg(?:\\s|/|>)")
    private val LOADER_CONTEXT = LoaderContext.builder()
        .externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL)
        .build()
}
