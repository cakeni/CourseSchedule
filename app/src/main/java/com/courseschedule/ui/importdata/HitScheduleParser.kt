package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses the nine-column print grid used by HIT-family pages. */
internal class HitScheduleParser(private val totalWeeks: Int) {
    fun parse(html: String): ParsedImport {
        val document = Jsoup.parse(html)
        val roots = document.select(".xfyq_con,#xszp").toList()
        if (roots.isEmpty()) throw ImportFormatException(
            "未找到哈工大课表",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        val courses = mutableListOf<Course>()
        var unreadable = 0
        roots.flatMap { it.select("tr").toList() }.take(240).forEach rowLoop@{ row ->
            val cells = directCells(row)
            if (cells.size !in 8..9) return@rowLoop
            val sectionColumn = if (cells.size == 8) 0 else 1
            val sections = parseSections(cells[sectionColumn].text()) ?: return@rowLoop
            val dayStart = sectionColumn + 1
            (1..7).forEach dayLoop@{ day ->
                val cell = cells.getOrNull(dayStart + day - 1) ?: return@dayLoop
                val lines = cell.html().split(Regex("<br\\b[^>]*>|◇", RegexOption.IGNORE_CASE))
                    .map { Jsoup.parseBodyFragment(it).text().trim() }
                    .filter(String::isNotBlank)
                val markers = lines.indices.filter { WEEK.containsMatchIn(normalize(lines[it])) }
                var lastName = ""
                markers.forEachIndexed { markerPosition, markerIndex ->
                    val previous = lines.getOrNull(markerIndex - 1).orEmpty()
                    val name = if (WEEK.containsMatchIn(normalize(previous))) lastName else previous
                    if (name.isBlank()) {
                        unreadable++
                        return@forEachIndexed
                    }
                    val nextMarker = markers.getOrNull(markerPosition + 1)
                    val groupEnd = nextMarker?.let { (it - 1).coerceAtLeast(markerIndex + 1) } ?: lines.size
                    val details = lines.subList(markerIndex, groupEnd).joinToString(" ")
                    runCatching { parseMeeting(name, details, day, sections) }
                        .onSuccess(courses::addAll)
                        .onFailure { unreadable++ }
                    lastName = name
                }
            }
        }
        if (unreadable > 0) throw ImportFormatException(
            "有 $unreadable 个哈工大课程块字段不完整，本次未导入",
            AcademicImportErrorCode.PARTIAL_PARSE
        )
        if (courses.isEmpty()) throw ImportFormatException(
            "哈工大课表页面没有课程",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        return ParsedImport(courses.distinct(), sourceLabel = "哈工大教务 · HTML")
    }

    private fun parseMeeting(
        name: String,
        details: String,
        day: Int,
        fallbackSections: IntRange
    ): List<Course> {
        val clean = normalize(details)
        val weeks = QiangzhiScheduleParser(totalWeeks).parseWeeks(
            WEEK.findAll(clean).joinToString(",") { it.value }
        )
        val sections = SECTION.find(clean)?.let { match ->
            val start = match.groupValues[1].toInt()
            start..(match.groupValues[2].toIntOrNull() ?: start)
        } ?: fallbackSections
        if (sections.first !in 1..30 || sections.last !in sections.first..30) {
            throw ImportFormatException("哈工大课程节次无效", AcademicImportErrorCode.MISSING_SECTION)
        }
        val teacher = labelValue(clean, "(?:任课|授课)?(?:教师|老师)")
        val room = labelValue(clean, "(?:上课)?(?:地点|教室)")
        return compressImportWeeks(weeks).map { range ->
            Course(
                courseName = name,
                teacher = teacher,
                classroom = room,
                dayOfWeek = day,
                startSection = sections.first,
                endSection = sections.last,
                startWeek = range.start,
                endWeek = range.end,
                weekType = range.weekType,
                colorIndex = Math.floorMod(name.hashCode(), 16)
            )
        }
    }

    private fun parseSections(value: String): IntRange? {
        val values = Regex("\\d{1,2}").findAll(value).map { it.value.toInt() }.toList()
        val first = values.firstOrNull() ?: return null
        val last = values.lastOrNull() ?: first
        return (first..last).takeIf { first in 1..30 && last in first..30 }
    }

    private fun labelValue(text: String, label: String): String =
        Regex("(?:$label)\\s*[:：]\\s*(.+?)(?=\\s*(?:教师|老师|地点|教室)\\s*[:：]|[,，;；\\[\\]]|$)")
            .find(text)
            ?.groupValues?.get(1)?.trim().orEmpty()

    private fun normalize(value: String): String = value.replace('【', '[').replace('】', ']')
        .replace('（', '(').replace('）', ')').replace('，', ',').replace('、', ',')
        .replace(Regex("[~～—–－至]"), "-")

    private fun directCells(row: Element): List<Element> = row.children().toList().filter {
        it.tagName() == "td" || it.tagName() == "th"
    }

    companion object {
        private val WEEK = Regex("\\d{1,2}(?:\\s*[-,]\\s*\\d{1,2})*\\s*周(?:\\s*\\(?[单双]\\)?)?")
        private val SECTION = Regex("(?:第|\\[)?\\s*(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?\\s*节\\s*]?")
    }
}
