package com.tencent.bkrepo.preview.service

/**
 * 内联预览按后缀强制的响应 Content-Type（媒体、PDF、PPTX）。
 */
object MediaPreviewContentType {
    private val SUFFIX_TO_CONTENT_TYPE = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "qt" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "3gp" to "video/3gpp",
        "asf" to "video/x-ms-asf",
        "flv" to "video/x-flv",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "m4a" to "audio/mp4",
        "wma" to "audio/x-ms-wma",
        "aac" to "audio/aac",
        "pdf" to "application/pdf",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "ppt" to "application/vnd.ms-powerpoint"
    )

    /**
     * 将后缀映射为浏览器可内联预览的 MIME。未知后缀返回 null。
     */
    fun fromSuffix(suffix: String): String? {
        return SUFFIX_TO_CONTENT_TYPE[suffix.lowercase()]
    }
}
