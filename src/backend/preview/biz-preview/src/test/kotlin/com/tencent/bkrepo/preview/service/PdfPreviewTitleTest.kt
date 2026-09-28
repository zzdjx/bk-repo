package com.tencent.bkrepo.preview.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.DomXmpParser
import org.apache.xmpbox.xml.XmpSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

@DisplayName("PDF 预览标题改写")
class PdfPreviewTitleTest {

    @Test
    fun `matching info title keeps the original bytes`() {
        val pdf = pdfWithTitles(info = "  周报.pdf  ", xmp = "其他")

        val rewritten = PdfPreviewTitle.rewrite(pdf, "周报.pdf")

        assertTrue(pdf.contentEquals(rewritten))
    }

    @Test
    fun `missing info title is rewritten in info and xmp`() {
        val pdf = pdfWithTitles(info = null, xmp = "其他")

        val rewritten = PdfPreviewTitle.rewrite(pdf, "周报.pdf")

        assertEquals("周报.pdf", infoTitle(rewritten))
        assertEquals("周报.pdf", xmpTitle(rewritten))
    }

    @Test
    fun `different info title is replaced`() {
        val pdf = pdfWithTitles(info = "草稿", xmp = null)

        val rewritten = PdfPreviewTitle.rewrite(pdf, "周报.pdf")

        assertEquals("周报.pdf", infoTitle(rewritten))
        assertEquals("周报.pdf", xmpTitle(rewritten))
    }

    @Test
    fun `encrypted pdf is left unchanged`() {
        val pdf = encryptedPdf()

        val rewritten = PdfPreviewTitle.rewrite(pdf, "周报.pdf")

        assertTrue(pdf.contentEquals(rewritten))
    }

    @Test
    fun `corrupt bytes are left unchanged`() {
        val pdf = "not a pdf".toByteArray()

        val rewritten = PdfPreviewTitle.rewrite(pdf, "周报.pdf")

        assertTrue(pdf.contentEquals(rewritten))
    }
}

private fun pdfWithTitles(info: String?, xmp: String?): ByteArray {
    PDDocument().use { document ->
        document.addPage(PDPage())
        if (info != null) {
            document.documentInformation.title = info
        }
        if (xmp != null) {
            val metadata = XMPMetadata.createXMPMetadata()
            val dublinCore = metadata.createAndAddDublinCoreSchema()
            dublinCore.setTitle(xmp)
            val serialized = ByteArrayOutputStream()
            XmpSerializer().serialize(metadata, serialized, true)
            val pdMetadata = PDMetadata(document)
            pdMetadata.importXMPMetadata(serialized.toByteArray())
            document.documentCatalog.metadata = pdMetadata
        }
        val output = ByteArrayOutputStream()
        document.save(output)
        return output.toByteArray()
    }
}

private fun encryptedPdf(): ByteArray {
    PDDocument().use { document ->
        document.addPage(PDPage())
        val policy = StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission())
        policy.encryptionKeyLength = 128
        document.protect(policy)
        val output = ByteArrayOutputStream()
        document.save(output)
        return output.toByteArray()
    }
}

private fun infoTitle(bytes: ByteArray): String? {
    return PDDocument.load(bytes).use { it.documentInformation.title }
}

private fun xmpTitle(bytes: ByteArray): String? {
    return PDDocument.load(bytes).use { document ->
        val metadata = document.documentCatalog.metadata ?: return null
        DomXmpParser().parse(metadata.createInputStream()).dublinCoreSchema?.title
    }
}
