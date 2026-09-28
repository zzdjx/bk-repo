package com.tencent.bkrepo.preview.service

/**
 * 把节点元数据 IMATE_ARTIFACT_NAME 收成 PDF 预览用的作品名。
 * 清洗后为空则返回 null，调用方继续用路径文件名。
 */
object PdfPreviewDisplayName {

    fun normalize(raw: String?): String? {
        if (raw == null) {
            return null
        }
        val cleaned = strip(raw).trim()
        if (cleaned.isEmpty()) {
            return null
        }
        val truncated = truncate(cleaned)
        return if (truncated.endsWith(PDF_SUFFIX, ignoreCase = true)) {
            truncated
        } else {
            truncated + PDF_SUFFIX
        }
    }

    private fun strip(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch == '/' || ch == '\\' || ch.code <= ASCII_CONTROL_MAX || ch.code == ASCII_DELETE) {
                continue
            }
            out.append(ch)
        }
        return out.toString()
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_LENGTH) {
            return value
        }
        val end = if (value[MAX_LENGTH - 1].isHighSurrogate()) MAX_LENGTH - 1 else MAX_LENGTH
        return value.substring(0, end)
    }

    private const val MAX_LENGTH = 200
    private const val PDF_SUFFIX = ".pdf"
    private const val ASCII_CONTROL_MAX = 0x1F
    private const val ASCII_DELETE = 0x7F
}
