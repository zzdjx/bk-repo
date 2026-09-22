package com.tencent.bkrepo.fs.server.utils

import com.tencent.bkrepo.common.metadata.model.TMetadata

/**
 * 作品类型解析：先信元数据 [METADATA_ARTIFACT_TYPE]，非法或缺失再按扩展名回退。
 * 取值与分享侧 `ArtifactShareTypes` 对齐。
 */
object ArtifactThumbnailTypes {

    const val TYPE_IMAGE = "image"
    const val TYPE_PDF = "pdf"
    const val TYPE_HTML = "html"
    const val TYPE_CODE = "code"
    const val TYPE_TABLE = "table"
    const val TYPE_SLIDES = "slides"
    const val TYPE_MARKDOWN = "markdown"
    const val TYPE_VIDEO = "video"
    const val TYPE_AUDIO = "audio"
    const val TYPE_3D = "3d"
    const val TYPE_OTHER = "other"
    const val METADATA_ARTIFACT_NAME = "IMATE_ARTIFACT_NAME"
    const val METADATA_ARTIFACT_TYPE = "IMATE_ARTIFACT_TYPE"
    const val METADATA_ARTIFACT_THUMBNAIL = "IMATE_ARTIFACT_THUMBNAIL"
    const val METADATA_ARTIFACT_THUMBNAIL_INO = "IMATE_ARTIFACT_THUMBNAIL_INO"
    const val SIDECAR_SUFFIX = ".thumb.jpg"

    val KNOWN_TYPES = setOf(
        TYPE_IMAGE, TYPE_PDF, TYPE_HTML, TYPE_CODE, TYPE_TABLE,
        TYPE_SLIDES, TYPE_MARKDOWN, TYPE_VIDEO, TYPE_AUDIO, TYPE_OTHER,
    )

    fun resolve(metadataType: String?, fullPath: String): String {
        metadataType?.trim()?.lowercase()?.takeIf { it in KNOWN_TYPES }?.let { return it }
        return fromPath(fullPath) ?: TYPE_OTHER
    }

    /**
     * 从节点元数据解析作品类型，非法或缺失时按路径扩展名回退。
     */
    fun fromMetadata(metadata: List<TMetadata>?, fullPath: String): String {
        return resolve(metadataValue(metadata, METADATA_ARTIFACT_TYPE), fullPath)
    }

    fun metadataValue(metadata: List<TMetadata>?, key: String): String? {
        return metadata
            ?.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun sidecarFileName(originalName: String): String = ".$originalName$SIDECAR_SUFFIX"

    fun isSidecarFileName(name: String): Boolean {
        return name.startsWith(".") && name.endsWith(SIDECAR_SUFFIX)
    }

    fun upsertMetadata(existing: List<TMetadata>?, key: String, value: String): MutableList<TMetadata> {
        val result = existing.orEmpty().map { it.copy() }.toMutableList()
        val index = result.indexOfFirst { it.key.equals(key, ignoreCase = true) }
        if (index >= 0) {
            result[index] = result[index].copy(value = value)
        } else {
            result.add(TMetadata(key = key, value = value))
        }
        return result
    }

    /**
     * 去掉客户端提交的缩略图路径 / ino。这两个字段只允许服务端 persist sidecar 成功后写入。
     */
    fun withoutThumbnailKeys(metadata: MutableList<TMetadata>?): MutableList<TMetadata>? {
        if (metadata == null) {
            return null
        }
        return metadata.filterNot { isThumbnailKey(it.key) }.map { it.copy() }.toMutableList()
    }

    fun isThumbnailKey(key: String): Boolean {
        return key.equals(METADATA_ARTIFACT_THUMBNAIL, ignoreCase = true) ||
            key.equals(METADATA_ARTIFACT_THUMBNAIL_INO, ignoreCase = true)
    }

    fun fromPath(fullPath: String): String? {
        val ext = fullPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE_EXTS -> TYPE_IMAGE
            ext == "pdf" -> TYPE_PDF
            ext in HTML_EXTS -> TYPE_HTML
            ext in MARKDOWN_EXTS -> TYPE_MARKDOWN
            ext in TABLE_EXTS -> TYPE_TABLE
            ext in SLIDES_EXTS -> TYPE_SLIDES
            ext in CODE_EXTS -> TYPE_CODE
            ext in VIDEO_EXTS -> TYPE_VIDEO
            ext in AUDIO_EXTS -> TYPE_AUDIO
            else -> null
        }
    }

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
