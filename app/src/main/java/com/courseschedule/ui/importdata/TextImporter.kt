package com.courseschedule.ui.importdata

class TextImporter(totalWeeks: Int) {
    private val parser = ImportParser(totalWeeks)

    fun parse(text: String): ParsedImport = parser.parseText(text)
}
