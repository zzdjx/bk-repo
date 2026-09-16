package com.tencent.bkrepo.huggingface.pojo

import com.fasterxml.jackson.annotation.JsonInclude
import com.tencent.bkrepo.huggingface.constants.TREE_TYPE_DIRECTORY

/**
 * HuggingFace Hub tree 接口中的目录条目，字段对齐官方 `type`/`oid`/`path`/`size`。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RepoFolder(
    /**
     * 条目类型，目录固定为 `directory`。
     */
    val type: String = TREE_TYPE_DIRECTORY,
    /**
     * 相对仓库根目录的目录路径。
     */
    val path: String,
    /**
     * 目录 tree oid，对应 huggingface_hub 的 `tree_id`。
     */
    val oid: String,
    /**
     * 目录大小，官方协议中为 0。
     */
    val size: Long = 0,
    /**
     * 最近一次提交信息，仅 expand=true 时返回。
     */
    val lastCommit: LastCommitInfo? = null,
)
