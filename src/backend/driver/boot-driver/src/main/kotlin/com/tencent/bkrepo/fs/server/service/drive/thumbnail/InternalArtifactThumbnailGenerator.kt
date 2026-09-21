package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.poi.sl.usermodel.SlideShowFactory
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Driver 内部实现：`image`（栅格图按图片长边缩放；SVG 按文档长边栅格化，支持 SVG2 滤镜）缩放 JPEG，`pdf`/`slides`（ppt/pptx）渲染首页，
 * `table`（xls/xlsx/csv）按文档长边渲首屏网格，`video`（mp4/webm）抽第一帧，
 * `markdown`（md/markdown）渲首屏文本，`html` 在 Playwright 沙箱里截首屏。
 * 其它类型返回 Failed。
 */
@Component
class InternalArtifactThumbnailGenerator(
    private val properties: DriveThumbnailProperties,
    private val htmlRenderer: HtmlThumbnailRenderer,
) : ArtifactThumbnailGenerator {

    override fun generate(
        input: Path,
        artifactType: String,
        output: Path,
    ): ThumbnailGenerateResult {
        if (ThumbnailAbort.isCancelled()) {
            return ThumbnailGenerateResult.Failed("timeout")
        }
        return runCatching {
            when (artifactType) {
                ArtifactThumbnailTypes.TYPE_IMAGE -> renderImage(input, output)
                ArtifactThumbnailTypes.TYPE_PDF -> renderPdf(input, output)
                ArtifactThumbnailTypes.TYPE_SLIDES -> renderSlides(input, output)
                ArtifactThumbnailTypes.TYPE_TABLE ->
                    renderPreview(input, output, artifactType, properties.documentMaxEdge, preserveChroma = true) {
                        TableThumbnailRenderer.render(it, properties.documentMaxEdge)
                    }
                ArtifactThumbnailTypes.TYPE_VIDEO -> renderVideo(input, output)
                ArtifactThumbnailTypes.TYPE_MARKDOWN ->
                    renderPreview(input, output, artifactType, properties.documentMaxEdge, preserveChroma = true) {
                        MarkdownThumbnailRenderer.render(it)
                    }
                ArtifactThumbnailTypes.TYPE_HTML ->
                    renderPreview(input, output, artifactType, properties.htmlMaxEdge, preserveChroma = true) {
                        htmlRenderer.render(it)
                    }
                else -> ThumbnailGenerateResult.Failed("internal engine does not support type[$artifactType]")
            }
        }.getOrElse { e ->
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (ThumbnailAbort.isCancelled() || e is InterruptedException) {
                Files.deleteIfExists(output)
                return ThumbnailGenerateResult.Failed("timeout")
            }
            logger.warn("Internal thumbnail failed type[$artifactType] for [$input]: ${e.message}")
            ThumbnailGenerateResult.Failed(e.message ?: "internal generate failed")
        }
    }

    private fun renderPreview(
        input: Path,
        output: Path,
        artifactType: String,
        maxEdge: Int = properties.maxEdge,
        preserveChroma: Boolean = false,
        render: (Path) -> BufferedImage?,
    ): ThumbnailGenerateResult {
        if (ThumbnailAbort.isCancelled()) {
            return ThumbnailGenerateResult.Failed("timeout")
        }
        val rendered = render(input) ?: return ThumbnailGenerateResult.Failed("empty $artifactType[$input]")
        return writeScaledJpeg(rendered, output, maxEdge, preserveChroma)
    }

    private fun renderImage(input: Path, output: Path): ThumbnailGenerateResult {
        if (SvgThumbnailRenderer.looksLikeSvg(input)) {
            val source = SvgThumbnailRenderer.render(input, properties.documentMaxEdge)
                ?: return ThumbnailGenerateResult.Failed("unreadable svg[$input]")
            return writeScaledJpeg(source, output, properties.documentMaxEdge, preserveChroma = true)
        }
        val source = readSubsampled(input, properties.maxEdge)
            ?: return ThumbnailGenerateResult.Failed("unreadable image[$input]")
        return writeScaledJpeg(source, output)
    }

    /**
     * 按缩略图目标长边降采样解码，避免把整张原图的 raster 读进堆内存。
     */
    private fun readSubsampled(input: Path, maxEdge: Int): BufferedImage? {
        ImageIO.setUseCache(false)
        val stream = ImageIO.createImageInputStream(input.toFile()) ?: return null
        return stream.useAbortable {
            val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: return@useAbortable null
            try {
                reader.setInput(it, true, true)
                val param = reader.defaultReadParam
                val sampling = samplingFactor(reader.getWidth(0), reader.getHeight(0), maxEdge)
                if (sampling > 1) {
                    param.setSourceSubsampling(sampling, sampling, 0, 0)
                }
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * 取不会让长边落到 [maxEdge] 以下的最大整数倍率，给后续高质量缩放留出像素余量。
     */
    private fun samplingFactor(width: Int, height: Int, maxEdge: Int): Int {
        val edge = maxEdge.coerceAtLeast(1)
        val longest = max(width, height)
        return if (longest <= edge) 1 else longest / edge
    }

    private fun renderPdf(input: Path, output: Path): ThumbnailGenerateResult {
        return PDDocument.load(input.toFile()).useAbortable { document ->
            if (document.numberOfPages < 1) {
                ThumbnailGenerateResult.Failed("empty pdf[$input]")
            } else {
                val page = document.getPage(0)
                val box = page.cropBox
                val pageMaxPoints = max(box.width, box.height).coerceAtLeast(1f)
                val dpi = (POINTS_PER_INCH * properties.documentMaxEdge / pageMaxPoints).coerceIn(MIN_DPI, MAX_DPI)
                val rendered = PDFRenderer(document).renderImageWithDPI(0, dpi, ImageType.RGB)
                writeScaledJpeg(rendered, output, properties.documentMaxEdge, preserveChroma = true)
            }
        }
    }

    private fun renderSlides(input: Path, output: Path): ThumbnailGenerateResult {
        return SlideShowFactory.create(input.toFile(), null, true).useAbortable { show ->
            val slides = show.slides
            if (slides.isEmpty()) {
                return@useAbortable ThumbnailGenerateResult.Failed("empty slides[$input]")
            }
            val page = show.pageSize
            val size = scaledSize(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1), properties.documentMaxEdge)
            val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
            ThumbnailGraphics.use(image) { graphics ->
                ThumbnailGraphics.fillWhite(graphics, size.width, size.height)
                ThumbnailGraphics.applyQualityHints(graphics, text = true)
                graphics.scale(size.scale, size.scale)
                slides[0].draw(graphics)
            }
            finishJpeg(image, output, preserveChroma = true)
        }
    }

    private fun renderVideo(input: Path, output: Path): ThumbnailGenerateResult {
        var lastError: String? = null
        for (format in videoInputFormats(input)) {
            if (ThumbnailAbort.isCancelled()) {
                return ThumbnailGenerateResult.Failed("timeout")
            }
            try {
                return grabVideoFrame(input, output, format)
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (ThumbnailAbort.isCancelled() || e is InterruptedException) {
                    return ThumbnailGenerateResult.Failed("timeout")
                }
                lastError = e.message ?: e.javaClass.simpleName
                logger.warn(
                    "Internal thumbnail failed type[video] format[${format ?: FORMAT_AUTO}] " +
                        "for [$input]: ${e.message}",
                )
            }
        }
        return ThumbnailGenerateResult.Failed(lastError ?: "unreadable video[$input]")
    }

    private fun grabVideoFrame(input: Path, output: Path, format: String?): ThumbnailGenerateResult {
        return FFmpegFrameGrabber(input.toFile()).useAbortable { grabber ->
            format?.let { grabber.format = it }
            grabber.setOption(OPTION_PROBE_SIZE, VIDEO_PROBE_SIZE)
            grabber.setOption(OPTION_ANALYZE_DURATION, VIDEO_ANALYZE_DURATION)
            grabber.start()
            val frame = grabFirstImageFrame(grabber)
                ?: return@useAbortable ThumbnailGenerateResult.Failed("no video frame[$input]")
            val source = Java2DFrameConverter().use { converter ->
                converter.convert(frame)
            } ?: return@useAbortable ThumbnailGenerateResult.Failed("unreadable video frame[$input]")
            writeScaledJpeg(source, output)
        }
    }

    private fun videoInputFormats(input: Path): List<String?> {
        return buildList {
            ffmpegFormatOf(input.fileName.toString())?.let(::add)
            add(FORMAT_MP4)
            add(FORMAT_WEBM)
            add(null)
        }.distinct()
    }

    private fun ffmpegFormatOf(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return VIDEO_FORMAT_BY_EXT[ext]
    }

    private fun grabFirstImageFrame(grabber: FFmpegFrameGrabber): Frame? {
        repeat(MAX_VIDEO_FRAME_ATTEMPTS) {
            if (ThumbnailAbort.isCancelled()) {
                return null
            }
            val frame = grabber.grabImage() ?: return null
            if (frame.image != null) {
                return frame
            }
        }
        return null
    }

    private fun scaledSize(srcWidth: Int, srcHeight: Int, maxEdge: Int = properties.maxEdge): ScaledSize {
        val edge = maxEdge.coerceAtLeast(1)
        val scale = min(1.0, edge.toDouble() / max(srcWidth, srcHeight).coerceAtLeast(1))
        return ScaledSize(
            width = max(1, (srcWidth * scale).roundToInt()),
            height = max(1, (srcHeight * scale).roundToInt()),
            scale = scale,
        )
    }

    private fun scaleToMaxEdge(source: BufferedImage, maxEdge: Int = properties.maxEdge): BufferedImage {
        val size = scaledSize(source.width, source.height, maxEdge)
        if (size.width == source.width && size.height == source.height) {
            return toRgb(source)
        }
        var current = source
        var width = source.width
        var height = source.height
        while (width / 2 >= size.width && height / 2 >= size.height) {
            width = (width / 2).coerceAtLeast(size.width)
            height = (height / 2).coerceAtLeast(size.height)
            current = resample(current, width, height)
        }
        if (width != size.width || height != size.height || current.type != BufferedImage.TYPE_INT_RGB) {
            current = resample(current, size.width, size.height)
        }
        return current
    }

    private fun toRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) {
            return source
        }
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        ThumbnailGraphics.use(rgb) { graphics ->
            ThumbnailGraphics.fillWhite(graphics, source.width, source.height)
            graphics.drawImage(source, 0, 0, null)
        }
        return rgb
    }

    private fun resample(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        ThumbnailGraphics.use(scaled) { graphics ->
            ThumbnailGraphics.fillWhite(graphics, width, height)
            ThumbnailGraphics.applyQualityHints(graphics)
            graphics.drawImage(source, 0, 0, width, height, null)
        }
        return scaled
    }

    private fun writeScaledJpeg(
        image: BufferedImage,
        output: Path,
        maxEdge: Int = properties.maxEdge,
        preserveChroma: Boolean = false,
    ): ThumbnailGenerateResult {
        return finishJpeg(scaleToMaxEdge(image, maxEdge), output, preserveChroma)
    }

    private fun finishJpeg(image: BufferedImage, output: Path, preserveChroma: Boolean): ThumbnailGenerateResult {
        if (ThumbnailAbort.isCancelled()) {
            Files.deleteIfExists(output)
            return ThumbnailGenerateResult.Failed("timeout")
        }
        writeJpeg(image, output, preserveChroma)
        if (ThumbnailAbort.isCancelled()) {
            Files.deleteIfExists(output)
            return ThumbnailGenerateResult.Failed("timeout")
        }
        return ThumbnailGenerateResult.Success
    }

    private fun writeJpeg(image: BufferedImage, output: Path, preserveChroma: Boolean = false) {
        if (ThumbnailAbort.isCancelled()) {
            Files.deleteIfExists(output)
            return
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: error("no jpeg ImageWriter available")
        ImageIO.createImageOutputStream(output.toFile()).use { stream ->
            try {
                writer.output = stream
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = properties.jpegQuality.coerceIn(MIN_QUALITY, MAX_QUALITY)
                }
                val metadata = jpegMetadata(writer, image, param, preserveChroma)
                writer.write(null, IIOImage(image, null, metadata), param)
            } finally {
                writer.dispose()
            }
        }
    }

    private fun jpegMetadata(
        writer: ImageWriter,
        image: BufferedImage,
        param: ImageWriteParam,
        preserveChroma: Boolean,
    ): IIOMetadata? {
        if (!preserveChroma) {
            return null
        }
        return runCatching {
            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param)
                ?: return@runCatching null
            if (JPEG_NATIVE_METADATA !in metadata.metadataFormatNames) {
                return@runCatching metadata
            }
            val tree = metadata.getAsTree(JPEG_NATIVE_METADATA)
            disableChromaSubsampling(tree)
            metadata.setFromTree(JPEG_NATIVE_METADATA, tree)
            metadata
        }.getOrElse { e ->
            logger.warn("Thumbnail JPEG 4:4:4 metadata failed: ${e.message}")
            null
        }
    }

    private fun disableChromaSubsampling(node: Node) {
        var child = node.firstChild
        while (child != null) {
            when {
                node.nodeName != SOF_NODE -> disableChromaSubsampling(child)
                child is Element && child.nodeName == COMPONENT_SPEC_NODE -> {
                    child.setAttribute(H_SAMPLING_FACTOR, "1")
                    child.setAttribute(V_SAMPLING_FACTOR, "1")
                }
            }
            child = child.nextSibling
        }
    }

    private data class ScaledSize(val width: Int, val height: Int, val scale: Double)

    companion object {
        private const val MIN_QUALITY = 0.1f
        private const val MAX_QUALITY = 1.0f
        private const val POINTS_PER_INCH = 72f
        private const val MIN_DPI = 36f
        private const val MAX_DPI = 300f
        private const val MAX_VIDEO_FRAME_ATTEMPTS = 30
        private const val FORMAT_MP4 = "mp4"
        private const val FORMAT_WEBM = "webm"
        private const val FORMAT_AUTO = "auto"
        private const val OPTION_PROBE_SIZE = "probesize"
        private const val OPTION_ANALYZE_DURATION = "analyzeduration"
        private const val VIDEO_PROBE_SIZE = "10000000"
        private const val VIDEO_ANALYZE_DURATION = "10000000"
        private const val JPEG_NATIVE_METADATA = "javax_imageio_jpeg_image_1.0"
        private const val SOF_NODE = "sof"
        private const val COMPONENT_SPEC_NODE = "componentSpec"
        private const val H_SAMPLING_FACTOR = "HsamplingFactor"
        private const val V_SAMPLING_FACTOR = "VsamplingFactor"
        private val VIDEO_FORMAT_BY_EXT = mapOf(
            "mp4" to FORMAT_MP4,
            "m4v" to FORMAT_MP4,
            "mov" to FORMAT_MP4,
            "webm" to FORMAT_WEBM,
        )
        private val logger = LoggerFactory.getLogger(InternalArtifactThumbnailGenerator::class.java)

        init {
            FFmpegLogCallback.set()
        }
    }
}
