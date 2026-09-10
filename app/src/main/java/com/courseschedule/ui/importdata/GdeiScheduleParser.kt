package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses GDEI's nested course blocks inside a weekday table. */
internal class GdeiScheduleParser(private val totalWeeks: Int) {
    fun parse(html: String): ParsedImport {
        val document = Jsoup.parse(html)
        val body = document.select("tbody").firstOrNull { candidate ->
            candidate.select("tr").any { row ->
                directCells(row).firstOrNull()?.text()?.let(SECTION::containsMatchIn) == true
            }
        } ?: throw ImportFormatException("未找到广二师课表", AcademicImportErrorCode.NO_TIMETABLE)
        val courses = mutableListOf<Course>()
        var unreadable = 0
        body.select("tr").take(30).forEach { row ->
            val cells = directCells(row)
            val fallback = cells.firstOrNull()?.text()?.let(::parseSections) ?: return@forEach
            cells.drop(1).take(7).forEachIndexed { dayIndex, cell ->
                cell.children().forEach blockLoop@{ wrapper ->
                    val fields = wrapper.children().toList()
                    if (fields.isEmpty() || fields.all { it.text().isBlank() }) return@blockLoop
                    if (fields.size < 5) {
                        unreadable++
                        return@blockLoop
                    }
                    val name = fields[0].text().trim()
                    val spans = fields[1].select("span").map(Element::text)
                    val weeksText = spans.firstOrNull().orEmpty()
                    val sections = spans.getOrNull(1)?.let(::parseSections) ?: fallback
                    if (name.isBlank() || weeksText.isBlank()) {
                        unreadable++
                        return@blockLoop
                    }
                    runCatching {
                        val weeks = QiangzhiScheduleParser(totalWeeks).parseWeeks(weeksText.replace(';', ','))
                        compressImportWeeks(weeks).map { range ->
                            Course(
                                courseName = name,
                                teacher = fields[4].text().trim(),
                                classroom = fields[3].text().substringBefore('(').trim(),
                                dayOfWeek = dayIndex + 1,
                                startSection = sections.first,
                                endSection = sections.last,
                                startWeek = range.start,
                                endWeek = range.end,
                                weekType = range.weekType,
                                colorIndex = Math.floorMod(name.hashCode(), 16)
                            )
                        }
                    }.onSuccess(courses::addAll).onFailure { unreadable++ }
                }
            }
        }
        if (unreadable > 0) throw ImportFormatException(
            "有 $unreadable 个广二师课程块字段不完整，本次未导入",
            AcademicImportErrorCode.PARTIAL_PARSE
        )
        if (courses.isEmpty()) throw ImportFormatException(
            "广二师课表页面没有课程",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        return ParsedImport(courses.distinct(), sourceLabel = "广二师教务 · HTML")
    }

    private fun parseSections(value: String): IntRange? {
        val match = SECTION.find(value) ?: return null
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toIntOrNull() ?: start
        return (start..end).takeIf { start in 1..30 && end in start..30 }
    }

    private fun directCells(row: Element): List<Element> = row.children().toList().filter {
        it.tagName() == "td" || it.tagName() == "th"
    }

    companion object {
        private val SECTION = Regex("第?\\s*(\\d{1,2})(?:\\s*[-,，~～—–－至]\\s*(\\d{1,2}))?\\s*节")
    }
}
