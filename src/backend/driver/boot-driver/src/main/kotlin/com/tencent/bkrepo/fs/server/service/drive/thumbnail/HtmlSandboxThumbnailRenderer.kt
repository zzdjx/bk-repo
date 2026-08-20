package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ScreenshotType
import com.tencent.bkrepo.fs.server.config.properties.drive.DriveThumbnailProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * HTML 缩略图：Playwright Chromium 打开隔离页，把文档放进
 * `sandbox="allow-scripts"`（无 `allow-same-origin`）的 iframe，等首屏动画后再截图。
 * 上下文离线并拦截非 `about:` / `data:` 请求，避免不可信 HTML 访问本地文件或外网。
 *
 * 运行时不会自动下载浏览器；部署机需预先安装 Playwright Chromium。
 * 以 root 运行时 Chromium 会拒绝进程沙箱，自动关闭；非 root 时默认开启，起不来再关闭。
 * HTML iframe 沙箱始终生效。
 */
@Component
class HtmlSandboxThumbnailRenderer(
    private val properties: DriveThumbnailProperties,
) : HtmlThumbnailRenderer, AutoCloseable {

    @Volatile
    private var playwright: Playwright? = null

    @Volatile
    private var browser: Browser? = null

    override fun render(input: Path): BufferedImage? {
        val html = ThumbnailPreviewText.read(input, properties.htmlMaxBytes).trim()
        if (html.isEmpty()) {
            return null
        }
        val running = synchronized(lock) { ensureBrowser() }
        return screenshotSandbox(running, html)
    }

    @PostConstruct
    fun warmup() {
        thread(isDaemon = true, name = WARMUP_THREAD) {
            runCatching { start() }
                .onFailure { logger.warn("HTML thumbnail Playwright warmup failed: ${it.message}") }
        }
    }

    /**
     * 启动 Chromium。上传路径依赖预热；测试可在截图前显式调用，避免把下载算进生成超时。
     */
    fun start() {
        synchronized(lock) {
            ensureBrowser()
        }
    }

    @PreDestroy
    override fun close() {
        synchronized(lock) {
            closeQuietly()
        }
    }

    private fun ensureBrowser(): Browser {
        browser?.takeIf { it.isConnected }?.let { return it }
        closeQuietly()
        val preferSandbox = properties.htmlChromiumSandbox && !runningAsRoot()
        if (properties.htmlChromiumSandbox && !preferSandbox) {
            logger.warn(
                "HTML thumbnail Chromium process sandbox disabled, running as root; iframe sandbox still applies",
            )
        }
        return try {
            launchBrowser(preferSandbox)
        } catch (e: Exception) {
            if (!preferSandbox) {
                throw e
            }
            logger.warn(
                "HTML thumbnail Chromium sandbox launch failed, retry without process sandbox: ${e.message}",
            )
            launchBrowser(false)
        }
    }

    private fun launchBrowser(chromiumSandbox: Boolean): Browser {
        val created = Playwright.create(
            Playwright.CreateOptions().setEnv(System.getenv() + (SKIP_BROWSER_DOWNLOAD to "1")),
        )
        try {
            val launched = created.chromium().launch(
                BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setChromiumSandbox(chromiumSandbox)
                    .setArgs(CHROMIUM_ARGS),
            )
            playwright = created
            browser = launched
            logger.info("HTML thumbnail Playwright Chromium started sandbox[$chromiumSandbox]")
            return launched
        } catch (e: Exception) {
            runCatching { created.close() }
            throw e
        }
    }

    private fun closeQuietly() {
        val runningBrowser = browser
        val runningPlaywright = playwright
        browser = null
        playwright = null
        runCatching { runningBrowser?.close() }
            .onFailure { logger.warn("Close HTML thumbnail browser failed: ${it.message}") }
        runCatching { runningPlaywright?.close() }
            .onFailure { logger.warn("Close HTML thumbnail Playwright failed: ${it.message}") }
    }

    private fun contextOptions() = Browser.NewContextOptions()
        .setViewportSize(
            properties.htmlViewportWidth.coerceAtLeast(1),
            properties.htmlViewportHeight.coerceAtLeast(1),
        )
        .setDeviceScaleFactor(properties.htmlDeviceScaleFactor.coerceAtLeast(1.0))
        .setJavaScriptEnabled(true)
        .setAcceptDownloads(false)
        .setOffline(true)
        .setBypassCSP(false)

    private fun screenshotSandbox(running: Browser, html: String): BufferedImage? {
        val timeoutMs = properties.timeout.toMillis().coerceAtLeast(1)
        val settleMs = settleWaitMillis(properties.htmlSettleDelay, timeoutMs)
        val screenshotTimeoutMs = (timeoutMs - settleMs).coerceAtLeast(1).toDouble()
        return running.newContext(contextOptions()).use { context ->
            context.setDefaultTimeout(timeoutMs.toDouble())
            context.setDefaultNavigationTimeout(timeoutMs.toDouble())
            context.route("**/*") { route ->
                if (allowedSandboxRequest(route.request().url())) {
                    route.resume()
                } else {
                    route.abort()
                }
            }
            context.newPage().use { page ->
                page.setDefaultTimeout(timeoutMs.toDouble())
                page.setContent(WRAPPER_HTML)
                page.evaluate(loadSandboxJs(), html)
                if (settleMs > 0) {
                    page.waitForTimeout(settleMs.toDouble())
                }
                val bytes = page.screenshot(
                    Page.ScreenshotOptions()
                        .setType(ScreenshotType.PNG)
                        .setFullPage(false)
                        .setTimeout(screenshotTimeoutMs),
                )
                ImageIO.read(ByteArrayInputStream(bytes))
            }
        }
    }

    companion object {
        internal const val IFRAME_SANDBOX_VALUE = "allow-scripts"
        private const val WARMUP_THREAD = "drive-html-thumb-warmup"
        private const val EMPTY_BASE = "about:blank"
        private const val ABOUT_SRCDOC = "about:srcdoc"
        private const val DATA_URL_PREFIX = "data:"
        private const val SKIP_BROWSER_DOWNLOAD = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"
        private val CHROMIUM_ARGS = listOf(
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--mute-audio",
        )
        private val WRAPPER_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8"/>
            <style>
            html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #ffffff; overflow: hidden; }
            </style>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        private val lock = Any()
        private val logger = LoggerFactory.getLogger(HtmlSandboxThumbnailRenderer::class.java)

        internal fun loadSandboxJs(): String = """
            (html) => {
              const iframe = document.createElement('iframe');
              iframe.setAttribute('sandbox', '$IFRAME_SANDBOX_VALUE');
              iframe.setAttribute('referrerpolicy', 'no-referrer');
              iframe.setAttribute('id', 'thumb-sandbox');
              iframe.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;border:0;';
              const loaded = new Promise((resolve) => {
                iframe.addEventListener('load', () => resolve(), { once: true });
              });
              iframe.srcdoc = html;
              document.body.appendChild(iframe);
              return loaded;
            }
        """.trimIndent()

        internal fun settleWaitMillis(delay: Duration, timeoutMs: Long): Long {
            if (delay.isZero || delay.isNegative) {
                return 0
            }
            return delay.toMillis().coerceAtLeast(0).coerceAtMost(timeoutMs.coerceAtLeast(0))
        }

        internal fun runningAsRoot(): Boolean {
            val os = System.getProperty("os.name").orEmpty()
            if (os.contains("Windows", ignoreCase = true)) {
                return false
            }
            val uid = procUid()
            if (uid != null) {
                return uid == 0
            }
            return System.getProperty("user.name") == "root"
        }

        private fun procUid(): Int? {
            return try {
                Files.readString(Path.of("/proc/self/status"))
                    .lineSequence()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            } catch (_: Exception) {
                null
            }
        }

        internal fun allowedSandboxRequest(url: String): Boolean {
            val value = url.trim()
            return matchesAbout(value, EMPTY_BASE, allowQuery = true) ||
                matchesAbout(value, ABOUT_SRCDOC, allowQuery = false) ||
                value.startsWith(DATA_URL_PREFIX)
        }

        private fun matchesAbout(value: String, base: String, allowQuery: Boolean): Boolean {
            return value == base ||
                value.startsWith("$base#") ||
                (allowQuery && value.startsWith("$base?"))
        }
    }
}
