package com.tencent.bkrepo.preview.service.share

import com.tencent.bkrepo.common.artifact.path.PathUtils

/**
 * 分享 downloadToken 的绑定路径：父目录深度超过阈值时绑父目录（便于同目录 sidecar），否则绑原文件。
 */
object ArtifactShareDownloadTokenPath {

    /**
     * 父目录路径段数大于 [minParentDepth] 时返回父目录（带尾斜杠），否则返回规范化后的文件路径。
     * [minParentDepth] 小于等于 0 时，非根路径一律绑定父目录。
     */
    fun resolve(fullPath: String, minParentDepth: Int): String {
        val filePath = PathUtils.normalizeFullPath(fullPath)
        val parent = PathUtils.resolveParent(filePath)
        if (PathUtils.isRoot(parent)) {
            return filePath
        }
        val parentDepth = filePath.count { it == PathUtils.UNIX_SEPARATOR } - 1
        if (parentDepth <= minParentDepth) {
            return filePath
        }
        return parent
    }
}
