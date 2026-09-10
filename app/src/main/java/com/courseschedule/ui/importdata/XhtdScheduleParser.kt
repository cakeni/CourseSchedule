package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup

/** Parses XHTD blocks whose div id stores the half-day row and weekday. */
internal class XhtdScheduleParser(private val totalWeeks: Int) {
    fun parse(html: String): ParsedImport {
        val root = Jsoup.parse(html).getElementById("kbtable")
            ?: throw ImportFormatException("未找到协和天地课表", AcademicImportErrorCode.NO_TIMETABLE)
        val courses = mutableListOf<Course>()
        var unreadable = 0
        root.select("div[id]").take(240).forEach { block ->
            if (block.selectFirst("nobr") == null) return@forEach
            val coordinate = Regex("^(\\d{1,2})-(\\d)$").matchEntire(block.id()) ?: return@forEach
            val halfDayRow = coordinate.groupValues[1].toInt()
            val day = coordinate.groupValues[2].toInt()
            if (halfDayRow !in 1..15 || day !in 1..7) return@forEach
            val lines = block.html().split(Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE))
                .map { Jsoup.parseBodyFragment(it).text().trim() }
            for (index in lines.indices step 5) {
                val fields = lines.drop(index).take(5)
                if (fields.all(String::isBlank)) continue
                if (fields.size < 5 || fields[0].isBlank() || fields[3].isBlank()) {
                    unreadable++
                    continue
                }
                runCatching {
                    val weeks = QiangzhiScheduleParser(totalWeeks).parseWeeks(fields[3])
                    compressImportWeeks(weeks).map { range ->
                        Course(
                            courseName = fields[0],
                            teacher = fields[2],
                            classroom = fields[4],
                            dayOfWeek = day,
                            startSection = (halfDayRow - 1) * 2 + 1,
                            endSection = (halfDayRow - 1) * 2 + 2,
                            startWeek = range.start,
                            endWeek = range.end,
                            weekType = range.weekType,
                            colorIndex = Math.floorMod(fields[0].hashCode(), 16)
                        )
                    }
                }.onSuccess(courses::addAll).onFailure { unreadable++ }
            }
        }
        if (unreadable > 0) throw ImportFormatException(
            "有 $unreadable 个协和天地课程块字段不完整，本次未导入",
            AcademicImportErrorCode.PARTIAL_PARSE
        )
        if (courses.isEmpty()) throw ImportFormatException(
            "协和天地课表页面没有课程",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        return ParsedImport(courses.distinct(), sourceLabel = "协和天地教务 · HTML")
    }
}
