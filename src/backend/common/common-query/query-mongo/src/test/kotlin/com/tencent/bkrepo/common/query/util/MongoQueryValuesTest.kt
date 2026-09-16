package com.tencent.bkrepo.common.query.util

import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

@DisplayName("Mongo 查询条件值校验")
class MongoQueryValuesTest {

    @Test
    fun `accept scalar and ordinary nested values`() {
        listOf(
            null,
            "foo",
            1,
            1L,
            true,
            LocalDateTime.now(),
            mapOf("env" to "prod"),
            mapOf("nested" to mapOf("key" to "value")),
            listOf("r1", "r2"),
            listOf(mapOf("k" to "v")),
            arrayOf("a", "b")
        ).forEach { value ->
            assertTrue(MongoQueryValues.isSafe(value), value.toString())
            MongoQueryValues.validate(value)
        }
    }

    @Test
    fun `reject operator map and nested operator keys`() {
        listOf(
            mapOf("\$gt" to ""),
            mapOf("\$exists" to true),
            mapOf("label" to mapOf("\$ne" to "")),
            listOf(mapOf("\$in" to listOf("a"))),
            arrayOf(mapOf("\$regex" to ".*")),
            mapOf("\$gt" to "", "size" to 1),
            mapOf("\u0000key" to "v"),
            mapOf(1 to "v")
        ).forEach { value ->
            assertFalse(MongoQueryValues.isSafe(value), value.toString())
            assertThrows<ParameterInvalidException> {
                MongoQueryValues.validate(value)
            }
        }
    }
}
