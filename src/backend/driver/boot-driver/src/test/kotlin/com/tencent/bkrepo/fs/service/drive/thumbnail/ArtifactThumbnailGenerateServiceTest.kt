package com.tencent.bkrepo.fs.service.drive.thumbnail

import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ArtifactThumbnailGenerateService
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ArtifactThumbnailGenerator
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.HtmlThumbnailRenderer
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.InternalArtifactThumbnailGenerator
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ThumbnailAbort
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ThumbnailGenerateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.sl.usermodel.SlideShow
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Node
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

@DisplayName("作品缩略图生成端口")
class ArtifactThumbnailGenerateServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `internal engine resizes image to jpeg within max edge`() = runBlocking {
        val input = writePng(SOURCE_WIDTH, SOURCE_HEIGHT)
        val output = tempDir.resolve("out.jpg")
        val result = service(internal()).generate(
            input = input,
            artifactType = ArtifactThumbnailTypes.TYPE_IMAGE,
            output = output,
        )

        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertEquals(properties().maxEdge, maxOf(image.width, image.height))
        assertTrue(image.width == properties().maxEdge || image.height == properties().maxEdge)
    }

    @Test
    fun `internal engine subsamples oversized image to max edge`() = runBlocking {
        val input = writePng(LARGE_SOURCE_WIDTH, LARGE_SOURCE_HEIGHT)
        val output = tempDir.resolve("large.jpg")
        val result = service(internal()).generate(
            input = input,
            artifactType = ArtifactThumbnailTypes.TYPE_IMAGE,
            output = output,
        )

        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertEquals(properties().maxEdge, image.width)
        assertEquals(properties().maxEdge / 2, image.height)
    }

    @Test
    fun `internal engine fails for invalid svg`() = runBlocking {
        assertGenerateFailed(
            ArtifactThumbnailTypes.TYPE_IMAGE,
            Files.writeString(tempDir.resolve("a.svg"), "not-an-svg"),
        )
    }

    @Test
    fun `internal engine renders svg to jpeg within max edge`() = runBlocking {
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            input = writeSvg(),
            output = tempDir.resolve("icon.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `internal engine renders svg copied to src temp suffix`() = runBlocking {
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            input = copyAsSrc(writeSvg()),
            output = tempDir.resolve("icon-src.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `internal engine renders svg with viewBox only`() = runBlocking {
        val input = Files.writeString(
            tempDir.resolve("viewbox.svg"),
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $SOURCE_WIDTH $SOURCE_HEIGHT">
              <rect width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT" fill="#3366cc"/>
            </svg>
            """.trimIndent(),
        )
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            input = input,
            output = tempDir.resolve("viewbox.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `internal engine renders svg with feDropShadow to jpeg within max edge`() = runBlocking {
        val input = Files.writeString(
            tempDir.resolve("shadow.svg"),
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT">
              <defs>
                <filter id="d" x="-20%" y="-20%" width="140%" height="140%">
                  <feDropShadow dx="0" dy="4" stdDeviation="6" flood-color="#1a365d" flood-opacity="0.2"/>
                </filter>
              </defs>
              <rect width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT" rx="8" fill="#3366cc" filter="url(#d)"/>
            </svg>
            """.trimIndent(),
        )
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            input = input,
            output = tempDir.resolve("shadow.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `internal engine renders svg with unsupported elements to jpeg within max edge`() = runBlocking {
        val input = Files.writeString(
            tempDir.resolve("hatch.svg"),
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT">
              <hatch id="h" hatchUnits="userSpaceOnUse"/>
              <rect width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT" fill="#3366cc"/>
            </svg>
            """.trimIndent(),
        )
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_IMAGE,
            input = input,
            output = tempDir.resolve("hatch.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `skip types return skipped without touching generator`() = runBlocking {
        val calls = AtomicInteger(0)
        val generator = ArtifactThumbnailGenerator { _, _, _ ->
            calls.incrementAndGet()
            ThumbnailGenerateResult.Success
        }
        val result = service(generator).generate(
            input = tempDir.resolve("x.py"),
            artifactType = ArtifactThumbnailTypes.TYPE_CODE,
            output = tempDir.resolve("out.jpg"),
        )

        assertEquals(ThumbnailGenerateResult.Skipped("skip type[code]"), result)
        assertEquals(0, calls.get())
    }

    @Test
    fun `internal engine fails for invalid pdf`() = runBlocking {
        assertGenerateFailed(ArtifactThumbnailTypes.TYPE_PDF, Files.writeString(tempDir.resolve("a.pdf"), "not-a-pdf"))
    }

    @Test
    fun `internal engine renders pdf first page to jpeg within max edge`() = runBlocking {
        val props = properties().apply { timeout = Duration.ofSeconds(PDF_RENDER_TIMEOUT_SECONDS) }
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_PDF,
            input = writePdf(),
            output = tempDir.resolve("pdf.jpg"),
            maxEdge = props.documentMaxEdge,
            props = props,
        )
    }

    @Test
    fun `internal engine fails for invalid pptx`() = runBlocking {
        assertGenerateFailed(
            ArtifactThumbnailTypes.TYPE_SLIDES,
            Files.writeString(tempDir.resolve("a.pptx"), "not-a-pptx"),
        )
    }

    @Test
    fun `internal engine renders pptx first slide to jpeg within max edge`() = runBlocking {
        val props = properties().apply { timeout = Duration.ofSeconds(SLIDES_RENDER_TIMEOUT_SECONDS) }
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_SLIDES,
            input = writePptx(),
            output = tempDir.resolve("pptx.jpg"),
            maxEdge = props.documentMaxEdge,
            props = props,
        )
    }

    @Test
    fun `internal engine renders ppt first slide to jpeg within max edge`() = runBlocking {
        val props = properties().apply { timeout = Duration.ofSeconds(SLIDES_RENDER_TIMEOUT_SECONDS) }
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_SLIDES,
            input = writePpt(),
            output = tempDir.resolve("ppt.jpg"),
            maxEdge = props.documentMaxEdge,
            props = props,
        )
    }

    @Test
    fun `internal engine fails for invalid xlsx`() = runBlocking {
        assertGenerateFailed(
            ArtifactThumbnailTypes.TYPE_TABLE,
            Files.write(
                tempDir.resolve("a.xlsx"),
                byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00),
            ),
        )
    }

    @Test
    fun `internal engine fails for empty csv`() = runBlocking {
        assertGenerateFailed(ArtifactThumbnailTypes.TYPE_TABLE, Files.writeString(tempDir.resolve("empty.csv"), "\n\n"))
    }

    @Test
    fun `internal engine renders xlsx first sheet to jpeg within max edge`() = runBlocking {
        val props = properties().apply { timeout = Duration.ofSeconds(TABLE_RENDER_TIMEOUT_SECONDS) }
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_TABLE,
            input = writeXlsx(),
            output = tempDir.resolve("xlsx.jpg"),
            maxEdge = props.documentMaxEdge,
            props = props,
        )
    }

    @Test
    fun `internal engine renders large xlsx within generate timeout`() = runBlocking {
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_TABLE,
            input = writeLargeXlsx(LARGE_XLSX_ROWS),
            output = tempDir.resolve("large-xlsx.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `internal engine renders xls first sheet to jpeg within max edge`() = runBlocking {
        val props = properties().apply { timeout = Duration.ofSeconds(TABLE_RENDER_TIMEOUT_SECONDS) }
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_TABLE,
            input = writeXls(),
            output = tempDir.resolve("xls.jpg"),
            maxEdge = props.documentMaxEdge,
            props = props,
        )
    }

    @Test
    fun `internal engine renders csv to jpeg within max edge`() = runBlocking {
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_TABLE,
            input = writeCsv(),
            output = tempDir.resolve("csv.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `table jpeg default max edge is 1080p`() = runBlocking {
        val props = DriveThumbnailProperties().apply {
            timeout = Duration.ofSeconds(TABLE_RENDER_TIMEOUT_SECONDS)
        }
        val output = tempDir.resolve("xlsx-1080p.jpg")
        val result = service(internal(props), props).generate(
            writeXlsx(),
            ArtifactThumbnailTypes.TYPE_TABLE,
            output,
        )
        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertEquals(1920, maxOf(image.width, image.height))
        assertTrue(jpegHasFullChroma(output))
    }

    @Test
    fun `svg jpeg default max edge is 1080p`() = runBlocking {
        val props = DriveThumbnailProperties().apply {
            timeout = Duration.ofSeconds(TABLE_RENDER_TIMEOUT_SECONDS)
        }
        val output = tempDir.resolve("svg-1080p.jpg")
        val result = service(internal(props), props).generate(
            writeSvg(),
            ArtifactThumbnailTypes.TYPE_IMAGE,
            output,
        )
        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertEquals(1920, maxOf(image.width, image.height))
        assertEquals(960, minOf(image.width, image.height))
        assertTrue(jpegHasFullChroma(output))
    }

    @Test
    fun `internal engine fails for empty markdown`() = runBlocking {
        assertGenerateFailed(
            ArtifactThumbnailTypes.TYPE_MARKDOWN,
            Files.writeString(tempDir.resolve("empty.md"), "\n\n"),
        )
    }

    @Test
    fun `internal engine renders markdown to jpeg within max edge`() = runBlocking {
        assertRenderedWithin(
            type = ArtifactThumbnailTypes.TYPE_MARKDOWN,
            input = writeMarkdown(),
            output = tempDir.resolve("note.jpg"),
            maxEdge = properties().documentMaxEdge,
        )
    }

    @Test
    fun `pdf slides markdown table svg use document max edge instead of global max edge`() = runBlocking {
        val props = properties().apply {
            maxEdge = 256
            documentMaxEdge = 512
            timeout = Duration.ofSeconds(PDF_RENDER_TIMEOUT_SECONDS)
        }
        val svc = service(internal(props), props)

        val pdfOut = tempDir.resolve("doc-pdf.jpg")
        assertEquals(
            ThumbnailGenerateResult.Success,
            svc.generate(writePdf(), ArtifactThumbnailTypes.TYPE_PDF, pdfOut),
        )
        val pdf = ImageIO.read(pdfOut.toFile())
        assertEquals(512, maxOf(pdf.width, pdf.height))

        val pptOut = tempDir.resolve("doc-ppt.jpg")
        assertEquals(
            ThumbnailGenerateResult.Success,
            svc.generate(writePptx(), ArtifactThumbnailTypes.TYPE_SLIDES, pptOut),
        )
        val ppt = ImageIO.read(pptOut.toFile())
        assertEquals(512, maxOf(ppt.width, ppt.height))

        val mdOut = tempDir.resolve("doc-md.jpg")
        assertEquals(
            ThumbnailGenerateResult.Success,
            svc.generate(writeMarkdown(), ArtifactThumbnailTypes.TYPE_MARKDOWN, mdOut),
        )
        val md = ImageIO.read(mdOut.toFile())
        assertEquals(512, maxOf(md.width, md.height))

        val tableOut = tempDir.resolve("doc-xlsx.jpg")
        assertEquals(
            ThumbnailGenerateResult.Success,
            svc.generate(writeXlsx(), ArtifactThumbnailTypes.TYPE_TABLE, tableOut),
        )
        val table = ImageIO.read(tableOut.toFile())
        assertEquals(512, maxOf(table.width, table.height))

        val svgOut = tempDir.resolve("doc-svg.jpg")
        assertEquals(
            ThumbnailGenerateResult.Success,
            svc.generate(writeSvg(), ArtifactThumbnailTypes.TYPE_IMAGE, svgOut),
        )
        val svg = ImageIO.read(svgOut.toFile())
        assertEquals(512, maxOf(svg.width, svg.height))
        assertTrue(jpegHasFullChroma(pdfOut))
        assertTrue(jpegHasFullChroma(pptOut))
        assertTrue(jpegHasFullChroma(mdOut))
        assertTrue(jpegHasFullChroma(tableOut))
        assertTrue(jpegHasFullChroma(svgOut))
    }

    @Test
    fun `internal engine fails for empty html`() = runBlocking {
        val htmlRenderer = HtmlThumbnailRenderer { path ->
            val text = Files.readString(path).trim()
            if (text.isEmpty()) {
                null
            } else {
                BufferedImage(HTML_SOURCE_WIDTH, HTML_SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB)
            }
        }
        val result = service(internal(htmlRenderer = htmlRenderer)).generate(
            input = Files.writeString(tempDir.resolve("empty.html"), "\n\n"),
            artifactType = ArtifactThumbnailTypes.TYPE_HTML,
            output = tempDir.resolve("out.jpg"),
        )
        assertTrue(result is ThumbnailGenerateResult.Failed)
    }

    @Test
    fun `internal engine fails when html renderer returns null`() = runBlocking {
        assertGenerateFailed(
            ArtifactThumbnailTypes.TYPE_HTML,
            Files.writeString(tempDir.resolve("page.html"), "<html><body>hi</body></html>"),
        )
    }

    @Test
    fun `internal engine renders html to jpeg within max edge`() = runBlocking {
        val input = Files.writeString(
            tempDir.resolve("page.html"),
            "<html><body><h1>Hello</h1></body></html>",
        )
        val htmlRenderer = HtmlThumbnailRenderer { path ->
            assertEquals(input, path)
            val image = BufferedImage(HTML_SOURCE_WIDTH, HTML_SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.GREEN
                graphics.fillRect(0, 0, HTML_SOURCE_WIDTH, HTML_SOURCE_HEIGHT)
            } finally {
                graphics.dispose()
            }
            image
        }
        val output = tempDir.resolve("html.jpg")
        val result = service(internal(htmlRenderer = htmlRenderer)).generate(
            input = input,
            artifactType = ArtifactThumbnailTypes.TYPE_HTML,
            output = output,
        )
        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertTrue(maxOf(image.width, image.height) <= properties().htmlMaxEdge)
        assertTrue(image.width >= 1 && image.height >= 1)
    }

    @Test
    fun `html jpeg uses html max edge instead of global max edge`() = runBlocking {
        val props = properties().apply {
            maxEdge = 256
            htmlMaxEdge = 512
        }
        val htmlRenderer = HtmlThumbnailRenderer {
            BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        }
        val output = tempDir.resolve("html-large.jpg")
        val result = service(internal(props, htmlRenderer), props).generate(
            input = Files.writeString(tempDir.resolve("wide.html"), "<html><body>hi</body></html>"),
            artifactType = ArtifactThumbnailTypes.TYPE_HTML,
            output = output,
        )
        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertEquals(512, image.width)
        assertEquals(288, image.height)
    }

    @Test
    fun `html jpeg default max edge is 1080p`() = runBlocking {
        val htmlRenderer = HtmlThumbnailRenderer {
            BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB)
        }
        val output = tempDir.resolve("html-1080p.jpg")
        val result = service(internal(htmlRenderer = htmlRenderer)).generate(
            input = Files.writeString(tempDir.resolve("retina.html"), "<html><body>hi</body></html>"),
            artifactType = ArtifactThumbnailTypes.TYPE_HTML,
            output = output,
        )
        assertEquals(ThumbnailGenerateResult.Success, result)
        val image = ImageIO.read(output.toFile())
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
    }

    @Test
    fun `html jpeg writes 4-4-4 chroma so colored text is not subsampled`() = runBlocking {
        val htmlRenderer = HtmlThumbnailRenderer {
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(102, 51, 204)
                graphics.fillRect(0, 0, 32, 32)
            } finally {
                graphics.dispose()
            }
            image
        }
        val output = tempDir.resolve("html-chroma.jpg")
        val result = service(internal(htmlRenderer = htmlRenderer)).generate(
            input = Files.writeString(tempDir.resolve("chroma.html"), "<html><body>hi</body></html>"),
            artifactType = ArtifactThumbnailTypes.TYPE_HTML,
            output = output,
        )
        assertEquals(ThumbnailGenerateResult.Success, result)
        assertTrue(jpegHasFullChroma(output), "HTML JPEG should keep 4:4:4 chroma")
    }

    @Test
    fun `internal engine fails for invalid video`() = runBlocking {
        val input = Files.writeString(tempDir.resolve("a.mp4"), "not-a-video")
        assertGenerateFailed(ArtifactThumbnailTypes.TYPE_VIDEO, input)
    }

    @Test
    fun `internal engine renders mp4 first frame to jpeg within max edge`() = runBlocking {
        assertVideoThumbnail(writeMp4("clip.mp4"))
    }

    @Test
    fun `internal engine renders mp4 copied to src temp suffix`() = runBlocking {
        assertVideoThumbnail(copyAsSrc(writeMp4("mpeg4.mp4")))
    }

    @Test
    fun `internal engine renders h264 mp4 copied to src temp suffix`() = runBlocking {
        assertVideoThumbnail(copyAsSrc(writeH264Mp4()))
    }

    @Test
    fun `internal engine renders h264 mp4 copied to receive temp suffix`() = runBlocking {
        val temp = tempDir.resolve("artifact_async_1.temp")
        Files.copy(writeH264Mp4(), temp)
        assertVideoThumbnail(temp)
    }

    @Test
    fun `internal engine fails truncated mp4 copied to src temp suffix`() = runBlocking {
        val mp4 = writeMp4("full.mp4")
        val truncated = tempDir.resolve("truncated.src")
        val bytes = Files.readAllBytes(mp4)
        Files.write(truncated, bytes.copyOf(bytes.size / 2))
        val result = service(internal(), properties()).generate(
            input = truncated,
            artifactType = ArtifactThumbnailTypes.TYPE_VIDEO,
            output = tempDir.resolve("truncated.jpg"),
        )
        assertTrue(result is ThumbnailGenerateResult.Failed, result.toString())
    }

    @Test
    fun `html concurrency one rejects the second caller immediately`() = runBlocking {
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val generator = ArtifactThumbnailGenerator { _, _, _ ->
            started.countDown()
            hold.await(5, TimeUnit.SECONDS)
            ThumbnailGenerateResult.Success
        }
        val svc = service(generator, properties().apply { concurrency["html"] = 1 })
        val first = async(Dispatchers.IO) {
            svc.generate(tempDir.resolve("a.html"), ArtifactThumbnailTypes.TYPE_HTML, tempDir.resolve("a.jpg"))
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val second = svc.generate(
            tempDir.resolve("b.html"),
            ArtifactThumbnailTypes.TYPE_HTML,
            tempDir.resolve("b.jpg"),
        )
        assertEquals(ThumbnailGenerateResult.Failed("busy type[html]"), second)
        hold.countDown()
        assertEquals(ThumbnailGenerateResult.Success, first.await())
    }

    @Test
    fun `generate waiting acquires permit after the busy type is free`() = runBlocking {
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val generator = ArtifactThumbnailGenerator { _, _, _ ->
            started.countDown()
            hold.await(5, TimeUnit.SECONDS)
            ThumbnailGenerateResult.Success
        }
        val svc = service(generator, properties().apply { concurrency["html"] = 1 })
        val first = async(Dispatchers.IO) {
            svc.generate(tempDir.resolve("a.html"), ArtifactThumbnailTypes.TYPE_HTML, tempDir.resolve("a.jpg"))
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val waiting = async(Dispatchers.IO) {
            svc.generateWaiting(
                input = tempDir.resolve("b.html"),
                artifactType = ArtifactThumbnailTypes.TYPE_HTML,
                output = tempDir.resolve("b.jpg"),
                waitTimeout = Duration.ofSeconds(10),
            )
        }
        hold.countDown()
        assertEquals(ThumbnailGenerateResult.Success, first.await())
        assertEquals(ThumbnailGenerateResult.Success, waiting.await())
    }

    @Test
    fun `generate waiting skips generate when leftover budget is not greater than timeout`() = runBlocking {
        val calls = AtomicInteger(0)
        val generator = ArtifactThumbnailGenerator { _, _, _ ->
            calls.incrementAndGet()
            ThumbnailGenerateResult.Success
        }
        val result = service(generator).generateWaiting(
            input = tempDir.resolve("a.html"),
            artifactType = ArtifactThumbnailTypes.TYPE_HTML,
            output = tempDir.resolve("a.jpg"),
            waitTimeout = Duration.ofMillis(200),
        )
        assertEquals(ThumbnailGenerateResult.Failed("duration"), result)
        assertEquals(0, calls.get())
    }

    @Test
    fun `image and html semaphores are independent`() = runBlocking {
        val hold = CountDownLatch(1)
        val htmlStarted = CountDownLatch(1)
        val generator = ArtifactThumbnailGenerator { _, type, _ ->
            if (type == ArtifactThumbnailTypes.TYPE_HTML) {
                htmlStarted.countDown()
                hold.await(5, TimeUnit.SECONDS)
            }
            ThumbnailGenerateResult.Success
        }
        val svc = service(
            generator,
            properties().apply {
                concurrency["html"] = 1
                concurrency["image"] = 8
            },
        )
        val htmlJob = async(Dispatchers.IO) {
            svc.generate(tempDir.resolve("a.html"), ArtifactThumbnailTypes.TYPE_HTML, tempDir.resolve("a.jpg"))
        }
        assertTrue(htmlStarted.await(2, TimeUnit.SECONDS))
        val imageResult = svc.generate(
            writePng(16, 16),
            ArtifactThumbnailTypes.TYPE_IMAGE,
            tempDir.resolve("img.jpg"),
        )
        assertEquals(ThumbnailGenerateResult.Success, imageResult)
        hold.countDown()
        assertEquals(ThumbnailGenerateResult.Success, htmlJob.await())
    }

    @Test
    fun `timeout returns before uninterruptible generate finishes`() = runBlocking {
        val finished = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val generator = ArtifactThumbnailGenerator { _, _, _ ->
            started.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < until) {
                // busy-spin: Thread.interrupt cannot unblock this
            }
            finished.set(true)
            ThumbnailGenerateResult.Success
        }
        val svc = service(generator, properties().apply { timeout = Duration.ofMillis(200) })
        val startedAt = System.nanoTime()
        val result = svc.generate(
            tempDir.resolve("slow.pdf"),
            ArtifactThumbnailTypes.TYPE_PDF,
            tempDir.resolve("slow.jpg"),
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals(ThumbnailGenerateResult.Failed("timeout"), result)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(elapsedMs < 2000, "caller waited ${elapsedMs}ms for 200ms timeout")
        assertTrue(!finished.get())
    }

    @Test
    fun `timeout closes registered abortable resource`() = runBlocking {
        val closed = CountDownLatch(1)
        val started = CountDownLatch(1)
        val generator = ArtifactThumbnailGenerator { _, _, _ ->
            ThumbnailAbort.register(AutoCloseable { closed.countDown() })
            started.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < until && !ThumbnailAbort.isCancelled()) {
                // wait until abort
            }
            ThumbnailGenerateResult.Success
        }
        val svc = service(generator, properties().apply { timeout = Duration.ofMillis(200) })
        val result = svc.generate(
            tempDir.resolve("slow.html"),
            ArtifactThumbnailTypes.TYPE_HTML,
            tempDir.resolve("slow.jpg"),
        )
        assertEquals(ThumbnailGenerateResult.Failed("timeout"), result)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `resolve prefers metadata type then extension`() {
        assertEquals(
            ArtifactThumbnailTypes.TYPE_PDF,
            ArtifactThumbnailTypes.resolve("pdf", "/a.png"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_IMAGE,
            ArtifactThumbnailTypes.resolve("nope", "/a.png"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_OTHER,
            ArtifactThumbnailTypes.resolve(null, "/a.bin"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_SLIDES,
            ArtifactThumbnailTypes.resolve(null, "/deck.pptx"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_SLIDES,
            ArtifactThumbnailTypes.resolve(null, "/deck.ppt"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_TABLE,
            ArtifactThumbnailTypes.resolve(null, "/data.xlsx"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_TABLE,
            ArtifactThumbnailTypes.resolve(null, "/data.xls"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_TABLE,
            ArtifactThumbnailTypes.resolve(null, "/data.csv"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_VIDEO,
            ArtifactThumbnailTypes.resolve(null, "/clips/demo.mp4"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_VIDEO,
            ArtifactThumbnailTypes.resolve(null, "/clips/demo.webm"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_MARKDOWN,
            ArtifactThumbnailTypes.resolve(null, "/readme.md"),
        )
        assertEquals(
            ArtifactThumbnailTypes.TYPE_MARKDOWN,
            ArtifactThumbnailTypes.resolve(null, "/notes/guide.markdown"),
        )
    }

    @Test
    fun `upsert metadata adds thumbnail path and replaces existing key case-insensitively`() {
        val merged = ArtifactThumbnailTypes.upsertMetadata(
            existing = listOf(TMetadata(key = "IMATE_ARTIFACT_NAME", value = "封面")),
            key = ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL,
            value = "/docs/.hero.png.thumb.jpg",
        )
        assertEquals("封面", ArtifactThumbnailTypes.metadataValue(merged, ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME))
        assertEquals(
            "/docs/.hero.png.thumb.jpg",
            ArtifactThumbnailTypes.metadataValue(merged, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL),
        )

        val replaced = ArtifactThumbnailTypes.upsertMetadata(
            existing = merged,
            key = "imate_artifact_thumbnail",
            value = "/docs/.hero.png.thumb.jpg",
        )
        assertEquals(1, replaced.count { it.key.equals(ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL, true) })
        assertEquals(
            "/docs/.hero.png.thumb.jpg",
            ArtifactThumbnailTypes.metadataValue(replaced, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL),
        )
    }

    @Test
    fun `without thumbnail keys drops client thumbnail path and ino case-insensitively`() {
        val stripped = ArtifactThumbnailTypes.withoutThumbnailKeys(
            mutableListOf(
                TMetadata(key = ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME, value = "封面"),
                TMetadata(key = "imate_artifact_thumbnail", value = "/stolen.jpg"),
                TMetadata(key = "IMATE_ARTIFACT_THUMBNAIL_INO", value = "9"),
            ),
        )
        assertEquals(
            "封面",
            ArtifactThumbnailTypes.metadataValue(stripped, ArtifactThumbnailTypes.METADATA_ARTIFACT_NAME),
        )
        assertNull(ArtifactThumbnailTypes.metadataValue(stripped, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL))
        assertNull(
            ArtifactThumbnailTypes.metadataValue(stripped, ArtifactThumbnailTypes.METADATA_ARTIFACT_THUMBNAIL_INO),
        )
        assertNull(ArtifactThumbnailTypes.withoutThumbnailKeys(null))
    }

    private fun service(
        generator: ArtifactThumbnailGenerator,
        properties: DriveThumbnailProperties = properties(),
    ) = ArtifactThumbnailGenerateService(generator, properties)

    private fun internal(
        properties: DriveThumbnailProperties = properties(),
        htmlRenderer: HtmlThumbnailRenderer = HtmlThumbnailRenderer { null },
    ) = InternalArtifactThumbnailGenerator(properties, htmlRenderer)

    private fun properties() = DriveThumbnailProperties().apply {
        timeout = Duration.ofSeconds(5)
        maxEdge = 256
        documentMaxEdge = 256
        jpegQuality = 0.7f
    }

    private fun jpegHasFullChroma(path: Path): Boolean {
        val reader = ImageIO.getImageReadersByFormatName("jpeg").asSequence().firstOrNull()
            ?: return false
        return ImageIO.createImageInputStream(path.toFile()).use { stream ->
            try {
                reader.input = stream
                val tree = reader.getImageMetadata(0).getAsTree(JPEG_NATIVE_METADATA)
                jpegSamplingFactors(tree).let { factors ->
                    factors.isNotEmpty() && factors.all { it == 1 }
                }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun jpegSamplingFactors(node: Node): List<Int> {
        val factors = mutableListOf<Int>()
        if (node.nodeName == "componentSpec") {
            val horizontal = node.attributes?.getNamedItem("HsamplingFactor")?.nodeValue?.toIntOrNull()
            val vertical = node.attributes?.getNamedItem("VsamplingFactor")?.nodeValue?.toIntOrNull()
            if (horizontal != null) {
                factors += horizontal
            }
            if (vertical != null) {
                factors += vertical
            }
        }
        var child = node.firstChild
        while (child != null) {
            factors += jpegSamplingFactors(child)
            child = child.nextSibling
        }
        return factors
    }

    private fun writePng(width: Int, height: Int): Path {
        val file = tempDir.resolve("src-${width}x${height}.png")
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(image, "png", file.toFile())
        return file
    }

    private fun writeSvg(): Path {
        val file = tempDir.resolve("icon.svg")
        Files.writeString(
            file,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT">
              <rect width="$SOURCE_WIDTH" height="$SOURCE_HEIGHT" fill="#3366cc"/>
            </svg>
            """.trimIndent(),
        )
        return file
    }

    private fun writePdf(): Path {
        val file = tempDir.resolve("page.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT))
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.setNonStrokingColor(Color.BLUE)
                content.addRect(20f, 20f, 120f, 80f)
                content.fill()
            }
            document.save(file.toFile())
        }
        return file
    }

    private fun writePptx(): Path {
        return writeSlideDeck(tempDir.resolve("deck.pptx"), XMLSlideShow())
    }

    private fun writePpt(): Path {
        return writeSlideDeck(tempDir.resolve("deck.ppt"), HSLFSlideShow())
    }

    private fun writeSlideDeck(file: Path, show: SlideShow<*, *>): Path {
        show.use { ppt ->
            val slide = ppt.createSlide()
            val shape = slide.createAutoShape()
            shape.anchor = Rectangle(SLIDE_SHAPE_X, SLIDE_SHAPE_Y, SLIDE_SHAPE_WIDTH, SLIDE_SHAPE_HEIGHT)
            shape.fillColor = Color.BLUE
            Files.newOutputStream(file).use { ppt.write(it) }
        }
        return file
    }

    private fun writeXlsx(): Path {
        return writeWorkbook(tempDir.resolve("sheet.xlsx"), XSSFWorkbook())
    }

    private fun writeLargeXlsx(rows: Int): Path {
        val file = tempDir.resolve("large-$rows.xlsx")
        SXSSFWorkbook(SXSSF_ROW_WINDOW).use { workbook ->
            val sheet = workbook.createSheet("s")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("name")
            header.createCell(1).setCellValue("value")
            for (i in 1 until rows) {
                val row = sheet.createRow(i)
                row.createCell(0).setCellValue("foo")
                row.createCell(1).setCellValue(42.0)
            }
            Files.newOutputStream(file).use { workbook.write(it) }
        }
        return file
    }

    private fun writeXls(): Path {
        return writeWorkbook(tempDir.resolve("sheet.xls"), HSSFWorkbook())
    }

    private fun writeWorkbook(file: Path, workbook: Workbook): Path {
        workbook.use { sheets ->
            val sheet = sheets.createSheet("s")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("name")
            header.createCell(1).setCellValue("value")
            val row = sheet.createRow(1)
            row.createCell(0).setCellValue("foo")
            row.createCell(1).setCellValue(42.0)
            Files.newOutputStream(file).use { sheets.write(it) }
        }
        return file
    }

    private fun writeCsv(): Path {
        val file = tempDir.resolve("sheet.csv")
        Files.writeString(file, "name,value\nfoo,42\n")
        return file
    }

    private fun writeMarkdown(): Path {
        val file = tempDir.resolve("note.md")
        Files.writeString(
            file,
            """
            # Title
            Hello **world** and a [link](https://example.com)
            - item one
            > quoted text
            | name | value |
            | --- | --- |
            | foo | 42 |
            ```
            code
            ```
            """.trimIndent(),
        )
        return file
    }

    private suspend fun assertGenerateFailed(type: String, input: Path) {
        val result = service(internal()).generate(input, type, tempDir.resolve("out.jpg"))
        assertTrue(result is ThumbnailGenerateResult.Failed)
    }

    private suspend fun assertRenderedWithin(
        type: String,
        input: Path,
        output: Path,
        maxEdge: Int,
        props: DriveThumbnailProperties = properties(),
    ) {
        val result = service(internal(props), props).generate(input, type, output)
        assertEquals(ThumbnailGenerateResult.Success, result)
        assertJpegWithin(output, maxEdge)
    }

    private fun assertJpegWithin(output: Path, maxEdge: Int) {
        val image = ImageIO.read(output.toFile())
        assertTrue(maxOf(image.width, image.height) <= maxEdge)
        assertTrue(image.width >= 1 && image.height >= 1)
    }

    private suspend fun assertVideoThumbnail(input: Path) {
        val output = tempDir.resolve("${input.fileName}.jpg")
        val props = properties().apply { timeout = Duration.ofSeconds(VIDEO_RENDER_TIMEOUT_SECONDS) }
        assertRenderedWithin(ArtifactThumbnailTypes.TYPE_VIDEO, input, output, props.maxEdge, props)
    }

    private fun copyAsSrc(source: Path): Path {
        val src = tempDir.resolve("${source.fileName}.src")
        Files.copy(source, src)
        return src
    }

    private fun writeMp4(fileName: String, codec: Int = avcodec.AV_CODEC_ID_MPEG4, codecName: String? = null): Path {
        val file = tempDir.resolve(fileName)
        val width = VIDEO_WIDTH
        val height = VIDEO_HEIGHT
        FFmpegFrameRecorder(file.toFile(), width, height).use { recorder ->
            recorder.format = "mp4"
            recorder.videoCodec = codec
            codecName?.let { recorder.videoCodecName = it }
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            recorder.frameRate = VIDEO_FRAME_RATE
            recorder.audioChannels = 0
            recorder.start()
            Java2DFrameConverter().use { converter ->
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try {
                    graphics.color = Color.BLUE
                    graphics.fillRect(0, 0, width, height)
                } finally {
                    graphics.dispose()
                }
                val frame = converter.convert(image)
                repeat(VIDEO_FRAME_COUNT) {
                    recorder.record(frame)
                }
            }
        }
        return file
    }

    private fun writeH264Mp4(): Path {
        return writeMp4("h264.mp4", avcodec.AV_CODEC_ID_H264, "libx264")
    }

    companion object {
        private const val SOURCE_WIDTH = 400
        private const val SOURCE_HEIGHT = 200
        private const val LARGE_SOURCE_WIDTH = 4096
        private const val LARGE_SOURCE_HEIGHT = 2048
        private const val HTML_SOURCE_WIDTH = 400
        private const val HTML_SOURCE_HEIGHT = 200
        private const val PDF_PAGE_WIDTH = 400f
        private const val PDF_PAGE_HEIGHT = 200f
        private const val PDF_RENDER_TIMEOUT_SECONDS = 30L
        private const val SLIDES_RENDER_TIMEOUT_SECONDS = 30L
        private const val TABLE_RENDER_TIMEOUT_SECONDS = 30L
        private const val LARGE_XLSX_ROWS = 100_000
        private const val SXSSF_ROW_WINDOW = 100
        private const val VIDEO_RENDER_TIMEOUT_SECONDS = 30L
        private const val VIDEO_WIDTH = 64
        private const val VIDEO_HEIGHT = 36
        private const val VIDEO_FRAME_COUNT = 3
        private const val VIDEO_FRAME_RATE = 1.0
        private const val SLIDE_SHAPE_X = 40
        private const val SLIDE_SHAPE_Y = 40
        private const val SLIDE_SHAPE_WIDTH = 200
        private const val SLIDE_SHAPE_HEIGHT = 120
        private const val JPEG_NATIVE_METADATA = "javax_imageio_jpeg_image_1.0"
    }
}
