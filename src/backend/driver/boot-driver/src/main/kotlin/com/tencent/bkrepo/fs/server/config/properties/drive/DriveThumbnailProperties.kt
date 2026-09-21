package com.tencent.bkrepo.fs.server.config.properties.drive

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 作品缩略图生成配置。完整文件上传成功后生成同目录隐藏 sidecar。
 */
@ConfigurationProperties("drive.thumbnail")
class DriveThumbnailProperties {

    /**
     * 是否在完整文件上传成功后生成作品缩略图。
     */
    var enabled: Boolean = false

    /**
     * 缩略图回填扫描的仓库名，仅处理该名称且类型为 DRIVE 的仓库。
     */
    var backfillRepoName: String = "imate-artifact"

    /**
     * PUT / 调用方等待生成器的硬超时。到期后中止生成线程并关闭已登记资源，立即 Failed。
     */
    var timeout: Duration = Duration.ofSeconds(5)

    /**
     * 图片、视频输出 JPEG 长边像素。
     */
    var maxEdge: Int = 256

    /**
     * HTML 输出 JPEG 长边像素。默认 1920（1080p）：DPR 2 截 2560×1440 再缩到 1920×1080。
     */
    var htmlMaxEdge: Int = 1920

    /**
     * PDF / PPT / Markdown / 表格 / SVG 输出 JPEG 长边像素。默认 1920（1080p），避免卡片上文字被放大发糊。
     */
    var documentMaxEdge: Int = 1920

    /**
     * JPEG quality，0.1–1.0。
     */
    var jpegQuality: Float = 1.0f

    /**
     * 不生成 sidecar 的作品类型（客户端用本地图标）。
     */
    var skipTypes: MutableSet<String> = mutableSetOf("code", "audio", "other")

    /**
     * 按作品类型的最大并发生成数；未列出的类型走 [defaultConcurrency]。
     * 获取不到许可时立即 Failed，不阻塞调用方。
     * `html` 每个许可对应一条 Playwright 专用线程和一份 Chromium，API 不跨线程。
     * 默认按 driver `-Xmx8G`、各类型同时打满来估：缩略图堆约 2.5–3.5G，其余留给上传与 GC。
     */
    var concurrency: MutableMap<String, Int> = mutableMapOf(
        "image" to 16,
        "pdf" to 6,
        "video" to 4,
        "html" to 3,
        "slides" to 4,
        "table" to 4,
        "markdown" to 8,
    )

    /**
     * 未在 [concurrency] 中出现的类型的并发上限。`<= 0` 表示不限流。
     */
    var defaultConcurrency: Int = 4

    /**
     * HTML 源文件最多读入的字节数，超出部分截断后再送入沙箱。
     */
    var htmlMaxBytes: Int = 32 * 1024 * 1024

    /**
     * HTML 沙箱视口宽度（像素）。截图像素宽约为本值 × [htmlDeviceScaleFactor]。
     */
    var htmlViewportWidth: Int = 1280

    /**
     * HTML 沙箱视口高度（像素）。截图像素高约为本值 × [htmlDeviceScaleFactor]。
     */
    var htmlViewportHeight: Int = 720

    /**
     * Playwright 设备像素比。2 按视网膜密度截文字和 canvas；输出长边受 [htmlMaxEdge] 限制。
     */
    var htmlDeviceScaleFactor: Double = 2.0

    /**
     * 是否启用 Chromium 进程沙箱。HTML 文档本身始终在 iframe `sandbox="allow-scripts"` 中渲染。
     * 以 root 运行时 Chromium 不支持进程沙箱，会自动关闭；无 user namespace 时也会回退关闭。
     */
    var htmlChromiumSandbox: Boolean = true

    /**
     * HTML 写入沙箱 iframe 后、截图前的等待时间，给 CSS/JS 动画留出首屏时间。
     */
    var htmlSettleDelay: Duration = Duration.ofSeconds(1)
}
