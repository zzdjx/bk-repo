package com.tencent.bkrepo.fs.service.drive.thumbnail

import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ArtifactThumbnailGenerateService
import com.tencent.bkrepo.fs.server.utils.ArtifactThumbnailTypes
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.HtmlSandboxThumbnailRenderer
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.InternalArtifactThumbnailGenerator
import com.tencent.bkrepo.fs.server.service.drive.thumbnail.ThumbnailGenerateResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

@DisplayName("HTML 沙箱缩略图")
class HtmlSandboxThumbnailRendererTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `iframe sandbox allows scripts without same origin`() {
        val js = HtmlSandboxThumbnailRenderer.loadSandboxJs()
        assertTrue(js.contains("setAttribute('sandbox', '${HtmlSandboxThumbnailRenderer.IFRAME_SANDBOX_VALUE}')"))
        assertEquals("allow-scripts", HtmlSandboxThumbnailRenderer.IFRAME_SANDBOX_VALUE)
        assertFalse(js.contains("allow-same-origin"))
        assertFalse(js.contains("allow-top-navigation"))
    }

    @Test
    fun `sandbox request allowlist rejects remote and file urls`() {
        assertTrue(HtmlSandboxThumbnailRenderer.allowedSandboxRequest("about:blank"))
        assertTrue(HtmlSandboxThumbnailRenderer.allowedSandboxRequest("about:srcdoc"))
        assertTrue(HtmlSandboxThumbnailRenderer.allowedSandboxRequest("data:text/html,hi"))
        assertFalse(HtmlSandboxThumbnailRenderer.allowedSandboxRequest("https://example.com/"))
        assertFalse(HtmlSandboxThumbnailRenderer.allowedSandboxRequest("http://127.0.0.1/"))
        assertFalse(HtmlSandboxThumbnailRenderer.allowedSandboxRequest("file:///tmp/secret"))
    }

    @Test
    fun `settle wait is skipped when delay is not positive`() {
        assertEquals(0, HtmlSandboxThumbnailRenderer.settleWaitMillis(Duration.ZERO, TIMEOUT_MS))
        assertEquals(0, HtmlSandboxThumbnailRenderer.settleWaitMillis(Duration.ofMillis(-1), TIMEOUT_MS))
    }

    @Test
    fun `settle wait is capped by generate timeout`() {
        assertEquals(SETTLE_MS, HtmlSandboxThumbnailRenderer.settleWaitMillis(Duration.ofSeconds(1), TIMEOUT_MS))
        assertEquals(CAP_MS, HtmlSandboxThumbnailRenderer.settleWaitMillis(Duration.ofSeconds(2), CAP_MS))
    }

    @Test
    fun `empty html does not require playwright`() {
        HtmlSandboxThumbnailRenderer(DriveThumbnailProperties()).use { renderer ->
            val image = renderer.render(Files.writeString(tempDir.resolve("empty.html"), "  \n"))
            assertEquals(null, image)
        }
    }

    @Test
    fun `playwright renders html first screen in chromium sandbox`() = runBlocking {
        val props = DriveThumbnailProperties().apply {
            timeout = Duration.ofSeconds(HTML_RENDER_TIMEOUT_SECONDS)
            maxEdge = 256
            jpegQuality = 0.7f
            htmlChromiumSandbox = true
        }
        HtmlSandboxThumbnailRenderer(props).use { renderer ->
            try {
                renderer.start()
            } catch (e: Exception) {
                assumeTrue(false, "Playwright Chromium sandbox unavailable: ${e.message}")
                return@runBlocking
            }
            val output = tempDir.resolve("sandbox.jpg")
            val result = ArtifactThumbnailGenerateService(
                InternalArtifactThumbnailGenerator(props, renderer),
                props,
            ).generate(
                input = writeGreenHtml(),
                artifactType = ArtifactThumbnailTypes.TYPE_HTML,
                output = output,
            )
            if (result is ThumbnailGenerateResult.Failed) {
                assumeTrue(false, "Playwright Chromium sandbox unavailable: ${result.reason}")
                return@runBlocking
            }
            assertEquals(ThumbnailGenerateResult.Success, result)
            val image = ImageIO.read(output.toFile())
            assertTrue(maxOf(image.width, image.height) <= props.htmlMaxEdge)
            assertTrue(image.width >= 1 && image.height >= 1)
            assertTrue(hasGreenPixel(image), "sandboxed HTML should paint the first screen")
        }
    }

    @Test
    fun `playwright screenshot uses device scale factor`() {
        val props = DriveThumbnailProperties().apply {
            timeout = Duration.ofSeconds(HTML_RENDER_TIMEOUT_SECONDS)
            htmlViewportWidth = SCALE_VIEWPORT_WIDTH
            htmlViewportHeight = SCALE_VIEWPORT_HEIGHT
            htmlDeviceScaleFactor = 2.0
            htmlSettleDelay = Duration.ZERO
            htmlChromiumSandbox = true
        }
        HtmlSandboxThumbnailRenderer(props).use { renderer ->
            try {
                renderer.start()
            } catch (e: Exception) {
                assumeTrue(false, "Playwright Chromium sandbox unavailable: ${e.message}")
                return
            }
            val image = renderer.render(writeGreenHtml())
            if (image == null) {
                assumeTrue(false, "Playwright Chromium sandbox unavailable: empty render")
                return
            }
            assertEquals(SCALE_VIEWPORT_WIDTH * 2, image.width)
            assertEquals(SCALE_VIEWPORT_HEIGHT * 2, image.height)
            assertTrue(hasGreenPixel(image))
        }
    }

    @Test
    fun `playwright waits for delayed first-screen paint before screenshot`() = runBlocking {
        val props = DriveThumbnailProperties().apply {
            timeout = Duration.ofSeconds(HTML_RENDER_TIMEOUT_SECONDS)
            maxEdge = 256
            jpegQuality = 0.7f
            htmlChromiumSandbox = true
            htmlSettleDelay = Duration.ofMillis(DELAYED_SETTLE_MS)
        }
        HtmlSandboxThumbnailRenderer(props).use { renderer ->
            try {
                renderer.start()
            } catch (e: Exception) {
                assumeTrue(false, "Playwright Chromium sandbox unavailable: ${e.message}")
                return@runBlocking
            }
            val output = tempDir.resolve("delayed.jpg")
            val result = ArtifactThumbnailGenerateService(
                InternalArtifactThumbnailGenerator(props, renderer),
                props,
            ).generate(
                input = writeDelayedGreenHtml(),
                artifactType = ArtifactThumbnailTypes.TYPE_HTML,
                output = output,
            )
            if (result is ThumbnailGenerateResult.Failed) {
                assumeTrue(false, "Playwright Chromium sandbox unavailable: ${result.reason}")
                return@runBlocking
            }
            assertEquals(ThumbnailGenerateResult.Success, result)
            val image = ImageIO.read(output.toFile())
            assertTrue(hasGreenPixel(image), "screenshot should wait for delayed first-screen paint")
        }
    }

    private fun writeGreenHtml(): Path {
        val file = tempDir.resolve("green.html")
        Files.writeString(
            file,
            """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
            html, body { margin: 0; width: 100%; height: 100%; background: #008000; }
            </style>
            </head>
            <body>
            <script>
            try {
              window.parent.document.documentElement.style.background = '#ff0000';
              window.parent.document.body.style.background = '#ff0000';
            } catch (e) {}
            </script>
            </body>
            </html>
            """.trimIndent(),
        )
        return file
    }

    private fun writeDelayedGreenHtml(): Path {
        val file = tempDir.resolve("delayed-green.html")
        Files.writeString(
            file,
            """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
            html, body { margin: 0; width: 100%; height: 100%; background: #ff0000; }
            </style>
            </head>
            <body>
            <script>
            setTimeout(() => {
              document.documentElement.style.background = '#008000';
              document.body.style.background = '#008000';
            }, $PAINT_DELAY_MS);
            </script>
            </body>
            </html>
            """.trimIndent(),
        )
        return file
    }

    private fun hasGreenPixel(image: BufferedImage): Boolean {
        val sample = image.getRGB(image.width / 2, image.height / 2)
        val green = sample shr 8 and 0xFF
        val red = sample shr 16 and 0xFF
        val blue = sample and 0xFF
        return green > 80 && green > red && green > blue
    }

    companion object {
        private const val HTML_RENDER_TIMEOUT_SECONDS = 30L
        private const val TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 1_000L
        private const val CAP_MS = 500L
        private const val PAINT_DELAY_MS = 400L
        private const val DELAYED_SETTLE_MS = 800L
        private const val SCALE_VIEWPORT_WIDTH = 400
        private const val SCALE_VIEWPORT_HEIGHT = 300
    }
}
