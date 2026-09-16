package com.tencent.bkrepo.preview.service

/**
 * 浏览器可直接预览的 ONLINE3D 范围（v1 仅自包含 GLB）。
 */
object Online3dPreview {
    const val GLB_SUFFIX = "glb"
    private const val BYTES_PER_MB = 1024L * 1024L

    fun isGlb(suffix: String?): Boolean {
        return suffix.equals(GLB_SUFFIX, ignoreCase = true)
    }

    fun megabytesToBytes(mb: Long): Long {
        return mb * BYTES_PER_MB
    }
}
