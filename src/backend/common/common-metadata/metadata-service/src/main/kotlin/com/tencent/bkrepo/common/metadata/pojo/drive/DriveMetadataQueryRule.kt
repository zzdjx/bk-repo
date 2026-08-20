package com.tencent.bkrepo.common.metadata.pojo.drive

import com.tencent.bkrepo.common.query.enums.OperationType

/**
 * Drive 文件查询的元数据条件，语义对齐节点搜索中的 `metadata.{key}` 规则。
 * [operation] 为 `EXISTS` / `NULL` / `NOT_NULL` 时 [value] 不参与匹配，可省略。
 */
data class DriveMetadataQueryRule(
    val key: String,
    val value: Any = "",
    val operation: OperationType = OperationType.EQ,
)
