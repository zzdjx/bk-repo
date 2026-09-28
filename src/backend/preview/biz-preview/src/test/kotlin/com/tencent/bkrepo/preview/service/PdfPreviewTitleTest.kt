package com.tencent.bkrepo.preview.service

import org.apache.pdfbox.io.RandomAccessBuffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.DomXmpParser
import org.apache.xmpbox.xml.XmpSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@DisplayName("PDF 预览标题改写")
class PdfPreviewTitleTest {

    @Test
    fun `matching info title does not append an update`() {
        val pdf = pdfWithTitles(info = "  周报.pdf  ", xmp = "其他")

        val suffix = PdfPreviewTitle.incrementalUpdate(RandomAccessBuffer(pdf), "周报.pdf")

        assertNull(suffix)
    }

    @Test
    fun `missing info title is appended in info and xmp`() {
        val pdf = pdfWithTitles(info = null, xmp = "其他")

        val updated = append(pdf, "周报.pdf")

        assertEquals("周报.pdf", infoTitle(updated))
        assertEquals("周报.pdf", xmpTitle(updated))
    }

    @Test
    fun `different info title is replaced`() {
        val pdf = pdfWithTitles(info = "草稿", xmp = null)

        val updated = append(pdf, "周报.pdf")

        assertEquals("周报.pdf", infoTitle(updated))
        assertEquals("周报.pdf", xmpTitle(updated))
    }

    @Test
    fun `encrypted pdf is left unchanged`() {
        val pdf = encryptedPdf()

        val suffix = PdfPreviewTitle.incrementalUpdate(RandomAccessBuffer(pdf), "周报.pdf")

        assertNull(suffix)
    }

    @Test
    fun `corrupt bytes are left unchanged`() {
        val suffix = PdfPreviewTitle.incrementalUpdate(RandomAccessBuffer("not a pdf".toByteArray()), "周报.pdf")

        assertNull(suffix)
    }

    @Test
    fun `title suffix stays small for a large pdf`() {
        val pdf = pdfWithLargeStream()
        val suffix = PdfPreviewTitle.incrementalUpdate(RandomAccessBuffer(pdf), "周报.pdf")

        assertTrue(pdf.size > STREAM_SIZE)
        assertTrue(suffix != null && suffix.size < pdf.size / 2)
        assertEquals("周报.pdf", infoTitle(pdf + suffix!!))
    }

    @Test
    fun `ranged rewrite reads the trailer but not the page body`() {
        val pdf = pdfWithLargeStream(BODY_SIZE)
        var bytesRead = 0L
        val source = PdfRangedRandomAccess(pdf.size.toLong()) { offset, length ->
            bytesRead += length
            val start = offset.toInt()
            pdf.copyOfRange(start, start + length)
        }

        val suffix = PdfPreviewTitle.incrementalUpdate(source, "周报.pdf")
        check(suffix != null && suffix.isNotEmpty()) {
            "suffix=${suffix?.size} bytesRead=$bytesRead pdf=${pdf.size}"
        }
        assertTrue(bytesRead < BODY_SIZE / 2) { "bytesRead=$bytesRead pdf=${pdf.size}" }
        assertEquals("周报.pdf", infoTitle(pdf + suffix))
    }
}

private fun append(pdf: ByteArray, title: String): ByteArray {
    val suffix = PdfPreviewTitle.incrementalUpdate(RandomAccessBuffer(pdf), title)
    assertTrue(suffix != null && suffix.isNotEmpty())
    return pdf + suffix!!
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

private fun pdfWithLargeStream(streamSize: Int = STREAM_SIZE): ByteArray {
    val payload = Random(1).nextBytes(streamSize)
    PDDocument().use { document ->
        val page = PDPage()
        document.addPage(page)
        document.documentInformation.title = "草稿"
        val stream = PDStream(document, java.io.ByteArrayInputStream(payload))
        page.setContents(stream)
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

private const val STREAM_SIZE = 400_000
private const val BODY_SIZE = 2_000_000
