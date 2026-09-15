package com.tencent.bkrepo.preview.service.share

/**
 * 作品类型：优先节点元数据 [DriveShareNodeResolver.METADATA_ARTIFACT_TYPE]，
 * 旧记录缺失时按文件路径扩展名回退，取值与搜索接口 `type` 对齐。
 */
object ArtifactShareTypes {

    fun resolve(storedOrMetadata: String?, fullPath: String): String? {
        storedOrMetadata?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return fromPath(fullPath)
    }

    fun fromPath(fullPath: String): String? {
        val name = fullPath.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0) {
            return null
        }
        val ext = name.substring(dot + 1).lowercase()
        return when {
            ext in IMAGE_EXTS -> "image"
            ext == "pdf" -> "pdf"
            ext in HTML_EXTS -> "html"
            ext in MARKDOWN_EXTS -> "markdown"
            ext in TABLE_EXTS -> "table"
            ext in SLIDES_EXTS -> "slides"
            ext in CODE_EXTS -> "code"
            ext in VIDEO_EXTS -> "video"
            ext in AUDIO_EXTS -> "audio"
            ext == "glb" -> "3d"
            else -> null
        }
    }

    /**
     * Chrome 内置阅读器的「演示」要求 PDF 是标签页顶层文档。
     * 分享短链只对原生 PDF 直接输出 application/pdf；幻灯片仍走内嵌页 + PDF.js。
     */
    fun usesNativePdfViewer(type: String?, fullPath: String? = null): Boolean {
        return resolvedType(type, fullPath) == PDF_TYPE
    }

    private fun resolvedType(type: String?, fullPath: String?): String? {
        val stored = type?.trim()?.takeIf { it.isNotEmpty() }
        return (stored ?: fullPath?.let { fromPath(it) })?.lowercase()
    }

    private const val PDF_TYPE = "pdf"
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")
    private val HTML_EXTS = setOf("html", "htm")
    private val MARKDOWN_EXTS = setOf("md", "markdown")
    private val TABLE_EXTS = setOf("xls", "xlsx", "csv")
    private val SLIDES_EXTS = setOf("ppt", "pptx", "key")
    private val VIDEO_EXTS = setOf("mp4", "webm")
    private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "oga", "m4a")
    private val CODE_EXTS = setOf(
        "ts", "tsx", "js", "jsx", "py", "go", "rs", "java", "kt", "json", "yaml", "yml", "sh", "sql",
    )
}
