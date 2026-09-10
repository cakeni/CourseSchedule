package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Shared strict table mechanics for report-style Kingosoft and SouthSoft pages. */
internal class StrictTimetableTableParser(
    private val totalWeeks: Int,
    private val label: String,
    private val selectors: List<String>,
    private val rejectImageOnly: Boolean = false,
    private val orderedRowsAreSections: Boolean = false,
    private val splitDoubleBreaks: Boolean = false,
    private val splitBoldBlocks: Boolean = false,
    private val splitLeafCourseDivs: Boolean = false,
    private val compactBraceFormat: Boolean = false,
    private val defaultAllWeeks: Boolean = false
) {
    fun matches(document: Document): Boolean = selectors.any { selector ->
        runCatching { document.select(selector).isNotEmpty() }.getOrDefault(false)
    }

    fun parse(html: String): ParsedImport {
        val document = Jsoup.parse(html)
        val roots = selectors.flatMap { selector ->
            runCatching { document.select(selector).toList() }.getOrDefault(emptyList())
        }.distinct().filterNot(::isHidden)
        if (roots.isEmpty()) {
            throw ImportFormatException("未找到 $label 课表", AcademicImportErrorCode.NO_TIMETABLE)
        }
        val tables = roots.flatMap { root ->
            if (root.tagName() == "table") listOf(root) else root.select("table").toList()
        }.distinct().filterNot(::isHidden)
        if (tables.isEmpty()) {
            if (rejectImageOnly && roots.any { it.select("img,canvas,svg").isNotEmpty() }) {
                throw ImportFormatException(
                    "$label 当前是图片课表，无法安全读取；请切换到报表或列表视图",
                    AcademicImportErrorCode.UNSUPPORTED_VARIANT
                )
            }
            throw ImportFormatException("未找到 $label 表格课表", AcademicImportErrorCode.NO_TIMETABLE)
        }

        var matchedTable = false
        val parsed = mutableListOf<Course>()
        for (table in tables.take(12)) {
            val expanded = expand(table)
            val structured = parseStructured(expanded)
            if (structured != null) {
                matchedTable = true
                parsed += structured
                continue
            }
            val grid = parseGrid(expanded)
            if (grid != null) {
                matchedTable = true
                parsed += grid
            }
        }
        if (!matchedTable) {
            throw ImportFormatException(
                "$label 页面结构不受支持",
                AcademicImportErrorCode.UNSUPPORTED_VARIANT
            )
        }
        if (parsed.isEmpty()) {
            throw ImportFormatException("$label 页面没有课程", AcademicImportErrorCode.NO_TIMETABLE)
        }
        return ParsedImport(parsed.distinctBy(::courseKey), sourceLabel = "$label · HTML")
    }

    private data class Slot(
        val cell: Element,
        val originRow: Int,
        val originColumn: Int,
        val rowSpan: Int,
        val columnSpan: Int,
        val origin: Boolean
    )

    private data class ExpandedTable(val rows: List<List<Slot?>>)

    private fun expand(table: Element): ExpandedTable {
        val sourceRows = table.select("tr").toList().filter { it.closest("table") === table }
        if (sourceRows.size > 240) {
            throw ImportFormatException("$label 表格行数异常", AcademicImportErrorCode.PAYLOAD_TOO_LARGE)
        }
        val active = mutableMapOf<Int, Slot>()
        val result = mutableListOf<List<Slot?>>()
        var totalCells = 0
        sourceRows.forEachIndexed { rowIndex, row ->
            val rowSlots = mutableMapOf<Int, Slot>()
            active.forEach { (column, slot) ->
                if (rowIndex < slot.originRow + slot.rowSpan) rowSlots[column] = slot.copy(origin = false)
            }
            var column = 0
            directCells(row).forEach { cell ->
                while (rowSlots.containsKey(column)) column++
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 240) ?: 1
                val columnSpan = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 32) ?: 1
                val slot = Slot(cell, rowIndex, column, rowSpan, columnSpan, origin = true)
                repeat(columnSpan) { offset ->
                    rowSlots[column + offset] = if (offset == 0) slot else slot.copy(origin = false)
                    if (rowSpan > 1) active[column + offset] = slot
                }
                column += columnSpan
                totalCells++
                if (totalCells > 18_000) {
                    throw ImportFormatException("$label 表格节点过多", AcademicImportErrorCode.PAYLOAD_TOO_LARGE)
                }
            }
            val width = (rowSlots.keys.maxOrNull() ?: -1) + 1
            result += List(width) { rowSlots[it] }
            active.entries.removeAll { (_, slot) -> rowIndex + 1 >= slot.originRow + slot.rowSpan }
        }
        return ExpandedTable(result)
    }

    private fun parseStructured(table: ExpandedTable): List<Course>? {
        val headerIndex = table.rows.indexOfFirst { row ->
            val headers = row.map { normalizeHeader(it?.cell?.text().orEmpty()) }
            headers.any { it in COURSE_HEADERS } && headers.any { it in DAY_HEADERS } &&
                headers.any { it in SECTION_HEADERS } && headers.any { it in WEEK_HEADERS }
        }
        if (headerIndex < 0) return null
        val headers = table.rows[headerIndex].map { normalizeHeader(it?.cell?.text().orEmpty()) }
        val nameColumn = headers.indexOfFirst { it in COURSE_HEADERS }
        val dayColumn = headers.indexOfFirst { it in DAY_HEADERS }
        val sectionColumn = headers.indexOfFirst { it in SECTION_HEADERS }
        val weekColumn = headers.indexOfFirst { it in WEEK_HEADERS }
        val teacherColumn = headers.indexOfFirst { it in TEACHER_HEADERS }
        val roomColumn = headers.indexOfFirst { it in ROOM_HEADERS }
        val result = mutableListOf<Course>()
        table.rows.drop(headerIndex + 1).forEach { row ->
            val values = row.map { it?.cell?.wholeText().orEmpty().trim() }
            if (values.all(String::isBlank)) return@forEach
            val name = values.valueAt(nameColumn)
            if (name.isBlank()) {
                throw ImportFormatException("$label 课程名称缺失", AcademicImportErrorCode.PARTIAL_PARSE)
            }
            val day = parseDay(values.valueAt(dayColumn))
                ?: throw ImportFormatException("$label 课程星期缺失", AcademicImportErrorCode.MISSING_DAY)
            val sections = parseSections(values.valueAt(sectionColumn))
            val weeks = parseWeeks(values.valueAt(weekColumn))
            result += buildCourses(
                name, values.valueAt(teacherColumn), values.valueAt(roomColumn), day,
                listOf(sections to weeks)
            )
        }
        return result
    }

    private fun parseGrid(table: ExpandedTable): List<Course>? {
        val headerIndex = table.rows.indexOfFirst { row ->
            row.mapNotNull { parseDay(it?.cell?.text().orEmpty()) }.distinct().size >= 2
        }
        if (headerIndex < 0) return null
        val dayColumns = table.rows[headerIndex].mapIndexedNotNull { index, slot ->
            parseDay(slot?.cell?.text().orEmpty())?.let { index to it }
        }.toMap()
        val rowSections = table.rows.map { row ->
            row.mapIndexedNotNull { column, slot ->
                if (column in dayColumns || slot == null) null else parseSectionsOrNull(slot.cell.text())
            }.flatten().distinct().sorted()
        }
        val result = mutableListOf<Course>()
        val handled = mutableSetOf<Pair<Int, Int>>()
        table.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex <= headerIndex) return@forEachIndexed
            row.forEachIndexed cellLoop@{ column, slot ->
                val day = dayColumns[column] ?: return@cellLoop
                if (slot == null || !slot.origin || !handled.add(slot.originRow to slot.originColumn)) return@cellLoop
                val text = slot.cell.wholeText().trim()
                if (text.isBlank() || text in EMPTY_CELLS) return@cellLoop
                val spannedDays = (slot.originColumn until slot.originColumn + slot.columnSpan)
                    .mapNotNull(dayColumns::get).distinct()
                if (spannedDays.size != 1) {
                    throw ImportFormatException("$label 课程跨星期单元格无法确认", AcademicImportErrorCode.MISSING_DAY)
                }
                val explicitSections = (slot.originRow until slot.originRow + slot.rowSpan)
                    .flatMap { rowSections.getOrElse(it) { emptyList() } }.distinct().sorted()
                val fallbackSections = explicitSections.ifEmpty {
                    if (!orderedRowsAreSections) emptyList() else {
                        val start = slot.originRow - headerIndex
                        (start until start + slot.rowSpan).toList()
                    }
                }
                val blocks = splitBlocks(slot.cell)
                blocks.forEach { block ->
                    result += parseCourseBlock(block, day, fallbackSections)
                }
            }
        }
        return result
    }

    private fun splitBlocks(cell: Element): List<Element> {
        val preferred = cell.select(
            ".kbcontent,.kbcontent1,.course,.courseInfo,.kb-item,[data-course]"
        ).toList().filter { candidate ->
            candidate.parents().toList().none { parent -> parent !== cell &&
                parent.`is`(".kbcontent,.kbcontent1,.course,.courseInfo,.kb-item,[data-course]") }
        }
        if (preferred.isNotEmpty()) return preferred
        if (splitLeafCourseDivs) {
            val leafDivs = cell.select("div").toList().filter { candidate ->
                WEEK_TOKEN.containsMatchIn(candidate.text()) && candidate.select("div").none { nested ->
                    nested !== candidate && WEEK_TOKEN.containsMatchIn(nested.text())
                }
            }
            if (leafDivs.isNotEmpty()) return leafDivs
        }
        val separators = buildList {
            if (splitDoubleBreaks) add("(?:<br\\b[^>]*>\\s*){2,}")
            if (splitBoldBlocks) add("(?=<b\\b)")
            if (compactBraceFormat) add("[;；]")
            add("<hr\\b[^>]*>")
            add("[-─—]{5,}")
        }.joinToString("|")
        return cell.html().split(Regex(separators, RegexOption.IGNORE_CASE))
            .map { Jsoup.parseBodyFragment(it).body() }
            .filter { it.text().trim().isNotBlank() && it.text().trim() !in EMPTY_CELLS }
    }

    private fun parseCourseBlock(block: Element, day: Int, fallbackSections: List<Int>): List<Course> {
        val text = block.wholeText().replace('\u00a0', ' ').trim()
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val rawName = block.select("[title=课程名称],[title=课程名],[data-field=course]").firstOrNull()?.text()?.trim()
            ?: extractLabel(text, "课程(?:名称|名)?")
            ?: if (compactBraceFormat) lines.firstOrNull().orEmpty() else {
                lines.firstOrNull { line ->
                    !WEEK_TOKEN.containsMatchIn(line) && !SECTION_TOKEN.containsMatchIn(line) &&
                        !LABEL_ONLY.containsMatchIn(line) && !isCourseCode(line)
                }.orEmpty()
            }
        val name = if (compactBraceFormat) rawName.substringBefore('{').substringBefore('｛')
            .substringBefore('(').substringBefore('（').trim() else rawName
        if (name.isBlank()) {
            throw ImportFormatException("$label 课程名称缺失", AcademicImportErrorCode.PARTIAL_PARSE)
        }
        val compactDetails = if (compactBraceFormat) {
            Regex("[（(]([^）)]+)[）)]").find(text)?.groupValues?.get(1)
                ?.trim()?.split(Regex("\\s+"))
        } else null
        val teacher = extractLabel(text, "(?:任课|授课)?(?:教师|老师)")
            ?: compactDetails?.firstOrNull().orEmpty()
        val room = extractLabel(text, "(?:上课)?(?:地点|教室)")
            ?: compactDetails?.lastOrNull().orEmpty()
        val meetings = parseMeetingPairs(text, fallbackSections)
        return buildCourses(name, teacher, room, day, meetings)
    }

    private fun parseMeetingPairs(
        text: String,
        fallbackSections: List<Int>
    ): List<Pair<List<Int>, List<Int>>> {
        val result = mutableListOf<Pair<List<Int>, List<Int>>>()
        WEEK_THEN_SECTION.findAll(normalize(text)).forEach { match ->
            result += parseSections(match.groups["sections"]!!.value) to
                parseWeeks(match.groups["weeks"]!!.value)
        }
        if (result.isEmpty()) {
            SECTION_THEN_WEEK.findAll(normalize(text)).forEach { match ->
                result += parseSections(match.groups["sections"]!!.value) to
                    parseWeeks(match.groups["weeks"]!!.value)
            }
        }
        if (result.isNotEmpty()) return result.distinct()
        val weekValues = WEEK_TOKEN.findAll(normalize(text)).flatMap { match ->
            parseWeeks(match.value).asSequence()
        }.distinct().toList().ifEmpty {
            if (defaultAllWeeks) (1..totalWeeks).toList() else {
                throw ImportFormatException("$label 课程周次缺失", AcademicImportErrorCode.MISSING_WEEK)
            }
        }
        val sections = SECTION_TOKEN.find(normalize(text))?.value?.let(::parseSections)
            ?: fallbackSections.takeIf(List<Int>::isNotEmpty)
            ?: throw ImportFormatException("$label 课程节次缺失", AcademicImportErrorCode.MISSING_SECTION)
        return listOf(sections to weekValues)
    }

    private fun buildCourses(
        rawName: String,
        teacher: String,
        room: String,
        day: Int,
        meetings: List<Pair<List<Int>, List<Int>>>
    ): List<Course> {
        val name = rawName.trim()
        return meetings.flatMap { (sectionValues, weekValues) ->
            contiguousRuns(sectionValues).flatMap { sections ->
                compressImportWeeks(weekValues).map { weeks ->
                    Course(
                        courseName = name,
                        teacher = teacher.trim(),
                        classroom = room.trim(),
                        dayOfWeek = day,
                        startSection = sections.first,
                        endSection = sections.last,
                        startWeek = weeks.start,
                        endWeek = weeks.end,
                        weekType = weeks.weekType,
                        colorIndex = Math.floorMod(name.hashCode(), 16)
                    )
                }
            }
        }
    }

    private fun parseWeeks(value: String): List<Int> = try {
        QiangzhiScheduleParser(totalWeeks).parseWeeks(value)
    } catch (error: ImportFormatException) {
        throw ImportFormatException(error.message ?: "$label 周次无效", AcademicImportErrorCode.MISSING_WEEK)
    }

    private fun parseSections(value: String): List<Int> = parseSectionsOrNull(value)
        ?: throw ImportFormatException("$label 课程节次缺失", AcademicImportErrorCode.MISSING_SECTION)

    private fun parseSectionsOrNull(value: String): List<Int>? {
        val match = SECTION_TOKEN.find(normalize(value)) ?: return null
        val numbers = sortedSetOf<Int>()
        match.groupValues[1].replace('，', ',').replace('、', ',')
            .replace(Regex("[~～—–－至]"), "-").split(',').forEach { part ->
                val range = Regex("\\s*(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?\\s*").matchEntire(part)
                    ?: return null
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toIntOrNull() ?: start
                if (start !in 1..30 || end !in start..30) return null
                numbers += start..end
            }
        return numbers.toList().takeIf(List<Int>::isNotEmpty)
    }

    private fun parseDay(value: String): Int? {
        val match = Regex("(?:星期|周|礼拜)?([一二三四五六日天])").find(value.replace(Regex("\\s+"), ""))
            ?: return null
        return "一二三四五六日".indexOf(match.groupValues[1].replace('天', '日'))
            .plus(1).takeIf { it in 1..7 }
    }

    private fun extractLabel(text: String, label: String): String? =
        Regex("(?:$label)\\s*[:：]\\s*([^\\n,，;；]+)").find(text)?.groupValues?.get(1)
            ?.trim()?.trimEnd(']', '}', '｝', ')', '）')

    private fun normalize(value: String): String = value.replace('（', '(').replace('）', ')')
        .replace('【', '[').replace('】', ']').replace(Regex("[~～—–－至]"), "-")

    private fun contiguousRuns(values: List<Int>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        for (value in values.distinct().sorted()) {
            val last = result.lastOrNull()
            if (last != null && last.last + 1 == value) result[result.lastIndex] = last.first..value
            else result += value..value
        }
        return result
    }

    private fun directCells(row: Element): List<Element> = row.children().toList().filter {
        it.tagName() == "td" || it.tagName() == "th"
    }

    private fun isHidden(element: Element): Boolean =
        sequenceOf(element).plus(element.parents().toList()).any { candidate ->
            candidate.hasAttr("hidden") || candidate.attr("aria-hidden") == "true" ||
                Regex("display\\s*:\\s*none|visibility\\s*:\\s*hidden", RegexOption.IGNORE_CASE)
                    .containsMatchIn(candidate.attr("style"))
        }

    private fun normalizeHeader(value: String): String = value.trim().lowercase()
        .replace(Regex("[\\s_/-]"), "")

    private fun List<String>.valueAt(index: Int): String = getOrElse(index) { "" }
    private fun isCourseCode(value: String): Boolean =
        value.matches(Regex("(?=.*\\d)[A-Za-z0-9_.-]{6,}"))

    private fun courseKey(course: Course): List<Any> = listOf(
        course.courseName, course.teacher, course.classroom, course.dayOfWeek,
        course.startSection, course.endSection, course.startWeek, course.endWeek, course.weekType
    )

    companion object {
        private val EMPTY_CELLS = setOf("-", "--", "无", "暂无", "1")
        private val WEEK_TOKEN = Regex(
            "\\d{1,2}(?:\\s*[-~～—–－至,，、]\\s*\\d{1,2})*\\s*周(?:\\s*\\(?[单双]\\)?)?"
        )
        private val SECTION_TOKEN = Regex(
            "(?:第|\\[)?\\s*(\\d{1,2}(?:\\s*[-~～—–－至,，、]\\s*\\d{1,2})*)\\s*节\\s*]?"
        )
        private val WEEK_THEN_SECTION = Regex(
            "(?<weeks>${WEEK_TOKEN.pattern}).{0,80}?(?<sections>${SECTION_TOKEN.pattern})"
        )
        private val SECTION_THEN_WEEK = Regex(
            "(?<sections>${SECTION_TOKEN.pattern}).{0,80}?(?<weeks>${WEEK_TOKEN.pattern})"
        )
        private val LABEL_ONLY = Regex("^(?:课程|课程名称|教师|老师|教室|地点|周次|节次)\\s*[:：]")
        private val COURSE_HEADERS = setOf("课程", "课程名", "课程名称", "coursename")
        private val DAY_HEADERS = setOf("星期", "周几", "上课日", "weekday", "day")
        private val SECTION_HEADERS = setOf("节次", "上课节次", "section", "sections")
        private val WEEK_HEADERS = setOf("周次", "上课周", "week", "weeks")
        private val TEACHER_HEADERS = setOf("教师", "老师", "任课教师", "授课教师", "teacher")
        private val ROOM_HEADERS = setOf("教室", "地点", "上课地点", "room", "location")
    }
}
