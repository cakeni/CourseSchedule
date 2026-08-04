package com.courseschedule.ui.importdata

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

class FileImporter(
    private val context: Context,
    private val totalWeeks: Int
) {
    fun importFromUri(uri: Uri): ParsedImport {
        val fileName = getFileName(uri).lowercase()
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()
        if (fileName.endsWith(".xlsx") || "spreadsheetml" in mimeType) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportFormatException("无法读取所选文件")
            return input.use { XlsxImporter(totalWeeks).parse(it) }
        }
        if (fileName.endsWith(".xls")) {
            throw ImportFormatException("暂不支持旧版 .xls，请在 Excel 中另存为 .xlsx 后导入")
        }
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw ImportFormatException("无法读取所选文件")
        val parser = ImportParser(totalWeeks)
        return when {
            fileName.endsWith(".json") || "json" in mimeType -> parser.parseJson(text)
            fileName.endsWith(".csv") || "csv" in mimeType -> parser.parseCsv(text)
            fileName.endsWith(".html") || fileName.endsWith(".htm") || "html" in mimeType -> {
                parser.parseHtml(text)
            }
            else -> parser.parseText(text)
        }
    }

    private fun getFileName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }
}
