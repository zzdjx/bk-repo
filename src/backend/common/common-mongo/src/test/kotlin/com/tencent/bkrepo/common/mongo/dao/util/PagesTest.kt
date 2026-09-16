package com.tencent.bkrepo.common.mongo.dao.util

import com.tencent.bkrepo.common.api.constant.DEFAULT_PAGE_NUMBER
import com.tencent.bkrepo.common.api.constant.DEFAULT_PAGE_SIZE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.data.domain.PageRequest

@DisplayName("分页工具类测试")
class PagesTest {

    @Test
    fun `ofRequest keeps valid page and size`() {
        val request = Pages.ofRequest(3, 15)
        assertEquals(2, request.pageNumber)
        assertEquals(15, request.pageSize)
    }

    @Test
    fun `ofRequest normalizes non-positive page`() {
        assertEquals(DEFAULT_PAGE_NUMBER - 1, Pages.ofRequest(0, 10).pageNumber)
        assertEquals(DEFAULT_PAGE_NUMBER - 1, Pages.ofRequest(-1, 10).pageNumber)
        assertEquals(10, Pages.ofRequest(0, 10).pageSize)
    }

    @Test
    fun `ofRequest normalizes non-positive size instead of throwing`() {
        val zeroSize: PageRequest = assertDoesNotThrow { Pages.ofRequest(1, 0) }
        assertEquals(0, zeroSize.pageNumber)
        assertEquals(DEFAULT_PAGE_SIZE, zeroSize.pageSize)

        val negativeSize: PageRequest = assertDoesNotThrow { Pages.ofRequest(2, -5) }
        assertEquals(1, negativeSize.pageNumber)
        assertEquals(DEFAULT_PAGE_SIZE, negativeSize.pageSize)
    }

    @Test
    fun `buildPage normalizes non-positive size`() {
        val records = (1..25).toList()
        val page = assertDoesNotThrow { Pages.buildPage(records, 1, 0) }
        assertEquals(DEFAULT_PAGE_NUMBER, page.pageNumber)
        assertEquals(DEFAULT_PAGE_SIZE, page.pageSize)
        assertEquals(DEFAULT_PAGE_SIZE, page.records.size)
        assertEquals((1..DEFAULT_PAGE_SIZE).toList(), page.records)
    }
}
