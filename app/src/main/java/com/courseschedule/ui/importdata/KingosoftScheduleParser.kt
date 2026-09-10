package com.courseschedule.ui.importdata

internal class KingosoftScheduleParser(
    totalWeeks: Int,
    private val selectedResults: Boolean
) {
    private val delegate = StrictTimetableTableParser(
        totalWeeks = totalWeeks,
        label = if (selectedResults) "青果正选结果" else "青果教学安排",
        selectors = if (selectedResults) {
            listOf("#kbDiv", "#mytable", ".pageRpt", "#reportArea")
        } else {
            listOf(".pageRpt", "#reportArea", "#mytable", "#kbDiv")
        },
        rejectImageOnly = true
    )

    fun parse(html: String): ParsedImport = delegate.parse(html)
}
