package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

internal object ThumbnailGraphics {

    fun <T> use(image: BufferedImage, block: (Graphics2D) -> T): T {
        val graphics = image.createGraphics()
        try {
            return block(graphics)
        } finally {
            graphics.dispose()
        }
    }

    fun fillWhite(graphics: Graphics2D, width: Int, height: Int) {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
    }

    fun applyQualityHints(graphics: Graphics2D, text: Boolean = false) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (text) {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        }
    }
}
