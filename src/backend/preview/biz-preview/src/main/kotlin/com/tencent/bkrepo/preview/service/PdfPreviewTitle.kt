package com.tencent.bkrepo.preview.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.DomXmpParser
import org.apache.xmpbox.xml.XmpSerializer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

/**
 * 改写 PDF Info / XMP 标题。加密或无法解析时返回原字节。
 */
object PdfPreviewTitle {

    private val logger = LoggerFactory.getLogger(PdfPreviewTitle::class.java)

    fun rewrite(pdf: ByteArray, title: String): ByteArray {
        return try {
            PDDocument.load(pdf).use { document ->
                if (document.isEncrypted) {
                    return pdf
                }
                val current = document.documentInformation.title?.trim()?.takeIf { it.isNotEmpty() }
                if (current == title) {
                    return pdf
                }
                document.documentInformation.title = title
                writeXmpTitle(document, title)
                val output = ByteArrayOutputStream()
                document.save(output)
                output.toByteArray()
            }
        } catch (exception: Exception) {
            logger.warn("pdf preview title rewrite failed", exception)
            pdf
        }
    }

    private fun writeXmpTitle(document: PDDocument, title: String) {
        try {
            val catalog = document.documentCatalog
            val xmp = catalog.metadata?.let { parseXmp(it) } ?: XMPMetadata.createXMPMetadata()
            val dublinCore = xmp.dublinCoreSchema ?: xmp.createAndAddDublinCoreSchema()
            dublinCore.setTitle(title)
            val serialized = ByteArrayOutputStream()
            XmpSerializer().serialize(xmp, serialized, true)
            val metadata = PDMetadata(document)
            metadata.importXMPMetadata(serialized.toByteArray())
            catalog.metadata = metadata
        } catch (exception: Exception) {
            logger.info("pdf preview xmp title skipped: ${exception.message}")
        }
    }

    private fun parseXmp(metadata: PDMetadata): XMPMetadata {
        return try {
            DomXmpParser().parse(metadata.createInputStream())
        } catch (_: Exception) {
            XMPMetadata.createXMPMetadata()
        }
    }
}
