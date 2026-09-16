package com.tencent.bkrepo.common.query.util

import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Mongo 查询字段名校验")
class MongoQueryFieldNamesTest {

    @Test
    fun `accept normal and nested fields`() {
        listOf("projectId", "fullPath", "metadata.key", "copyFromCredentialsKey", "_id").forEach { field ->
            assertTrue(MongoQueryFieldNames.isSafe(field), field)
            MongoQueryFieldNames.validate(field, "field")
        }
    }

    @Test
    fun `reject operator and malformed fields`() {
        listOf(
            "\$where",
            "\$expr",
            "\$function",
            "a.\$gt",
            "metadata.\$where",
            "",
            ".",
            "foo.",
            ".foo",
            "foo..bar",
            "foo\u0000bar"
        ).forEach { field ->
            assertFalse(MongoQueryFieldNames.isSafe(field), field)
            assertThrows<ParameterInvalidException> {
                MongoQueryFieldNames.validate(field, "field")
            }
        }
    }
}
