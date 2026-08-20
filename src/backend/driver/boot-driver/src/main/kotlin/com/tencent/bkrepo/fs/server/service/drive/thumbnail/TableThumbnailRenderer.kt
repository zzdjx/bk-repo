package com.tencent.bkrepo.fs.server.service.drive.thumbnail

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.SharedStrings
import org.apache.poi.xssf.model.StylesTable
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 表格缩略图：Excel / CSV 读首屏网格，再画成 JPEG 源图。
 * xlsx 用 SAX 只解析前 [MAX_PREVIEW_ROWS] 行，避免整本 XSSFWorkbook 载入超时。
 */
internal object TableThumbnailRenderer {

    fun render(input: Path, maxEdge: Int = DEFAULT_TABLE_MAX_EDGE): BufferedImage? {
        val grid = when (fileMagic(input)) {
            FileMagic.OOXML -> readXlsxGrid(input)
            FileMagic.OLE2 -> readOleWorkbookGrid(input)
            else -> readCsvGrid(input)
        }
        if (grid.isEmpty() || grid.all { row -> row.all { it.isEmpty() } }) {
            return null
        }
        return drawTable(grid, maxEdge.coerceAtLeast(1))
    }

    private fun fileMagic(input: Path): FileMagic {
        return Files.newInputStream(input).use { raw ->
            FileMagic.valueOf(FileMagic.prepareToCheckMagic(raw))
        }
    }

    private fun readXlsxGrid(input: Path): List<List<String>> {
        OPCPackage.open(input.toFile(), PackageAccess.READ).use { pkg ->
            val reader = XSSFReader(pkg).apply { setUseReadOnlySharedStringsTable(true) }
            val strings = reader.sharedStringsTable ?: ReadOnlySharedStringsTable(pkg)
            val sheets = reader.sheetsData
            if (!sheets.hasNext()) {
                return emptyList()
            }
            sheets.next().use { sheetStream ->
                return parseXlsxSheetPreview(reader.stylesTable, strings, sheetStream)
            }
        }
    }

    private fun parseXlsxSheetPreview(
        styles: StylesTable?,
        strings: SharedStrings,
        sheetStream: InputStream,
    ): List<List<String>> {
        val handler = PreviewSheetHandler()
        val parser = XMLHelper.newXMLReader()
        parser.contentHandler = XSSFSheetXMLHandler(styles, strings, handler, false)
        try {
            parser.parse(InputSource(sheetStream))
        } catch (e: PreviewLimitReachedException) {
            return handler.rows
        } catch (e: SAXException) {
            if (generateSequence<Throwable>(e) { it.cause }.none { it is PreviewLimitReachedException }) {
                throw e
            }
        }
        return handler.rows
    }

    private fun readOleWorkbookGrid(input: Path): List<List<String>> {
        WorkbookFactory.create(input.toFile(), null, true).use { workbook ->
            if (workbook.numberOfSheets < 1) {
                return emptyList()
            }
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter()
            val lastRow = min(sheet.lastRowNum, sheet.firstRowNum + MAX_PREVIEW_ROWS - 1)
            if (sheet.physicalNumberOfRows <= 0 || lastRow < sheet.firstRowNum) {
                return emptyList()
            }
            return (sheet.firstRowNum..lastRow).map { rowIndex ->
                val row = sheet.getRow(rowIndex) ?: return@map emptyList()
                val cellEnd = min(row.lastCellNum.toInt(), MAX_PREVIEW_COLS).coerceAtLeast(0)
                (0 until cellEnd).map { col ->
                    row.getCell(col)?.let { previewCellText(formatter.formatCellValue(it)) } ?: ""
                }
            }
        }
    }

