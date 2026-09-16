package com.tencent.bkrepo.huggingface.artifact

import com.tencent.bkrepo.huggingface.pojo.RepoFile
import com.tencent.bkrepo.huggingface.pojo.RepoFolder
import com.tencent.bkrepo.repository.pojo.node.NodeInfo

/**
 * 将仓库节点转换为 HuggingFace Hub tree 协议条目。
 */
internal object HfTreeConverter {

    /**
     * 把 [nodes] 转成相对 [revisionRoot] 的 tree 条目，并丢弃根节点本身以及前缀外的节点。
     */
    fun convert(nodes: List<NodeInfo>, revisionRoot: String): List<Any> {
        val prefix = revisionRoot.trimEnd('/') + '/'
        return nodes.mapNotNull { node ->
            convert(
                folder = node.folder,
                fullPath = node.fullPath,
                size = node.size,
                oid = node.sha256 ?: node.id.orEmpty(),
                prefix = prefix,
            )
        }
    }

    /**
     * 将单个文件节点转成 tree 条目，用于 `path_in_repo` 指向文件的场景。
     */
    fun convertFile(fullPath: String, size: Long, oid: String, revisionRoot: String): List<Any> {
        val prefix = revisionRoot.trimEnd('/') + '/'
        return listOfNotNull(convert(folder = false, fullPath = fullPath, size = size, oid = oid, prefix = prefix))
    }

    private fun convert(folder: Boolean, fullPath: String, size: Long, oid: String, prefix: String): Any? {
        if (!fullPath.startsWith(prefix)) {
            return null
        }
        val relativePath = fullPath.removePrefix(prefix)
        if (relativePath.isEmpty()) {
            return null
        }
        return if (folder) {
            RepoFolder(path = relativePath, oid = oid)
        } else {
            RepoFile(path = relativePath, size = size, oid = oid)
        }
    }
}
