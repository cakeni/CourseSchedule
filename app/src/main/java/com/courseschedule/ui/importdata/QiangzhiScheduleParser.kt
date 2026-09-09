package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Parses the displayed Qiangzhi semester table, including both titled and older <br> layouts.
 * No login, network requests, inferred class duration or guessed all-semester weeks live here.
 */
class QiangzhiScheduleParser(
    private val totalWeeks: Int,
    private val variantId: String = AcademicAdapterRegistry.QIANGZHI_AUTO
) {
    fun parse(html: String): ParsedImport = parseDocument(Jsoup.parse(html))

    internal fun matches(document: Document): Boolean = when (variantId) {
        AcademicAdapterRegistry.QIANGZHI_2017 ->
            document.selectFirst(".el-table__header") != null &&
                document.selectFirst(".el-table__body") != null
        AcademicAdapterRegistry.QIANGZHI_2024 ->
            document.selectFirst("[name=kbDataTd]") != null &&
                document.selectFirst(".qz-toolitiplists,.qz-tooltipContent-title") != null
        AcademicAdapterRegistry.QIANGZHI_CRAZY -> document.selectFirst(".kbcontent1") != null
        AcademicAdapterRegistry.QIANGZHI_BR ->
            document.selectFirst("#kbtable .kbcontent br") != null
        AcademicAdapterRegistry.QIANGZHI_NODE -> document.select("#kbtable .kbcontent").any {
            it.childrenSize() > 1 && it.selectFirst("[title]") != null
        }
        AcademicAdapterRegistry.QIANGZHI_STANDARD -> document.selectFirst("#kbtable .kbcontent") != null
        else -> document.selectFirst(
            "#kbtable,.el-table__body,[name=kbDataTd],.kbcontent1"
        ) != null
    }

    internal fun parseDocument(document: Document): ParsedImport {
        if (variantId == AcademicAdapterRegistry.QIANGZHI_2017) {
            return parse2017Document(document)
        }
        val table = document.getElementById("kbtable")?.takeIf { it.tagName() == "table" }
            ?: document.select("#kbtable1,#kbTable,table.kbtable,table[class*=kbtable]")
                .firstOrNull { it.tagName() == "table" }
            ?: document.select("table").firstOrNull { candidate ->
                candidate.select("tr").any { row ->
                    directCells(row).count { parseDay(it.text()) != null } >= 2
                } && '周' in candidate.text() &&
                    Regex("节次|第\\s*\\d+\\s*节").containsMatchIn(candidate.text())
            }
            ?: throw ImportFormatException("未找到强智学期课表，请先打开「我的课表 → 学期理论课表」")
        if (isHidden(table)) throw ImportFormatException("课表尚未显示，请先选择学期并点击查询")
        val clean = table.clone()
        clean.select("script, style, input, select, textarea, button, iframe, frame, object, embed").remove()
        clean.getAllElements().toList().filter { isHidden(it) }.forEach(Element::remove)
        val rows = clean.select("tr").toList().filter { it.closest("table") === clean }
        if (rows.size > 240) throw ImportFormatException("课表行数异常，请只导入学期理论课表")

        val dayColumns = mutableMapOf<Int, Int>()
        val occupiedUntil = mutableMapOf<Int, Int>()
        val courses = mutableListOf<Course>()
        var unreadableBlocks = 0
        val unreadableCodes = mutableSetOf<AcademicImportErrorCode>()
        rows.forEachIndexed { rowIndex, row ->
            var column = 0
            val cells = row.children().toList().filter { it.tagName() in setOf("th", "td") }.map { cell ->
                while ((occupiedUntil[column] ?: 0) > rowIndex) column++
                val span = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 32) ?: 1
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 240) ?: 1
                val startColumn = column
                repeat(span) { occupiedUntil[column++] = rowIndex + rowSpan }
                startColumn to cell
            }
            val headings = cells.mapNotNull { (index, cell) -> parseDay(cell.text())?.let { index to it } }
            if (headings.size >= 2) {
                dayColumns.clear()
                dayColumns.putAll(headings)
                return@forEachIndexed
            }
            if (dayColumns.isEmpty()) return@forEachIndexed
            val rowSections = cells.filter { it.first !in dayColumns }.firstNotNullOfOrNull {
                sectionMatch(it.second.text())?.let { match -> parseNumbers(match.groupValues[1], 30) }
            }
            cells.forEach cellLoop@{ (index, cell) ->
                val day = dayColumns[index] ?: return@cellLoop
                val contentSelector = when (variantId) {
                    AcademicAdapterRegistry.QIANGZHI_CRAZY -> ".kbcontent1"
                    AcademicAdapterRegistry.QIANGZHI_2024 -> ".qz-toolitiplists"
                    else -> ".kbcontent"
                }
                val contents = cell.select(contentSelector).toList().filter { element ->
                    element.parents().toList().none { it !== cell && it.`is`(contentSelector) }
                }.ifEmpty {
                    cell.select("div").toList().filter { div ->
                        hasTime(div) && div.select("div").none { it !== div && hasTime(it) }
                    }.ifEmpty { listOf(cell) }
                }
                contents.forEach { content ->
                    content.html().split(Regex("<hr\\b[^>]*>|[-─—]{5,}", RegexOption.IGNORE_CASE)).forEach blockLoop@{ html ->
                        val block = Jsoup.parseBodyFragment(html).body()
                        if (block.text().isBlank() || block.text().trim() in setOf("无", "--", "-")) return@blockLoop
                        try {
                            val parsed = if (variantId == AcademicAdapterRegistry.QIANGZHI_2024) {
                                parse2024Block(block, day, rowSections)
                            } else {
                                parseBlock(block, day, rowSections)
                            }
                            if (parsed.isEmpty()) {
                                unreadableBlocks++
                                unreadableCodes += when {
                                    '周' !in block.text() -> AcademicImportErrorCode.MISSING_WEEK
                                    sectionMatch(block.text()) == null && rowSections == null ->
                                        AcademicImportErrorCode.MISSING_SECTION
                                    else -> AcademicImportErrorCode.PARTIAL_PARSE
                                }
                            } else courses += parsed
                        } catch (error: ImportFormatException) {
                            unreadableBlocks++
                            unreadableCodes += error.errorCode ?: AcademicImportErrorCode.PARTIAL_PARSE
                        }
                    }
                }
            }
        }
        if (dayColumns.isEmpty()) throw ImportFormatException(
            "未识别到星期表头，请打开完整的学期理论课表",
            AcademicImportErrorCode.MISSING_DAY
        )
        if (unreadableBlocks > 0) throw ImportFormatException(
            "有 $unreadableBlocks 个课程块的周次或节次无法确认，本次未导入。请使用完整学期课表，勿使用周课表或截图。",
            unreadableCodes.singleOrNull() ?: AcademicImportErrorCode.PARTIAL_PARSE
        )
        if (courses.isEmpty()) throw ImportFormatException("当前页面没有已排课课程，请确认已选择学期并点击查询")
        // Some systems repeat a class in each spanned cell or provide duplicate print layouts.
        return ParsedImport(courses.distinctBy {
            listOf(it.courseName, it.teacher, it.classroom, it.dayOfWeek, it.startSection,
                it.endSection, it.startWeek, it.endWeek, it.weekType)
        }, sourceLabel = "强智教务 · HTML")
    }

    private fun parse2017Document(document: Document): ParsedImport {
        val header = document.select(".el-table__header tr").lastOrNull()
            ?: throw ImportFormatException("未找到强智 2017 星期表头")
        val bodyRows = document.select(".el-table__body tr")
        if (bodyRows.isEmpty()) throw ImportFormatException("未找到强智 2017 课表内容")
        val merged = Document.createShell("")
        val table = merged.body().appendElement("table").attr("id", "kbtable")
        table.appendChild(header.clone())
        bodyRows.take(240).forEach { table.appendChild(it.clone()) }
        return QiangzhiScheduleParser(
            totalWeeks,
            AcademicAdapterRegistry.QIANGZHI_STANDARD
        ).parseDocument(merged).copy(sourceLabel = "强智教务 · 2017")
    }

    private fun parse2024Block(block: Element, day: Int, rowSections: List<Int>?): List<Course> {
        val name = block.selectFirst(".qz-tooltipContent-title")?.text()?.trim()
            ?: block.selectFirst("[title=课程名称],[title=课程名]")?.text()?.trim()
            ?: throw ImportFormatException("课程名称缺失")
        val text = block.wholeText()
        val teacher = Regex("(?:老师|教师)\\s*[:：]?\\s*([^\\s,，;；]+)")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val room = Regex("(?:地点|教室)\\s*[:：]?\\s*([^\\n,，;；]+)")
            .find(text)?.groupValues?.getOrNull(1).orEmpty().trim()
        val times = block.select(".qz-tooltipContent-detailitem").map(Element::text)
            .filter { '周' in it && sectionMatch(it) != null }
            .ifEmpty {
                Regex("\\d{1,2}(?:\\s*[-~～—–－至,，、]\\s*\\d{1,2})*\\s*周.{0,48}?(?:第|\\[)?\\s*\\d{1,2}(?:\\s*[-,]\\s*\\d{1,2})*\\s*节\\s*]?", RegexOption.DOT_MATCHES_ALL)
                    .findAll(text).map(MatchResult::value).toList()
            }
        if (times.isEmpty()) throw ImportFormatException("课程周次或节次缺失")
        return times.flatMap { time ->
            val withoutChineseLabel = time.substringAfter("时间：", time)
            val value = withoutChineseLabel.substringAfter("时间:", withoutChineseLabel)
            courses(name, teacher, room, day, value, rowSections)
        }
    }

    private fun parseBlock(block: Element, day: Int, rowSections: List<Int>?): List<Course> {
        val titled = block.select("[title]").toList()
        val timeFields = titled.filter { it.attr("title").contains("周次") }
        val leading = block.clone().apply { select("[title]").remove() }.wholeText()
        val name = titled.firstOrNull { it.attr("title") in setOf("课程名称", "课程名") }?.text()
            ?: lines(leading).firstOrNull { !isCourseCode(it) }
            ?: ""
        if (timeFields.isNotEmpty()) {
            if (name.isBlank()) throw ImportFormatException("课程名称缺失")
            return timeFields.flatMap { time ->
                val timeIndex = titled.indexOf(time)
                val teacher = titled.take(timeIndex).lastOrNull { isTeacher(it.attr("title")) }?.text().orEmpty()
                val room = titled.drop(timeIndex + 1).takeWhile { !it.attr("title").contains("周次") }
                    .firstOrNull { isRoom(it.attr("title")) }?.text()
                    ?: titled.firstOrNull { isRoom(it.attr("title")) }?.text().orEmpty()
                courses(name.trim(), teacher.trim(), room.trim(), day, time.text(), rowSections)
            }
        }

        // Older Qiangzhi: name / teacher / 1-8周[1-2节] / classroom; a name can have several slots.
        val rows = lines(block.wholeText())
        val timeIndexes = rows.indices.filter { '周' in rows[it] && sectionMatch(rows[it]) != null }
        val oldName = rows.take(timeIndexes.firstOrNull() ?: 0).firstOrNull { !isCourseCode(it) }.orEmpty()
        return timeIndexes.flatMap { index ->
            if (oldName.isBlank() || index < 2 || index + 1 >= rows.size) throw ImportFormatException("课程字段不完整")
            courses(oldName, rows[index - 1], rows[index + 1], day, rows[index], rowSections)
        }
    }

    private fun courses(
        name: String, teacher: String, room: String, day: Int,
        time: String, rowSections: List<Int>?
    ): List<Course> {
        val normalized = normalize(time)
        val sectionsMatch = sectionMatch(normalized)
        val sections = sectionsMatch?.let { parseNumbers(it.groupValues[1], 30) } ?: rowSections
            ?: throw ImportFormatException("课程节次缺失", AcademicImportErrorCode.MISSING_SECTION)
        val weeksText = if (sectionsMatch == null) normalized else normalized.removeRange(sectionsMatch.range)
        val weeks = parseWeeks(weeksText)
        val sectionRuns = sections.sorted().fold(mutableListOf<IntRange>()) { runs, section ->
            val last = runs.lastOrNull()
            if (last != null && last.last + 1 == section) runs[runs.lastIndex] = last.first..section
            else runs += section..section
            runs
        }
        return sectionRuns.flatMap { section ->
            compressImportWeeks(weeks).map { range ->
                Course(courseName = name, teacher = teacher, classroom = room, dayOfWeek = day,
                    startSection = section.first, endSection = section.last,
                    startWeek = range.start, endWeek = range.end, weekType = range.weekType,
                    colorIndex = Math.floorMod(name.hashCode(), 16))
            }
        }
    }

    internal fun parseWeeks(text: String): List<Int> {
        val clean = normalize(text).replace("周次:", "").replace("(周)", "")
            .replace("周", "").replace("第", "").replace(Regex("\\s+"), "")
            .trim('[', ']', '{', '}', ':')
        if (clean in setOf("单", "(单)", "双", "(双)")) {
            return (1..totalWeeks.coerceIn(1, 52)).filter { (it % 2 == 1) == clean.contains('单') }
        }
        if (clean.isBlank()) throw ImportFormatException("课程周次缺失", AcademicImportErrorCode.MISSING_WEEK)
        val result = sortedSetOf<Int>()
        clean.split(',', ';').forEach { part ->
            val match = Regex("(\\d{1,2})(?:-(\\d{1,2}))?(?:\\(([单双])\\)|([单双]))?").matchEntire(part)
                ?: throw ImportFormatException("无法识别课程周次")
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].toIntOrNull() ?: first
            if (first !in 1..52 || last !in first..52) throw ImportFormatException("课程周次超出范围")
            val marker = match.groupValues[3].ifBlank { match.groupValues[4] }
            result += (first..last).filter { marker.isEmpty() ||
                (marker == "单" && it % 2 == 1) || (marker == "双" && it % 2 == 0) }
        }
        if (result.isEmpty()) throw ImportFormatException("课程周次为空")
        return result.toList()
    }

    private fun parseNumbers(value: String, maximum: Int): List<Int> {
        val numbers = sortedSetOf<Int>()
        normalize(value).replace(Regex("\\s+"), "").split(',').forEach { part ->
            val match = Regex("(\\d{1,2})(?:-(\\d{1,2}))?").matchEntire(part)
                ?: throw ImportFormatException("无法识别课程节次")
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toIntOrNull() ?: start
            if (start !in 1..maximum || end !in start..maximum) throw ImportFormatException("课程节次超出范围")
            numbers += start..end
        }
        return numbers.toList()
    }

    private fun sectionMatch(text: String): MatchResult? =
        Regex("(?:\\[|第)?\\s*(\\d{1,2}(?:\\s*[-,]\\s*\\d{1,2})*)\\s*节\\s*\\]?").find(normalize(text))

    private fun normalize(text: String): String = text.replace('（', '(').replace('）', ')')
        .replace('【', '[').replace('】', ']').replace('，', ',').replace('、', ',')
        .replace('；', ';').replace('：', ':').replace(Regex("[~～—–－至]"), "-")

    private fun lines(text: String): List<String> = text.replace('\u00a0', ' ').lineSequence()
        .map(String::trim).filter(String::isNotBlank).toList()

    private fun isCourseCode(text: String): Boolean =
        Regex("(?=.*\\d)[A-Za-z0-9_.\\-]{6,}").matches(text)

    private fun isTeacher(title: String) = title in setOf("老师", "教师", "任课教师", "授课教师")
    private fun isRoom(title: String) = title in setOf("教室", "地点", "上课地点")
    private fun hasTime(element: Element) = '周' in element.text() && sectionMatch(element.text()) != null
    private fun isHidden(element: Element): Boolean =
        sequenceOf(element).plus(element.parents().toList()).any { candidate ->
            candidate.hasAttr("hidden") || candidate.attr("aria-hidden") == "true" ||
                Regex("(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(candidate.attr("style"))
        }

    private fun directCells(row: Element): List<Element> = row.children().toList()
        .filter { it.tagName() in setOf("th", "td") }

    private fun parseDay(text: String): Int? {
        val value = text.trim().replace(Regex("\\s+"), "")
        val match = Regex("^(?:星期|周|礼拜)?([一二三四五六日天])(?:\\d.*)?$").matchEntire(value) ?: return null
        return "一二三四五六日".indexOf(match.groupValues[1].replace('天', '日')).plus(1).takeIf { it > 0 }
    }
}
