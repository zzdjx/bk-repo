package com.tencent.bkrepo.preview.service

import com.tencent.bkrepo.preview.pojo.FileAttribute

/**
 * 幻灯片可在浏览器内用 pptx-viewer-core 播放，不必先转 PDF。
 * `.ppt` / `.pptx` / `.pptm` / `.ppsx` 都走动画预览；只有 OOXML 才能按 ZIP 剥离内嵌音视频。
 */
object LivePptxPreview {
    private val SUFFIXES = setOf("ppt", "pptx", "pptm", "ppsx")
    private val OOXML_SUFFIXES = setOf("pptx", "pptm", "ppsx")

    fun isSupported(suffix: String?): Boolean {
        return suffix?.lowercase() in SUFFIXES
    }

    /**
     * 可按 ZIP 解析并单独流出 `ppt/media` 的 OOXML 后缀。
     */
    fun isOoxml(suffix: String?): Boolean {
        return suffix?.lowercase() in OOXML_SUFFIXES
    }

    /**
     * 登录态/分享 token 的仓库文件：原样流出幻灯片，供前端动画播放。
     */
    fun shouldStreamOriginal(fileAttribute: FileAttribute): Boolean {
        return fileAttribute.storageType == 0 && shouldSkipOfficeConvert(fileAttribute)
    }

    /**
     * 远程 office 仍走下载缓存链路，但跳过 LibreOffice。
     */
    fun shouldSkipOfficeConvert(fileAttribute: FileAttribute): Boolean {
        return isSupported(fileAttribute.suffix) && !fileAttribute.forcePdfConvert
    }
}
