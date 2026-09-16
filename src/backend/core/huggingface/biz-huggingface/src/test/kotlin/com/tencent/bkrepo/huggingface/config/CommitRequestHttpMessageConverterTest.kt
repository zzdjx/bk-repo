package com.tencent.bkrepo.huggingface.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

@DisplayName("Commit ndjson 消息转换器选择")
class CommitRequestHttpMessageConverterTest {

    private val converter = CommitRequestHttpMessageConverter(jacksonObjectMapper())

    @Test
    fun `does not write json list responses such as tree api`() {
        assertFalse(converter.canWrite(ArrayList::class.java, MediaType.APPLICATION_JSON))
        assertFalse(converter.canWrite(ArrayList::class.java, MediaType.ALL))
        assertFalse(converter.canWrite(ArrayList::class.java, null))
    }

    @Test
    fun `writes and reads commit ndjson lists`() {
        assertTrue(converter.canWrite(ArrayList::class.java, CommitRequestHttpMessageConverter.NDJSON))
        assertTrue(converter.canRead(ArrayList::class.java, CommitRequestHttpMessageConverter.NDJSON))
        assertTrue(converter.canRead(ArrayList::class.java, null))
    }

    @Test
    fun `does not read json lists as commit ndjson`() {
        assertFalse(converter.canRead(ArrayList::class.java, MediaType.APPLICATION_JSON))
        assertFalse(converter.canRead(String::class.java, CommitRequestHttpMessageConverter.NDJSON))
    }
}
