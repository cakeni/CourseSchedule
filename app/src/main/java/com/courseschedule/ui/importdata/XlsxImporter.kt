package com.courseschedule.ui.importdata

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

class XlsxImporter(private val totalWeeks: Int) {

    fun parse(input: InputStream): ParsedImport {
        val sheets = runCatching { XlsxReader.readSheets(input) }
            .getOrElse { error ->
                if (error is ImportFormatException) throw error
                throw ImportFormatException("Excel 文件已损坏或格式不正确")
            }
        val parser = ImportParser(totalWeeks)
        val courses = sheets.flatMap { rows ->
            parser.parseTabularRows(rows, SOURCE_LABEL).courses
        }.map { course ->
            course.copy(colorIndex = Math.floorMod(course.courseName.hashCode(), 15) + 1)
        }
        if (courses.isEmpty()) {
            throw ImportFormatException("Excel 中未找到包含课程名称、星期和节次的课程表")
        }
        return ParsedImport(courses = courses, sourceLabel = SOURCE_LABEL)
    }

    private companion object {
        const val SOURCE_LABEL = "Excel"
    }
}

private object XlsxReader {
    private const val MAX_ENTRY_SIZE = 16 * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 48 * 1024 * 1024
    private const val MAX_SHEETS = 32
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 256

    fun readSheets(input: InputStream): List<List<List<String>>> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').removePrefix("/")
                    val relevant = name == "xl/sharedStrings.xml" ||
                        (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
                    if (relevant) {
                        val bytes = readLimited(zip, MAX_ENTRY_SIZE)
                        totalSize += bytes.size
                        if (totalSize > MAX_TOTAL_SIZE) {
                            throw ImportFormatException("Excel 文件内容过大")
                        }
                        entries[name] = bytes
                    }
                    zip.closeEntry()
                }
            }
        } catch (_: ZipException) {
            throw ImportFormatException("所选文件不是有效的 .xlsx 文件")
        }

        val sheetEntries = entries.entries
            .filter { (name, _) -> name.startsWith("xl/worksheets/") }
            .sortedBy { (name, _) -> sheetNumber(name) }
            .take(MAX_SHEETS)
        if (sheetEntries.isEmpty()) throw ImportFormatException("Excel 文件中没有工作表")

        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let(::parseSharedStrings)
            .orEmpty()
        return sheetEntries.map { (_, bytes) -> parseSheet(bytes, sharedStrings) }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw ImportFormatException("Excel 工作表内容过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        parseXml(bytes, object : DefaultHandler() {
            private var insideString = false
            private var insideText = false
            private var text = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (elementName(localName, qName)) {
                    "si" -> {
                        insideString = true
                        text = StringBuilder()
                    }
                    "t" -> if (insideString) insideText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (insideText) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementName(localName, qName)) {
                    "t" -> insideText = false
                    "si" -> {
                        strings += text.toString()
                        insideString = false
                    }
                }
            }
        })
        return strings
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        parseXml(bytes, object : DefaultHandler() {
            private var cells = sortedMapOf<Int, String>()
            private var cellType = ""
            private var cellColumn = 0
            private var nextColumn = 0
            private var captureValue = false
            private var captureInlineText = false
            private var value = StringBuilder()
            private var inlineText = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (elementName(localName, qName)) {
                    "row" -> {
                        cells = sortedMapOf()
                        nextColumn = 0
                    }
                    "c" -> {
                        cellType = attributes?.getValue("t").orEmpty()
                        cellColumn = attributes?.getValue("r")
                            ?.let(::columnIndex)
                            ?.takeIf { it in 0 until MAX_COLUMNS }
                            ?: nextColumn
                        value = StringBuilder()
                        inlineText = StringBuilder()
                    }
                    "v" -> captureValue = true
                    "t" -> if (cellType == "inlineStr") captureInlineText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (captureValue) value.append(ch, start, length)
                if (captureInlineText) inlineText.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementName(localName, qName)) {
                    "v" -> captureValue = false
                    "t" -> captureInlineText = false
                    "c" -> {
                        val resolved = resolveCellValue(
                            cellType,
                            value.toString(),
                            inlineText.toString(),
                            sharedStrings
                        )
                        if (resolved.isNotBlank() && cellColumn in 0 until MAX_COLUMNS) {
                            cells[cellColumn] = resolved
                        }
                        nextColumn = (cellColumn + 1).coerceAtMost(MAX_COLUMNS)
                    }
                    "row" -> if (rows.size < MAX_ROWS && cells.isNotEmpty()) {
                        val width = (cells.lastKey() + 1).coerceAtMost(MAX_COLUMNS)
                        rows += List(width) { column -> cells[column].orEmpty() }
                    }
                }
            }
        })
        return rows
    }

    private fun resolveCellValue(
        type: String,
        rawValue: String,
        inlineText: String,
        sharedStrings: List<String>
    ): String {
        val raw = if (type == "inlineStr") inlineText else rawValue
        return when (type) {
            "s" -> raw.trim().toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            else -> normalizeNumber(raw.trim())
        }
    }

    private fun normalizeNumber(value: String): String =
        if (value.matches(Regex("-?\\d+\\.0+"))) value.substringBefore('.') else value

    private fun columnIndex(reference: String): Int? {
        val letters = reference.takeWhile(Char::isLetter)
        if (letters.isEmpty()) return null
        var result = 0
        letters.uppercase().forEach { char ->
            result = result * 26 + (char - 'A' + 1)
        }
        return result - 1
    }

    private fun sheetNumber(name: String): Int = Regex("sheet(\\d+)\\.xml$")
        .find(name)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: Int.MAX_VALUE

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    private fun elementName(localName: String?, qName: String?): String =
        localName?.takeIf(String::isNotBlank) ?: qName.orEmpty().substringAfter(':')
}
