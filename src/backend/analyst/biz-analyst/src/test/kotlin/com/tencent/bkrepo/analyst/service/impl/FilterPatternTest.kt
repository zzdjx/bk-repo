package com.tencent.bkrepo.analyst.service.impl

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class FilterPatternTest {
    @Test
    fun `literal path with parentheses should match`() {
        val path = "/service-generic (1).jar"
        Assertions.assertTrue(matchesFilterPattern(path, path))
    }

    @Test
    fun `regex path should still match`() {
        Assertions.assertTrue(matchesFilterPattern("/.*\\.jar", "/service-generic (1).jar"))
        Assertions.assertFalse(matchesFilterPattern("/.*\\.jar", "/service-generic (1).tgz"))
    }
}
