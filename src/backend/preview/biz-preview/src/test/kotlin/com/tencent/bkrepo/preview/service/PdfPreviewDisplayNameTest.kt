package com.tencent.bkrepo.preview.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PDF 预览作品名")
class PdfPreviewDisplayNameTest {

    @Test
    fun `missing metadata stays unset`() {
        assertNull(PdfPreviewDisplayName.normalize(null))
        assertNull(PdfPreviewDisplayName.normalize("   "))
        assertNull(PdfPreviewDisplayName.normalize("///"))
        assertNull(PdfPreviewDisplayName.normalize("\n\t"))
    }

    @Test
    fun `plain name gains pdf suffix`() {
        assertEquals("周报汇总.pdf", PdfPreviewDisplayName.normalize("周报汇总"))
        assertEquals("周报汇总.pdf", PdfPreviewDisplayName.normalize("  周报汇总  "))
    }

    @Test
    fun `existing pdf suffix is kept with its case`() {
        assertEquals("周报.PDF", PdfPreviewDisplayName.normalize("周报.PDF"))
        assertEquals("周报.pdf", PdfPreviewDisplayName.normalize("周报.pdf"))
    }

    @Test
    fun `other suffix still gains pdf`() {
        assertEquals("周报.v2.pdf", PdfPreviewDisplayName.normalize("周报.v2"))
    }

    @Test
    fun `slashes newlines and ascii controls are removed`() {
        assertEquals("202609 周报.pdf", PdfPreviewDisplayName.normalize("2026/09 周报"))
        assertEquals("ab.pdf", PdfPreviewDisplayName.normalize("a\\b"))
        assertEquals("周报.pdf", PdfPreviewDisplayName.normalize("周\n报\u0007"))
        assertEquals("周\u2028报.pdf", PdfPreviewDisplayName.normalize("周\u2028报"))
    }

    @Test
    fun `internal spaces stay`() {
        assertEquals("周 报.pdf", PdfPreviewDisplayName.normalize("周 报"))
    }

    @Test
    fun `name longer than 200 chars is cut then suffixed`() {
        val normalized = PdfPreviewDisplayName.normalize("啊".repeat(250))
        assertEquals("啊".repeat(200) + ".pdf", normalized)
    }

    @Test
    fun `truncation that cuts the pdf suffix appends it again`() {
        val normalized = PdfPreviewDisplayName.normalize("啊".repeat(197) + ".pdf")
        assertEquals("啊".repeat(197) + ".pd.pdf", normalized)
    }

    @Test
    fun `name of 200 chars that already ends with pdf stays`() {
        val name = "啊".repeat(196) + ".pdf"
        assertEquals(name, PdfPreviewDisplayName.normalize(name))
    }
}
