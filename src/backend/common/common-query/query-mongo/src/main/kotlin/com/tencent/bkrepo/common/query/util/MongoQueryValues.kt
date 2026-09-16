package com.tencent.bkrepo.common.query.util

import com.tencent.bkrepo.common.api.exception.ParameterInvalidException

/**
 * 校验自定义查询中的 value，拒绝把 `{ "$gt": "" }` 这类 Map 原样写入 BSON 后被 Mongo 当成操作符。
 */
object MongoQueryValues {

    /**
     * 校验[value]可以作为查询条件值。
     * [parameterName]用于异常提示，默认 value。
     */
    fun validate(value: Any?, parameterName: String = "value") {
        if (!isSafe(value)) {
            throw ParameterInvalidException(parameterName)
        }
    }

    /**
     * 标量直接通过；Map 的 key 不能以 `$` 开头或包含空字节；List / Array 递归检查元素。
     */
    fun isSafe(value: Any?): Boolean {
        return when (value) {
            null, is String, is Number, is Boolean -> true
            is Map<*, *> -> value.all { (key, nested) -> isSafeKey(key) && isSafe(nested) }
            is Iterable<*> -> value.all { isSafe(it) }
            is Array<*> -> value.all { isSafe(it) }
            else -> true
        }
    }

    private fun isSafeKey(key: Any?): Boolean {
        if (key !is String || key.contains(NULL_CHAR)) {
            return false
        }
        return !key.startsWith(OPERATOR_PREFIX)
    }

    private const val OPERATOR_PREFIX = '$'
    private const val NULL_CHAR = '\u0000'
}
