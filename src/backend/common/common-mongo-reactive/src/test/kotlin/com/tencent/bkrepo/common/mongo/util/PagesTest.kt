package com.tencent.bkrepo.common.mongo.util

import com.tencent.bkrepo.common.api.constant.DEFAULT_PAGE_SIZE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

@DisplayName("响应式分页工具类测试")
class PagesTest {

    @Test
    fun `ofRequest normalizes non-positive size instead of throwing`() {
        val request = assertDoesNotThrow { Pages.ofRequest(1, 0) }
        assertEquals(0, request.pageNumber)
        assertEquals(DEFAULT_PAGE_SIZE, request.pageSize)
    }

    @Test
    fun `buildPage normalizes non-positive size`() {
        val page = assertDoesNotThrow { Pages.buildPage((1..5).toList(), 1, -1) }
        assertEquals(DEFAULT_PAGE_SIZE, page.pageSize)
        assertEquals(5, page.records.size)
    }
}