    private fun readCsvGrid(input: Path): List<List<String>> {
        return Files.newInputStream(input).use { raw ->
            val stream = BufferedInputStream(raw)
            ThumbnailPreviewText.skipUtf8Bom(stream)
            InputStreamReader(stream, StandardCharsets.UTF_8).buffered().use { reader ->
                reader.lineSequence()
                    .dropWhile { it.isBlank() }
                    .take(MAX_PREVIEW_ROWS)
                    .map { parseCsvLine(it) }
                    .toList()
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == CSV_QUOTE && inQuotes && line.getOrNull(i + 1) == CSV_QUOTE -> {
                    current.appendCapped(CSV_QUOTE)
                    i++
                }
                ch == CSV_QUOTE -> inQuotes = !inQuotes
                ch == CSV_DELIMITER && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                    if (result.size >= MAX_PREVIEW_COLS) {
                        return result
                    }
                }
                else -> current.appendCapped(ch)
            }
            i++
        }
        result.add(current.toString().trim())
        return result
    }

    private fun StringBuilder.appendCapped(ch: Char) {
        if (length < MAX_CELL_CHARS) {
            append(ch)
        }
    }

    private fun drawTable(grid: List<List<String>>, maxEdge: Int): BufferedImage {
        val colCount = grid.maxOf { it.size }.coerceIn(1, MAX_PREVIEW_COLS)
        val rowCount = grid.size.coerceAtMost(MAX_PREVIEW_ROWS)
        val layout = TableLayout.from(colCount, rowCount, maxEdge)
        val width = colCount * layout.colWidth + layout.stroke.toInt()
        val height = rowCount * layout.rowHeight + layout.stroke.toInt()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        ThumbnailGraphics.use(image) { graphics ->
            ThumbnailGraphics.fillWhite(graphics, width, height)
            ThumbnailGraphics.applyQualityHints(graphics, text = true)
            graphics.stroke = BasicStroke(layout.stroke)
            graphics.font = TABLE_FONT.deriveFont(layout.fontSize)
            drawGrid(graphics, grid, layout, rowCount, colCount)
        }
        return image
    }

    private fun drawGrid(
        graphics: Graphics2D,
        grid: List<List<String>>,
        layout: TableLayout,
        rowCount: Int,
        colCount: Int,
    ) {
        val metrics = graphics.fontMetrics
        for (row in 0 until rowCount) {
            val cells = grid[row]
            for (col in 0 until colCount) {
                drawCell(graphics, metrics, layout, cells.getOrNull(col).orEmpty(), row, col)
            }
        }
    }

    private fun drawCell(
        graphics: Graphics2D,
        metrics: FontMetrics,
        layout: TableLayout,
        text: String,
        row: Int,
        col: Int,
    ) {
        val x = col * layout.colWidth
        val y = row * layout.rowHeight
        if (row == 0) {
            graphics.color = TABLE_HEADER_BG
            graphics.fillRect(x, y, layout.colWidth, layout.rowHeight)
        }
        graphics.color = TABLE_GRID
        graphics.drawRect(x, y, layout.colWidth, layout.rowHeight)
        val maxTextWidth = layout.colWidth - layout.padding * 2
        if (text.isEmpty() || maxTextWidth <= 0) {
            return
        }
        val baseline = y + (layout.rowHeight + metrics.ascent - metrics.descent) / 2
        graphics.color = TABLE_TEXT
        graphics.drawString(clipText(text, metrics, maxTextWidth), x + layout.padding, baseline)
    }

    private fun clipText(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text
        }
        val ellipsisWidth = metrics.stringWidth(ELLIPSIS)
        if (ellipsisWidth >= maxWidth) {
            return ""
        }
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) {
            end--
        }
        return text.substring(0, end) + ELLIPSIS
    }

    private class PreviewSheetHandler : XSSFSheetXMLHandler.SheetContentsHandler {
        val rows = mutableListOf<MutableList<String>>()
        private var firstRowNum: Int? = null
        private var currentRow: MutableList<String>? = null

        override fun startRow(rowNum: Int) {
            val first = firstRowNum ?: rowNum.also { firstRowNum = it }
            if (rowNum >= first + MAX_PREVIEW_ROWS) {
                throw PreviewLimitReachedException()
            }
            while (rows.size < rowNum - first) {
                rows.add(mutableListOf())
            }
            currentRow = mutableListOf()
        }

        override fun endRow(rowNum: Int) {
            currentRow?.let { rows.add(it) }
            currentRow = null
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
            val row = currentRow ?: return
            val col = cellReference?.let { CellReference(it).col.toInt() } ?: return
            if (col !in 0 until MAX_PREVIEW_COLS) {
                return
            }
            while (row.size <= col) {
                row.add("")
            }
            row[col] = previewCellText(formattedValue.orEmpty())
        }
    }

    private fun previewCellText(value: String): String {
        return value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_CELL_CHARS)
    }

    private class PreviewLimitReachedException : RuntimeException()

    private data class TableLayout(
        val colWidth: Int,
        val rowHeight: Int,
        val padding: Int,
        val fontSize: Float,
        val stroke: Float,
    ) {
        companion object {
            fun from(colCount: Int, rowCount: Int, maxEdge: Int): TableLayout {
                val design = max(colCount * TABLE_COL_WIDTH, rowCount * TABLE_ROW_HEIGHT).coerceAtLeast(1)
                val scale = max(1f, maxEdge.toFloat() / design)
                return TableLayout(
                    colWidth = (TABLE_COL_WIDTH * scale).roundToInt().coerceAtLeast(1),
                    rowHeight = (TABLE_ROW_HEIGHT * scale).roundToInt().coerceAtLeast(1),
                    padding = (TABLE_CELL_PADDING * scale).roundToInt().coerceAtLeast(1),
                    fontSize = TABLE_FONT_SIZE * scale,
                    stroke = scale,
                )
            }
        }
    }

    private const val DEFAULT_TABLE_MAX_EDGE = 1920
    private const val MAX_PREVIEW_ROWS = 12
    private const val MAX_PREVIEW_COLS = 8
    private const val MAX_CELL_CHARS = 64
    private const val TABLE_COL_WIDTH = 72
    private const val TABLE_ROW_HEIGHT = 22
    private const val TABLE_CELL_PADDING = 4
    private const val TABLE_FONT_SIZE = 11
    private const val CSV_DELIMITER = ','
    private const val CSV_QUOTE = '"'
    private const val ELLIPSIS = "..."
    private val TABLE_HEADER_BG = Color(0xF2F2F2)
    private val TABLE_GRID = Color(0xD0D0D0)
    private val TABLE_TEXT = Color(0x333333)
    private val TABLE_FONT = Font(Font.SANS_SERIF, Font.PLAIN, TABLE_FONT_SIZE)
}
