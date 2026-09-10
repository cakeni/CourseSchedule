package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses the fixed slash-delimited graduate timetable grid used by UESTC. */
internal class UestcPostScheduleParser(private val totalWeeks: Int) {
    fun parse(html: String): ParsedImport {
        val table = Jsoup.parse(html).getElementById("tbl")
            ?: throw ImportFormatException("未找到电子科大研究生课表", AcademicImportErrorCode.NO_TIMETABLE)
        val rows = table.select("tr").toList().filter { it.closest("table") === table }.drop(1)
        val courses = mutableListOf<Course>()
        var unreadable = 0
        rows.take(30).forEach { row ->
            directCells(row).drop(1).take(7).forEachIndexed { dayIndex, cell ->
                cell.text().split(Regex("，\\s+")).filter(String::isNotBlank).forEach blockLoop@{ value ->
                    val fields = value.split('/').map(String::trim)
                    if (fields.size < 8 || fields[1].isBlank()) {
                        unreadable++
                        return@blockLoop
                    }
                    runCatching {
                        val sections = Regex("(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})")
                            .find(fields[6]) ?: throw ImportFormatException("电子科大课程节次缺失")
                        val weeks = QiangzhiScheduleParser(totalWeeks).parseWeeks(fields[5])
                        compressImportWeeks(weeks).map { range ->
                            Course(
                                courseName = fields[1],
                                teacher = fields[4],
                                classroom = fields[7],
                                dayOfWeek = dayIndex + 1,
                                startSection = sections.groupValues[1].toInt(),
                                endSection = sections.groupValues[2].toInt(),
                                startWeek = range.start,
                                endWeek = range.end,
                                weekType = range.weekType,
                                colorIndex = Math.floorMod(fields[1].hashCode(), 16)
                            )
                        }
                    }.onSuccess(courses::addAll).onFailure { unreadable++ }
                }
            }
        }
        if (unreadable > 0) throw ImportFormatException(
            "有 $unreadable 个电子科大课程块字段不完整，本次未导入",
            AcademicImportErrorCode.PARTIAL_PARSE
        )
        if (courses.isEmpty()) throw ImportFormatException(
            "电子科大研究生课表页面没有课程",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        return ParsedImport(courses.distinct(), sourceLabel = "电子科大研究生教务 · HTML")
    }

    private fun directCells(row: Element): List<Element> = row.children().toList().filter {
        it.tagName() == "td" || it.tagName() == "th"
    }
}
