package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * 将 HTML 渲成首屏图像。实现必须在隔离沙箱中执行不可信文档。
 */
fun interface HtmlThumbnailRenderer {

    fun render(input: Path): BufferedImage?
}
