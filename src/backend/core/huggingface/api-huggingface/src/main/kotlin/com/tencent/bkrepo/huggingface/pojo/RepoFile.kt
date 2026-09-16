package com.tencent.bkrepo.huggingface.pojo

import com.fasterxml.jackson.annotation.JsonInclude
import com.tencent.bkrepo.huggingface.constants.TREE_TYPE_FILE

/**
 * HuggingFace Hub tree 接口中的文件条目，字段对齐官方 `type`/`oid`/`path`/`size`。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RepoFile(
    /**
     * 条目类型，文件固定为 `file`。
     */
    val type: String = TREE_TYPE_FILE,
    /**
     * 相对仓库根目录的文件路径。
     */
    val path: String,
    /**
     * 文件大小，单位 byte。
     */
    val size: Long,
    /**
     * 文件 blob oid，对应 huggingface_hub 的 `blob_id`。
     */
    val oid: String,
    /**
     * LFS 指针信息，非 LFS 文件为 null。
     */
    val lfs: BlobLfsInfo? = null,
    /**
     * 最近一次提交信息，仅 expand=true 时返回。
     */
    val lastCommit: LastCommitInfo? = null,
    /**
     * 安全扫描信息，仅 expand=true 时返回。
     */
    val security: BlobSecurityInfo? = null,
)
