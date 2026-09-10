package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses the fixed two-sections-per-row print table used by SCAU. */
internal class ScauScheduleParser(private val totalWeeks: Int) {
    fun parse(html: String): ParsedImport {
        val document = Jsoup.parse(html)
        val table = document.select("table[border=1]").firstOrNull {
            it.attr("bordercolor").equals("#000000", ignoreCase = true)
        } ?: throw ImportFormatException(
            "未找到华农打印课表",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        val rows = table.select("tr").toList().filter { it.closest("table") === table }.drop(2)
        if (rows.size > 30) throw ImportFormatException(
            "华农课表行数异常",
            AcademicImportErrorCode.PAYLOAD_TOO_LARGE
        )
        val courses = mutableListOf<Course>()
        rows.forEachIndexed { rowIndex, row ->
            directCells(row).filter { it.attr("valign").equals("top", ignoreCase = true) }
                .take(7).forEachIndexed cellLoop@{ dayIndex, cell ->
                    val lines = cell.html().split(Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE))
                        .map { Jsoup.parseBodyFragment(it).text().trim() }
                        .filter(String::isNotBlank)
                    if (lines.size < 3) return@cellLoop
                    val titleAndTeacher = lines[0]
                    val rawName = titleAndTeacher.substringBefore('：').substringBefore(':').trim()
                    val nameParts = rawName.split(Regex("\\s+")).filter(String::isNotBlank)
                    val embeddedWeeks = nameParts.lastOrNull()?.takeIf {
                        it.matches(Regex("[\\d,，\\-~～—–－至]+"))
                    }
                    val name = if (embeddedWeeks == null) rawName else nameParts.dropLast(1).joinToString("")
                    if (name.isBlank()) throw ImportFormatException(
                        "华农课程名称缺失",
                        AcademicImportErrorCode.PARTIAL_PARSE
                    )
                    val teacher = when {
                        '：' in titleAndTeacher -> titleAndTeacher.substringAfter('：')
                        ':' in titleAndTeacher -> titleAndTeacher.substringAfter(':')
                        else -> ""
                    }.trim()
                    val room = lines[1]
                    val weeks = try {
                        QiangzhiScheduleParser(totalWeeks).parseWeeks(embeddedWeeks ?: lines[2])
                    } catch (error: ImportFormatException) {
                        throw ImportFormatException(
                            error.message ?: "华农课程周次无效",
                            AcademicImportErrorCode.MISSING_WEEK
                        )
                    }
                    compressImportWeeks(weeks).forEach { range ->
                        courses += Course(
                            courseName = name,
                            teacher = teacher,
                            classroom = room,
                            dayOfWeek = dayIndex + 1,
                            startSection = rowIndex * 2 + 1,
                            endSection = rowIndex * 2 + 2,
                            startWeek = range.start,
                            endWeek = range.end,
                            weekType = range.weekType,
                            colorIndex = Math.floorMod(name.hashCode(), 16)
                        )
                    }
                }
        }
        if (courses.isEmpty()) throw ImportFormatException(
            "华农课表页面没有课程",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        return ParsedImport(courses.distinct(), sourceLabel = "华农教务 · HTML")
    }

    private fun directCells(row: Element): List<Element> = row.children().toList().filter {
        it.tagName() == "td" || it.tagName() == "th"
    }
}
