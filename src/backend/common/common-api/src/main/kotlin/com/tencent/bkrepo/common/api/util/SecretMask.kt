package com.tencent.bkrepo.common.api.util

/**
 * 配置接口密钥：空值或整串 `*`（一个或多个）表示更新时保留原值。
 */
object SecretMask {
    const val MASKED = "*"

    fun isKeepExisting(value: String?) = value.isNullOrBlank() || value.all { it == '*' }

    fun mask(value: String?) = if (value.isNullOrBlank()) value else MASKED

    fun keep(incoming: String?, existing: String?) = if (isKeepExisting(incoming)) existing else incoming
}
