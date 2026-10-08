package com.tencent.bkrepo.preview.service

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.io.RandomAccessRead
import org.apache.pdfbox.io.ScratchFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdfparser.PDFParser
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.DomXmpParser
import org.apache.xmpbox.xml.XmpSerializer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * 生成追加在原 PDF 末尾的增量更新。只改 Info / XMP 标题，正文仍留在原文件里。
 * 加密、标题已一致或无法解析时返回 null，调用方按原文件 Range 直出。
 */
object PdfPreviewTitle {

    private val logger = LoggerFactory.getLogger(PdfPreviewTitle::class.java)

    fun incrementalUpdate(source: RandomAccessRead, title: String): ByteArray? {
        val scratch = ScratchFile(MemoryUsageSetting.setupTempFileOnly())
        try {
            val parser = InfoOnlyParser(source, scratch)
            parser.parse()
            return parser.pdDocument.use { document ->
                suffixAfter(document, source, title)
            }
        } catch (exception: Exception) {
            logger.warn("pdf preview title rewrite failed", exception)
            return null
        } finally {
            scratch.close()
        }
    }

    private fun suffixAfter(document: PDDocument, source: RandomAccessRead, title: String): ByteArray? {
        if (document.isEncrypted) {
            return null
        }
        val current = document.documentInformation.title?.trim()?.takeIf { it.isNotEmpty() }
        if (current == title) {
            return null
        }
        document.documentInformation.title = title
        document.documentInformation.cosObject.setNeedToBeUpdated(true)
        writeXmpTitle(document, title)
        document.documentCatalog.cosObject.setNeedToBeUpdated(true)
        document.document.trailer.setNeedToBeUpdated(true)
        // 原文拷贝会被 SuffixCapture 丢掉，偏移只看 length()。Range 源不再回存储。
        (source as? PdfRangedRandomAccess)?.skipSourceReads()
        val capture = SuffixCapture(source.length())
        document.saveIncremental(capture)
        return capture.suffix().takeIf { it.isNotEmpty() }
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
            metadata.cosObject.setNeedToBeUpdated(true)
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

/**
 * 只解析 trailer、目录和 Info。目录里的间接对象只登记自身，不读页内容或附件。
 */
private class InfoOnlyParser(
    source: RandomAccessRead,
    scratch: ScratchFile,
) : PDFParser(source, scratch) {

    override fun parse() {
        var failed = true
        try {
            if (!parsePDFHeader() && !parseFDFHeader() && !isLenient) {
                throw IOException("Error: Header doesn't contain versioninfo")
            }
            val trailer = retrieveTrailer()
            val root = parseTrailerValuesDynamically(trailer)
            if (root !is COSDictionary) {
                throw IOException("Expected root dictionary, but got this: $root")
            }
            if (isLenient && !root.containsKey(COSName.TYPE)) {
                root.setItem(COSName.TYPE, COSName.CATALOG)
            }
            registerCatalogObjects(root)
            val info = trailer.getDictionaryObject(COSName.INFO)
            if (info is COSDictionary) {
                parseDictObjects(info)
            }
            document.setDecrypted()
            failed = false
        } finally {
            if (failed && document != null) {
                IOUtils.closeQuietly(document)
                document = null
            }
        }
    }

    /**
     * 目录中直接出现的间接对象各解析一次，增量写出时沿用原对象号。
     * 解析停在这一层，页内容流和附件流仍留在原文件里。
     */
    private fun registerCatalogObjects(catalog: COSDictionary) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        seen.add(catalog)
        for (value in catalog.values) {
            registerCatalogReference(value, seen)
        }
    }

    private fun registerCatalogReference(value: COSBase?, seen: MutableSet<COSBase>) {
        if (value == null || !seen.add(value)) {
            return
        }
        when (value) {
            is COSObject -> parseObjectDynamically(value, false)
            is COSArray -> {
                for (element in value) {
                    registerCatalogReference(element, seen)
                }
            }
            is COSDictionary -> {
                if (value.isDirect) {
                    for (nested in value.values) {
                        registerCatalogReference(nested, seen)
                    }
                }
            }
            else -> Unit
        }
    }
}

/**
 * saveIncremental 会先把原文件写进输出，再写标题补丁。这里丢掉前半段，只留下补丁。
 */
private class SuffixCapture(private val originalSize: Long) : OutputStream() {

    private var seen = 0L
    private val tail = ByteArrayOutputStream()

    fun suffix(): ByteArray = tail.toByteArray()

    override fun write(byte: Int) {
        if (seen >= originalSize) {
            tail.write(byte)
        }
        seen += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        var start = offset
        var remaining = length
        if (seen < originalSize) {
            val discard = min(remaining.toLong(), originalSize - seen).toInt()
            seen += discard
            start += discard
            remaining -= discard
        }
        if (remaining > 0) {
            tail.write(buffer, start, remaining)
            seen += remaining
        }
    }
}
