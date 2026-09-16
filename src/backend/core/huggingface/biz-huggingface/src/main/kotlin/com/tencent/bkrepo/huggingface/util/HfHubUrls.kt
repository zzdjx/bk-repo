package com.tencent.bkrepo.huggingface.util

import com.tencent.bkrepo.huggingface.constants.REPO_TYPE_DATASET
import com.tencent.bkrepo.huggingface.constants.REPO_TYPE_MODEL
import com.tencent.bkrepo.huggingface.exception.OperationNotSupportException

/**
 * 构造 huggingface_hub 1.29+ `parse_hf_uri` 可解析的仓库路径。
 * 类型前缀必须是复数（`models/`、`datasets/`）；单数 `model/` 会触发 `HfUriError`。
 * model 也可省略类型前缀，与官方 Hub web 路径一致。
 */
object HfHubUrls {

    /**
     * Hub 可解析的相对路径：model 为 `{namespace}/{name}`，dataset 为 `datasets/{namespace}/{name}`。
     */
    fun repoUrlPath(type: String, repoId: String): String {
        return when (type) {
            REPO_TYPE_MODEL -> repoId
            REPO_TYPE_DATASET -> "datasets/$repoId"
            else -> throw OperationNotSupportException()
        }
    }

    /**
     * commit 接口响应中的 `commitUrl`，供客户端按 `/commit/` 截出仓库路径后再解析。
     */
    fun commitUrl(type: String, repoId: String, commitOid: String): String {
        return "${repoUrlPath(type, repoId)}/commit/$commitOid"
    }
}

