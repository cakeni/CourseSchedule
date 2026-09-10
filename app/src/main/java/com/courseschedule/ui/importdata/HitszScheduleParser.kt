package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses the card grid used by the HIT Shenzhen timetable page. */
internal class HitszScheduleParser(private val totalWeeks: Int) {
    fun parse(html: String): ParsedImport {
        val body = Jsoup.parse(html).selectFirst(".ivu-table-tbody")
            ?: throw ImportFormatException("未找到哈工深课表", AcademicImportErrorCode.NO_TIMETABLE)
        val rows = body.select("tr").toList().filter { it.closest(".ivu-table-tbody") === body }
        if (rows.size > 30) throw ImportFormatException(
            "哈工深课表行数异常",
            AcademicImportErrorCode.PAYLOAD_TOO_LARGE
        )
        val courses = mutableListOf<Course>()
        var unreadable = 0
        rows.forEach { row ->
            directCells(row).forEachIndexed { columnIndex, cell ->
                if (columnIndex !in 1..7) return@forEachIndexed
                val cards = cell.select(".ivu-card-body").toList().filter { card ->
                    card.select(".ivu-card-body").none { nested -> nested !== card }
                }
                cards.forEach { card ->
                    runCatching { parseCard(card, columnIndex) }
                        .onSuccess(courses::addAll)
                        .onFailure { unreadable++ }
                }
            }
        }
        if (unreadable > 0) throw ImportFormatException(
            "有 $unreadable 个哈工深课程卡片字段不完整，本次未导入",
            AcademicImportErrorCode.PARTIAL_PARSE
        )
        if (courses.isEmpty()) throw ImportFormatException(
            "哈工深课表页面没有课程",
            AcademicImportErrorCode.NO_TIMETABLE
        )
        return ParsedImport(courses.distinct(), sourceLabel = "哈工深教务 · HTML")
    }

    private fun parseCard(card: Element, day: Int): List<Course> {
        val text = normalize(card.text())
        val name = text.split(Regex("\\s+")).firstOrNull().orEmpty()
        if (name.isBlank()) throw ImportFormatException("哈工深课程名称缺失")
        val section = SECTION.find(text)
            ?: throw ImportFormatException("哈工深课程节次缺失", AcademicImportErrorCode.MISSING_SECTION)
        val startSection = section.groupValues[1].toInt()
        val endSection = section.groupValues[2].toIntOrNull() ?: startSection
        if (startSection !in 1..30 || endSection !in startSection..30) {
            throw ImportFormatException("哈工深课程节次无效", AcademicImportErrorCode.MISSING_SECTION)
        }
        val weekText = WEEK.findAll(text).joinToString(",") { it.value }
        val weeks = try {
            QiangzhiScheduleParser(totalWeeks).parseWeeks(weekText)
        } catch (error: ImportFormatException) {
            throw ImportFormatException(
                error.message ?: "哈工深课程周次无效",
                AcademicImportErrorCode.MISSING_WEEK
            )
        }
        val details = BRACKET.findAll(text).map { it.groupValues[1].trim() }
            .filter { value ->
                value.isNotBlank() && value != "实验" && !WEEK.containsMatchIn(value) &&
                    !SECTION.containsMatchIn(value)
            }.toList()
        val teacher = if (name.startsWith("[实验]")) "" else details.firstOrNull().orEmpty()
        val room = if (name.startsWith("[实验]")) details.lastOrNull().orEmpty()
            else details.drop(1).lastOrNull().orEmpty()
        return compressImportWeeks(weeks).map { range ->
            Course(
                courseName = name,
                teacher = teacher,
                classroom = room,
                dayOfWeek = day,
                startSection = startSection,
                endSection = endSection,
                startWeek = range.start,
                endWeek = range.end,
                weekType = range.weekType,
                colorIndex = Math.floorMod(name.hashCode(), 16)
            )
        }
    }

    private fun normalize(value: String): String = value.replace('【', '[').replace('】', ']')
        .replace('（', '(').replace('）', ')').replace('，', ',').replace('、', ',')
        .replace(Regex("[~～—–－至]"), "-").replace(Regex("\\s+"), " ").trim()

    private fun directCells(row: Element): List<Element> = row.children().toList().filter {
        it.tagName() == "td" || it.tagName() == "th"
    }

    companion object {
        private val WEEK = Regex("\\d{1,2}(?:\\s*[-,]\\s*\\d{1,2})*\\s*周(?:\\s*\\(?[单双]\\)?)?")
        private val SECTION = Regex("(?:第|\\[)?\\s*(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?\\s*节\\s*]?")
        private val BRACKET = Regex("\\[([^]]+)]")
    }
}
