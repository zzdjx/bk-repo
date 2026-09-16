package com.tencent.bkrepo.pypi.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PyPI simple HTML 转义")
class HtmlUtilsTest {

    @Test
    fun `escape encodes quotes ampersand and angle brackets`() {
        assertEquals("&lt;&gt;&quot;&#39;&amp;", HtmlUtils.escape("<>\"'&"))
    }
}
