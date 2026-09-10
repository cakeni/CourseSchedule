package com.courseschedule.ui.importdata

internal class SouthSoftScheduleParser(totalWeeks: Int) {
    private val delegate = StrictTimetableTableParser(
        totalWeeks = totalWeeks,
        label = "南软教务",
        selectors = listOf("#kb", "table.kb", ".kb")
    )

    fun parse(html: String): ParsedImport = delegate.parse(html)
}
