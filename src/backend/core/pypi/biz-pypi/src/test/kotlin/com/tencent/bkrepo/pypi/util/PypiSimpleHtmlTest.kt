package com.tencent.bkrepo.pypi.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PyPI simple HTML 拼接")
class PypiSimpleHtmlTest {

    @Test
    fun `requires-python quote cannot introduce extra attributes`() {
        val html = PypiSimpleHtml.fileNodeLink(
            fullPath = "/demo/1.0/demo-1.0.whl",
            name = "demo-1.0.whl",
            sha256 = "abc",
            requiresPython = ">=3.6\" data-x=\"injected",
        )
        val anchor = firstAnchor(html)
        assertEquals(">=3.6\" data-x=\"injected", anchor.attr("data-requires-python"))
        assertFalse(anchor.hasAttr("data-x"))
        assertEquals("../../packages/demo/1.0/demo-1.0.whl#sha256=abc", anchor.attr("href"))
        assertEquals("demo-1.0.whl", anchor.text())
    }

    @Test
    fun `filename quote cannot introduce extra attributes`() {
        val name = "demo-1.0.whl\" data-y=\"injected"
        val html = PypiSimpleHtml.fileNodeLink(
            fullPath = "/demo/1.0/$name",
            name = name,
            sha256 = "abc",
            requiresPython = null,
        )
        val anchor = firstAnchor(html)
        assertFalse(anchor.hasAttr("data-y"))
        assertEquals(name, anchor.text())
    }

    @Test
    fun `package list name cannot inject extra tags`() {
        val html = PypiSimpleHtml.packageListItem("demo<img data-z=1>")
        val fragment = Jsoup.parseBodyFragment(html)
        val anchor = firstAnchor(html)
        assertEquals("demo<img data-z=1>", anchor.text())
        assertEquals(0, fragment.select("img").size)
        assertFalse(anchor.hasAttr("data-z"))
    }

    @Test
    fun `package list href follows pep 503 normalization`() {
        val html = PypiSimpleHtml.packageListItem("My_Package")
        val anchor = firstAnchor(html)
        assertEquals("my-package/", anchor.attr("href"))
        assertEquals("My_Package", anchor.text())
    }

    @Test
    fun `page title cannot inject extra tags`() {
        val html = PypiSimpleHtml.page("Links for demo</title><img data-t=1>", "    list")
        val doc = Jsoup.parse(html)
        assertEquals("Links for demo</title><img data-t=1>", doc.title())
        assertEquals(0, doc.select("body img").size)
    }

    private fun firstAnchor(html: String): Element {
        return checkNotNull(Jsoup.parseBodyFragment(html).selectFirst("a"))
    }
}
