package com.tencent.bkrepo.common.query.util

import com.tencent.bkrepo.common.api.exception.ParameterInvalidException

/**
 * 校验自定义查询中的 Mongo 字段名，拒绝 `$where` / `$expr` 等操作符注入。
 */
object MongoQueryFieldNames {

    /**
     * 校验[field]可以作为查询、投影或排序字段。
     * [parameterName]用于异常提示，例如 field、select、sort。
     */
    fun validate(field: String, parameterName: String) {
        if (!isSafe(field)) {
            throw ParameterInvalidException(parameterName)
        }
    }

    /**
     * 字段名按 `.` 分段后，每一段都不能为空、不能以 `$` 开头，也不能包含空字节。
     */
    fun isSafe(field: String): Boolean {
        if (field.isEmpty() || field.contains(NULL_CHAR)) {
            return false
        }
        return field.split(PATH_SEPARATOR).all { segment ->
            segment.isNotEmpty() && !segment.startsWith(OPERATOR_PREFIX)
        }
    }

    private const val PATH_SEPARATOR = '.'
    private const val OPERATOR_PREFIX = '$'
    private const val NULL_CHAR = '\u0000'
}
