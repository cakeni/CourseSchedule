package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Strict, local parsers for common academic-system payloads. Every generated course must carry
 * an explicit weekday, section range and week set; an unrecognised row fails instead of guessing.
 */
internal class GenericAcademicScheduleParser(private val totalWeeks: Int) {

    fun parse(system: AcademicSystem, html: String, data: JsonElement?): ParsedImport {
        val courses = when (system) {
            AcademicSystem.URP_NEW -> parseNewUrp(requireData(data, "新版 URP 课表数据"))
            AcademicSystem.CHENGFANG -> parseChengfang(data, html)
            AcademicSystem.EAMS -> EamsScheduleParser().parse(data).courses
            AcademicSystem.SHUWEI -> parseShuwei(requireData(data, "树维课表数据"))
            AcademicSystem.XBELL -> parseXbell(requireData(data, "凌展课表数据"))
            AcademicSystem.CHAOXING -> parseChaoxing(requireData(data, "超星教务课表数据"))
            AcademicSystem.CHAOXING_SHARE -> parseChaoxingShare(requireData(data, "超星分享课表数据"))
            AcademicSystem.AIC_HTML -> parseAic(html)
            AcademicSystem.ZHENGFANG_HTML -> parseZhengfang(data, html)
            AcademicSystem.URP_HTML,
            AcademicSystem.VATUU_HTML,
            AcademicSystem.UMOOC_HTML,
            AcademicSystem.SOUTH_SOFT_HTML,
            AcademicSystem.YILIAN_HTML,
            AcademicSystem.KINGOSOFT_HTML,
            AcademicSystem.STRUCTURED_HTML -> parseHtml(system, html)
            AcademicSystem.WISEDU,
            AcademicSystem.QIANGZHI_HTML -> throw ImportFormatException("教务解析器选择错误")
        }
        if (courses.isEmpty()) throw ImportFormatException("当前页面没有识别到可导入的课程")
        return ParsedImport(courses.distinctBy(::courseKey), sourceLabel = "通用教务")
    }

    private fun parseNewUrp(root: JsonElement): List<Course> {
        val objects = collectObjects(root)
        val dateCourses = objects.filter { it.has("courseName") && it.has("timeAndPlaceList") }
            .flatMap { course ->
                val name = course.string("courseName")
                val teacher = course.string("attendClassTeacher")
                course.array("timeAndPlaceList").flatMap { value ->
                    val time = value.asObjectOrNull()
                        ?: throw ImportFormatException("新版 URP 上课时间格式不完整")
                    val start = time.int("classSessions")
                    val count = time.int("continuingSession")
                    if (start == null || count == null || count < 1) {
                        throw ImportFormatException("新版 URP 课程节次缺失")
                    }
                    buildCourses(
                        name = name,
                        teacher = teacher,
                        room = listOf(time.string("campusName"), time.string("teachingBuildingName"),
                            time.string("classroomName")).joinToString("").trim(),
                        day = time.int("classDay"),
                        sectionRuns = listOf(start..(start + count - 1)),
                        weeks = bitmapWeeks(time.string("classWeek"))
                    )
                }
            }
        if (dateCourses.isNotEmpty()) return dateCourses

        val classList = objects.filter { it.has("kcm") && it.get("id")?.isJsonObject == true }
            .flatMap { item ->
                val id = item.getAsJsonObject("id")
                val start = id.int("skjc")
                    ?: throw ImportFormatException("新版 URP 课程节次缺失")
                val count = item.int("cxjc")
                    ?: throw ImportFormatException("新版 URP 持续节次缺失")
                buildCourses(
                    name = item.string("kcm"),
                    teacher = item.string("jsm"),
                    room = item.string("jxlm") + item.string("jasm"),
                    day = id.int("skxq"),
                    sectionRuns = listOf(start..(start + count - 1)),
                    weeks = bitmapWeeks(id.string("skzc"))
                )
            }
        if (classList.isEmpty()) throw ImportFormatException("未找到新版 URP 的课程列表，请进入本学期课表并等待加载完成")
        return classList
    }

    private fun parseChengfang(data: JsonElement?, html: String): List<Course> {
        val root = data ?: extractJavascriptArray(html, "kbxx")
            ?: throw ImportFormatException("未找到乘方课表数据，请选择全部周次并点击查询课表")
        val rows = collectObjects(root).filter {
            it.has("kcmc") && it.has("xq") && it.has("jcdm2") && it.has("zcs")
        }
        if (rows.isEmpty()) throw ImportFormatException("乘方课表字段不完整")
        return rows.flatMap { row ->
            val sections = explicitNumbers(row.string("jcdm2"), 30)
            buildCourses(
                name = row.string("kcmc"), teacher = row.string("teaxms"),
                room = row.string("jxcdmcs"), day = row.int("xq"),
                sectionRuns = contiguousRuns(sections), weeks = explicitWeeks(row.string("zcs"))
            )
        }
    }

    private fun parseShuwei(root: JsonElement): List<Course> {
        val container = collectObjects(root).firstOrNull { it.has("activities") }
            ?: throw ImportFormatException("未找到树维课表活动数据，请在“我的课表”等待加载完成")
        val activities = container.array("activities")
        val objectActivities = activities.map { it.asObjectOrNull() }
        val isCurrentShape = objectActivities.filterNotNull().any {
            it.has("weekIndexes") || it.has("weekday") || it.has("startUnit") || it.has("endUnit")
        }
        if (isCurrentShape) {
            if (objectActivities.any { item -> item == null || !CURRENT_SHUWEI_FIELDS.all(item::has) }) {
                throw ImportFormatException("树维课程的星期、节次或周次字段不完整")
            }
            val current = objectActivities.filterNotNull()
            return current.flatMap { item ->
                val weeks = item.array("weekIndexes").mapNotNull { value ->
                    value.takeIf(JsonElement::isJsonPrimitive)?.asString?.toIntOrNull()
                }
                buildCourses(
                    name = item.string("courseName"), teacher = item.string("teachers"),
                    room = item.string("room"), day = item.int("weekday"),
                    sectionRuns = listOf(
                        (item.int("startUnit") ?: throw ImportFormatException("树维课程开始节次缺失"))..
                            (item.int("endUnit") ?: throw ImportFormatException("树维课程结束节次缺失"))
                    ),
                    weeks = weeks
                )
            }
        }

        // Older mobile pages expose one activity list per weekday/section cell.
        val unitCount = container.int("unitCount")
            ?: container.array("courseUnits").size.takeIf { it > 0 }
            ?: throw ImportFormatException("树维课表节次数缺失")
        if (unitCount !in 1..30) throw ImportFormatException("树维课表节次数异常")
        data class LegacyActivity(
            val name: String,
            val teacher: String,
            val room: String,
            val day: Int,
            val section: Int,
            val weeks: List<Int>
        )
        val legacy = activities.flatMapIndexed { index, cell ->
            val items = cell.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()
            items.map { value ->
                val item = value.asObjectOrNull()
                    ?: throw ImportFormatException("树维课程数据格式不完整")
                LegacyActivity(
                    name = item.string("courseName").substringBefore('(').trim(),
                    teacher = item.string("teacherName"), room = item.string("roomName"),
                    day = index / unitCount + 1,
                    section = index % unitCount + 1,
                    weeks = bitmapWeeks(item.string("vaildWeeks"), allowLeadingZero = true)
                )
            }
        }
        return legacy.groupBy { activity ->
            listOf(activity.name, activity.teacher, activity.room, activity.day, activity.weeks)
        }.values.flatMap { meetings ->
            val first = meetings.first()
            contiguousRuns(meetings.map(LegacyActivity::section).distinct()).flatMap { sections ->
                buildCourses(
                    name = first.name, teacher = first.teacher, room = first.room, day = first.day,
                    sectionRuns = listOf(sections), weeks = first.weeks
                )
            }
        }
    }

    private fun parseZhengfang(data: JsonElement?, html: String): List<Course> {
        if (data == null) return parseHtml(AcademicSystem.ZHENGFANG_HTML, html)
        val candidates = collectObjects(data).filter { row ->
            val hasName = row.hasAny("kcmc", "kcm", "courseName")
            val timeFieldCount = listOf(
                row.hasAny("xqj", "xq", "skxq", "day", "weekday"),
                row.hasAny("zcd", "zc", "skzc", "weeks"),
                row.hasAny("jcs", "jc", "sksj", "ksjc", "skjc", "startSection")
            ).count { it }
            hasName && timeFieldCount > 0 || timeFieldCount >= 2
        }
        if (candidates.isEmpty()) return parseHtml(AcademicSystem.ZHENGFANG_HTML, html)
        candidates.forEach { row ->
            if (row.firstString("kcmc", "kcm", "courseName").isBlank()) {
                throw ImportFormatException("正方课程名称缺失", AcademicImportErrorCode.PARTIAL_PARSE)
            }
            if (row.firstString("xqj", "xq", "skxq", "day", "weekday").isBlank()) {
                throw ImportFormatException("正方课程星期缺失", AcademicImportErrorCode.MISSING_DAY)
            }
            if (row.firstString("zcd", "zc", "skzc", "weeks").isBlank()) {
                throw ImportFormatException("正方课程周次缺失", AcademicImportErrorCode.MISSING_WEEK)
            }
            if (row.firstString("jcs", "jc", "sksj", "ksjc", "skjc", "startSection").isBlank()) {
                throw ImportFormatException("正方课程节次缺失", AcademicImportErrorCode.MISSING_SECTION)
            }
        }
        return candidates.flatMap { row ->
            val name = row.firstString("kcmc", "kcm", "courseName")
            val sectionsText = row.firstString("jcs", "jc", "sksj")
            val sections = if (sectionsText.isNotBlank()) {
                explicitSectionRuns(sectionsText)
            } else {
                val start = row.firstInt("ksjc", "skjc", "startSection")
                    ?: throw ImportFormatException("$name 的开始节次缺失")
                val end = row.firstInt("jsjc", "endSection")
                    ?: row.firstInt("cxjc", "sectionCount")?.takeIf { it > 0 }?.let { start + it - 1 }
                    ?: throw ImportFormatException("$name 的结束节次或持续节数缺失")
                listOf(start..end)
            }
            if (sections.isEmpty()) throw ImportFormatException("$name 的节次无法确认")
            buildCourses(
                name = name,
                teacher = row.firstString("xm", "jsxm", "jsmc", "teacher"),
                room = row.firstString("cdmc", "jxcdmc", "jasmc", "classroom", "room"),
                day = parseDay(row.firstString("xqj", "xq", "skxq", "day", "weekday")),
                sectionRuns = sections,
                weeks = explicitWeeks(row.firstString("zcd", "zc", "skzc", "weeks"))
            )
        }
    }

    private fun parseXbell(root: JsonElement): List<Course> {
        val rows = collectObjects(root).filter { it.has("kcmc") && it.has("xqj") && it.has("djj") && it.has("qmz") }
        if (rows.isEmpty()) throw ImportFormatException("未找到凌展课表数据")
        return rows.flatMap { row ->
            val weekType = when (row.int("dsz")) {
                0 -> 2
                1 -> 1
                2 -> 0
                else -> throw ImportFormatException("凌展课程单双周类型缺失")
            }
            val weeks = explicitWeeks(row.string("qmz")).filter { week ->
                weekType == 0 || weekType == 1 && week % 2 == 1 || weekType == 2 && week % 2 == 0
            }
            val section = row.int("djj") ?: throw ImportFormatException("凌展课程节次缺失")
            buildCourses(row.string("kcmc"), row.string("jsxm"), row.string("skdd"), row.int("xqj"),
                listOf(section..section), weeks)
        }
    }

    private fun parseChaoxing(root: JsonElement): List<Course> {
        val rows = collectObjects(root).filter { it.has("kcmc") && it.has("xq") && it.has("djc") && it.has("zc") }
        if (rows.isEmpty()) throw ImportFormatException("未找到超星教务课表数据")
        return rows.flatMap { row ->
            val weekType = row.int("zctype")?.takeIf { it in 0..2 } ?: 0
            val weeks = explicitWeeks(row.string("zc")).filter { week ->
                weekType == 0 || weekType == 1 && week % 2 == 1 || weekType == 2 && week % 2 == 0
            }
            val section = row.int("djc") ?: throw ImportFormatException("超星课程节次缺失")
            buildCourses(row.string("kcmc"), row.string("tmc"), row.string("croommc"), row.int("xq"),
                listOf(section..section), weeks)
        }
    }

    private fun parseChaoxingShare(root: JsonElement): List<Course> {
        val rows = collectObjects(root).filter {
            it.has("name") && it.has("dayOfWeek") && it.has("beginNumber") && it.has("length") && it.has("weeks")
        }
        if (rows.isEmpty()) throw ImportFormatException("未找到超星分享课表中的课程")
        return rows.flatMap { row ->
            val start = row.int("beginNumber") ?: throw ImportFormatException("超星分享课程节次缺失")
            val length = row.int("length") ?: throw ImportFormatException("超星分享课程时长缺失")
            buildCourses(row.string("name"), row.string("teacherName"),
                row.string("location") + row.string("onlineLocation"), row.int("dayOfWeek"),
                listOf(start..(start + length - 1)), explicitWeeks(row.string("weeks")))
        }
    }

    private fun parseAic(html: String): List<Course> {
        val document = checkedDocument(html)
        val table = document.selectFirst("table#table")
            ?: throw ImportFormatException("未找到 AIC 学期课表")
        val rows = table.select("tr").toList().filter { it.closest("table") === table }
        val courses = mutableListOf<Course>()
        rows.drop(1).forEachIndexed { rowIndex, row ->
            row.children().toList().filter { it.tagName() in setOf("td", "th") }.drop(1)
                .forEachIndexed { dayIndex, cell ->
                    cell.select(".courseInfo").forEach { block ->
                        val name = block.children().firstOrNull()?.text().orEmpty().trim()
                        val weeks = block.selectFirst(".weekDetail")?.text()?.let(::explicitWeeks)
                            ?: throw ImportFormatException("AIC 课程周次缺失；请切换到学期课表")
                        buildCourses(name, block.select(".teacher").text(), block.select(".place").text(),
                            dayIndex + 1, listOf((rowIndex + 1)..(rowIndex + 1)), weeks).also(courses::addAll)
                    }
                }
        }
        if (courses.isEmpty()) throw ImportFormatException("未找到 AIC 课程；请切换到学期课表")
        return courses
    }

    private fun parseHtml(system: AcademicSystem, html: String): List<Course> {
        val document = checkedDocument(html)
        val structured = parseStructuredTables(document)
        if (structured.isNotEmpty()) return structured
        val grid = parseGridTables(document, system)
        if (grid.isNotEmpty()) return grid
        throw ImportFormatException("当前页面没有完整的课程、星期、节次和周次，请按提示打开学期课表")
    }

    private fun parseStructuredTables(document: Document): List<Course> {
        document.select("table").forEach tableLoop@ { table ->
            val rows = table.select("tr").toList().filter { it.closest("table") === table }
            val headerIndex = rows.indexOfFirst { row ->
                val headers = directCells(row).map { normalizedHeader(it.text()) }
                headers.any { it in COURSE_HEADERS } && headers.any { it in DAY_HEADERS } &&
                    headers.any { it in WEEK_HEADERS } && headers.any { it in SECTION_HEADERS || it in START_SECTION_HEADERS }
            }
            if (headerIndex < 0) return@tableLoop
            val header = directCells(rows[headerIndex]).map { normalizedHeader(it.text()) }
            val nameAt = header.indexOfFirst { it in COURSE_HEADERS }
            val dayAt = header.indexOfFirst { it in DAY_HEADERS }
            val weeksAt = header.indexOfFirst { it in WEEK_HEADERS }
            val sectionAt = header.indexOfFirst { it in SECTION_HEADERS }
            val startAt = header.indexOfFirst { it in START_SECTION_HEADERS }
            val endAt = header.indexOfFirst { it in END_SECTION_HEADERS }
            val countAt = header.indexOfFirst { it in SECTION_COUNT_HEADERS }
            val teacherAt = header.indexOfFirst { it in TEACHER_HEADERS }
            val roomAt = header.indexOfFirst { it in ROOM_HEADERS }
            val parsed = mutableListOf<Course>()
            rows.drop(headerIndex + 1).forEach rowLoop@ { row ->
                val cells = directCells(row).map(Element::text)
                val name = cells.at(nameAt)
                if (name.isBlank()) return@rowLoop
                val startEnd = when {
                    sectionAt >= 0 -> explicitSectionRuns(cells.at(sectionAt)).singleOrNull()
                    startAt >= 0 -> {
                        val start = firstInt(cells.at(startAt))
                            ?: throw ImportFormatException("$name 的开始节次缺失")
                        val end = firstInt(cells.at(endAt))
                            ?: firstInt(cells.at(countAt))?.takeIf { it > 0 }?.let { start + it - 1 }
                            ?: throw ImportFormatException("$name 的结束节次或持续节数缺失")
                        start..end
                    }
                    else -> null
                } ?: throw ImportFormatException("$name 的节次无法确认")
                parsed += buildCourses(name, cells.at(teacherAt), cells.at(roomAt), parseDay(cells.at(dayAt)),
                    listOf(startEnd), explicitWeeks(cells.at(weeksAt)))
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun parseGridTables(document: Document, system: AcademicSystem): List<Course> {
        val selectors = when (system) {
            AcademicSystem.ZHENGFANG_HTML -> "#Table1,#kbgrid_table,#table1,#sycjlrtabGrid"
            AcademicSystem.VATUU_HTML -> "#table_border,.table_border"
            AcademicSystem.UMOOC_HTML -> "#timetable table,table#timetable,.timetable table"
            AcademicSystem.SOUTH_SOFT_HTML -> "#kb,table.kb"
            AcademicSystem.YILIAN_HTML -> "[class*=CourseTimeTable][class*=timeTable] table,table[class*=timeTable]"
            AcademicSystem.KINGOSOFT_HTML -> ".pageRpt,#reportArea,#mytable,#kbDiv,table"
            else -> "table"
        }
        val courses = mutableListOf<Course>()
        var unreadable = 0
        document.select(selectors).toList().filter { it.tagName() == "table" }.forEach tableLoop@ { table ->
            val rows = table.select("tr").toList().filter { it.closest("table") === table }
            val headerIndex = rows.indexOfFirst { row -> directCells(row).count { parseDay(it.text()) != null } >= 2 }
            if (headerIndex < 0) return@tableLoop
            val dayColumns = directCells(rows[headerIndex]).mapIndexedNotNull { index, cell ->
                parseDay(cell.text())?.let { index to it }
            }.toMap()
            rows.drop(headerIndex + 1).forEach rowLoop@ { row ->
                val cells = directCells(row)
                val fallback = cells.mapIndexedNotNull { index, cell ->
                    if (index in dayColumns) null else sectionRuns(cell.text()).firstOrNull()
                }.firstOrNull()
                dayColumns.forEach dayLoop@ { (column, day) ->
                    val cell = cells.getOrNull(column) ?: return@dayLoop
                    val blocks = leafCourseBlocks(cell)
                    blocks.forEach blockLoop@ { block ->
                        if (!containsWeek(block.text())) return@blockLoop
                        try {
                            courses += parseGridBlock(block, day, fallback)
                        } catch (_: ImportFormatException) {
                            unreadable++
                        }
                    }
                }
            }
        }
        if (unreadable > 0) {
            throw ImportFormatException("有 $unreadable 个课程块的周次或节次无法确认，本次未导入")
        }
        return courses
    }

    private fun parseGridBlock(block: Element, day: Int, fallback: IntRange?): List<Course> {
        val titled = block.select("[title],[data-original-title],[aria-describedby]")
        fun titledText(labels: Set<String>): String = titled.firstOrNull { element ->
            val label = element.attr("title").ifBlank { element.attr("data-original-title") }
                .ifBlank { element.attr("aria-describedby").substringAfterLast('_') }
            normalizedHeader(label) in labels
        }?.text().orEmpty().trim()
        val lines = block.wholeText().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val weekLine = titled.firstOrNull { normalizedHeader(it.attr("title")) in WEEK_HEADERS }?.text()
            ?: lines.firstOrNull(::containsWeek)
            ?: throw ImportFormatException("课程周次缺失")
        val weeks = explicitWeeks(extractWeekExpression(weekLine))
        val sections = sectionRuns(weekLine).ifEmpty { fallback?.let(::listOf).orEmpty() }
        if (sections.isEmpty()) throw ImportFormatException("课程节次缺失")
        val name = titledText(COURSE_HEADERS).ifBlank {
            block.selectFirst(".title")?.text().orEmpty().trim()
        }.ifBlank {
            lines.firstOrNull { line ->
                !containsWeek(line) && sectionRuns(line).isEmpty() && !isCourseCode(line) &&
                    !line.startsWith("教师") && !line.startsWith("老师") && !line.startsWith("地点") && !line.startsWith("教室")
            }.orEmpty().substringAfter(':').substringAfter('：').trim()
        }
        if (name.isBlank()) throw ImportFormatException("课程名称缺失")
        val weekIndex = lines.indexOf(weekLine)
        val teacher = titledText(TEACHER_HEADERS).ifBlank { labeled(block.text(), "教师|老师|任课教师") }
            .ifBlank { lines.getOrNull(weekIndex - 1).takeUnless { it == name || it?.let(::containsWeek) == true }.orEmpty() }
        val room = titledText(ROOM_HEADERS).ifBlank { labeled(block.text(), "教室|地点|上课地点") }
            .ifBlank { lines.drop((weekIndex + 1).coerceAtLeast(0)).firstOrNull { sectionRuns(it).isEmpty() }.orEmpty() }
        return buildCourses(name, teacher, room, day, sections, weeks)
    }

    private fun buildCourses(
        name: String,
        teacher: String,
        room: String,
        day: Int?,
        sectionRuns: List<IntRange>,
        weeks: List<Int>
    ): List<Course> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) throw ImportFormatException("课程名称缺失")
        if (day !in 1..7) throw ImportFormatException("$cleanName 的星期缺失或超出范围")
        if (weeks.isEmpty()) throw ImportFormatException("$cleanName 的周次为空")
        if (sectionRuns.isEmpty() || sectionRuns.any { it.first !in 1..30 || it.last !in it.first..30 }) {
            throw ImportFormatException("$cleanName 的节次缺失或超出范围")
        }
        return sectionRuns.flatMap { sections ->
            compressImportWeeks(weeks).map { range ->
                Course(courseName = cleanName, teacher = teacher.trim(), classroom = room.trim(),
                    dayOfWeek = day!!, startSection = sections.first, endSection = sections.last,
                    startWeek = range.start, endWeek = range.end, weekType = range.weekType,
                    colorIndex = Math.floorMod(cleanName.hashCode(), 16))
            }
        }
    }

    private fun explicitWeeks(value: String): List<Int> {
        if (value.isBlank()) throw ImportFormatException("课程周次缺失")
        val normalized = value.replace(Regex("""[\[\]{}]"""), "").trim()
        if (normalized.matches(Regex("[01]{1,53}"))) return bitmapWeeks(normalized)
        return QiangzhiScheduleParser(totalWeeks).parseWeeks(normalized)
    }

    private fun bitmapWeeks(value: String, allowLeadingZero: Boolean = false): List<Int> {
        var bitmap = value.trim()
        if (!bitmap.matches(Regex("[01]{1,53}"))) throw ImportFormatException("课程周次位图无效")
        // EAMS/ShuWei vaildWeeks uses index 0 as a sentinel; real weeks start at index 1.
        if (allowLeadingZero && bitmap.startsWith('0')) bitmap = bitmap.drop(1)
        if (bitmap.length > 52) throw ImportFormatException("课程周次超出支持范围")
        return bitmap.mapIndexedNotNull { index, bit -> (index + 1).takeIf { bit == '1' } }
            .ifEmpty { throw ImportFormatException("课程周次为空") }
    }

    private fun sectionRuns(value: String): List<IntRange> {
        val match = Regex("(?:第|\\[)?\\s*(\\d{1,2}(?:\\s*[-~～—–－至,，、]\\s*\\d{1,2})*)\\s*节").find(value)
            ?: value.trim().takeIf { it.matches(Regex("\\d{1,2}")) }?.let { return listOf(it.toInt()..it.toInt()) }
            ?: return emptyList()
        return contiguousRuns(explicitNumbers(match.groupValues[1], 30))
    }

    private fun explicitSectionRuns(value: String): List<IntRange> {
        sectionRuns(value).takeIf(List<IntRange>::isNotEmpty)?.let { return it }
        val clean = value.trim().replace('【', '[').replace('】', ']')
            .trim('[', ']').removePrefix("第").removeSuffix("节").trim()
        if (!clean.matches(Regex("\\d{1,2}(?:\\s*[-~～—–－至,，、]\\s*\\d{1,2})*"))) return emptyList()
        return contiguousRuns(explicitNumbers(clean, 30))
    }

    private fun explicitNumbers(value: String, maximum: Int): List<Int> {
        val result = sortedSetOf<Int>()
        value.replace(Regex("[~～—–－至]"), "-").replace('，', ',').replace('、', ',')
            .split(',').filter(String::isNotBlank).forEach { part ->
                val range = Regex("\\s*(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?\\s*").matchEntire(part)
                    ?: throw ImportFormatException("无法识别节次")
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toIntOrNull() ?: start
                if (start !in 1..maximum || end !in start..maximum) throw ImportFormatException("节次超出范围")
                result += start..end
            }
        return result.toList()
    }

    private fun contiguousRuns(values: List<Int>): List<IntRange> = values.sorted().fold(mutableListOf()) { runs, value ->
        val last = runs.lastOrNull()
        if (last != null && last.last + 1 == value) runs[runs.lastIndex] = last.first..value else runs += value..value
        runs
    }

    private fun extractWeekExpression(value: String): String {
        val normalized = value.replace('（', '(').replace('）', ')').replace('，', ',').replace('；', ';')
        val sectionStart = Regex("(?:第|\\[)?\\s*\\d{1,2}(?:\\s*[-,]\\s*\\d{1,2})*\\s*节").find(normalized)?.range?.first
        val withoutSections = if (sectionStart == null) normalized else normalized.substring(0, sectionStart)
        val start = withoutSections.indexOfFirst(Char::isDigit)
        if (start >= 0 && '周' in withoutSections.substring(start)) return withoutSections.substring(start).trim()
        return Regex("[单双全]周").find(withoutSections)?.value
            ?: throw ImportFormatException("课程周次缺失")
    }

    private fun parseDay(value: String): Int? {
        val text = value.trim().replace(Regex("\\s+"), "")
        Regex("(?:星期|周|礼拜)?([一二三四五六日天])").find(text)?.let {
            return "一二三四五六日".indexOf(it.groupValues[1].replace('天', '日')).plus(1).takeIf { day -> day > 0 }
        }
        return text.toIntOrNull()?.takeIf { it in 1..7 }
    }

    private fun checkedDocument(html: String): Document {
        if (html.isBlank()) throw ImportFormatException("未读取到课表页面内容")
        return Jsoup.parse(html).also { document ->
            document.select("script,style,input,select,textarea,button,iframe,frame,object,embed,form").remove()
        }
    }

    private fun leafCourseBlocks(cell: Element): List<Element> {
        val leaves = cell.select("div").toList().filter { div ->
            containsWeek(div.text()) && div.select("div").none { it !== div && containsWeek(it.text()) }
        }
        return leaves.ifEmpty { listOf(cell) }
    }

    private fun containsWeek(value: String): Boolean = '周' in value &&
        (value.any(Char::isDigit) || value.contains('单') || value.contains('双') || value.contains('全'))

    private fun directCells(row: Element): List<Element> = row.children().toList().filter { it.tagName() in setOf("th", "td") }
    private fun normalizedHeader(value: String): String = value.trim().lowercase().replace(Regex("[\\s_\\-/：:]"), "")
    private fun List<String>.at(index: Int): String = getOrNull(index).orEmpty().trim()
    private fun firstInt(value: String): Int? = Regex("\\d+").find(value)?.value?.toIntOrNull()
    private fun labeled(value: String, labels: String): String = Regex("(?:$labels)\\s*[:：]\\s*([^\\s,，;；]+)")
        .find(value)?.groupValues?.getOrNull(1).orEmpty()
    private fun isCourseCode(value: String) = value.matches(Regex("(?=.*\\d)[A-Za-z0-9_.-]{6,}"))

    private fun requireData(data: JsonElement?, label: String): JsonElement = data
        ?: throw ImportFormatException("未读取到$label；请刷新课表页面、等待加载完成后重试")

    private fun collectObjects(root: JsonElement): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var visited = 0
        fun visit(element: JsonElement?, depth: Int) {
            if (element == null || element.isJsonNull || depth > 12 || ++visited > 12_000) return
            when {
                element.isJsonObject -> {
                    result += element.asJsonObject
                    element.asJsonObject.entrySet().forEach { visit(it.value, depth + 1) }
                }
                element.isJsonArray -> element.asJsonArray.forEach { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        return result
    }

    private fun extractJavascriptArray(html: String, variable: String): JsonElement? {
        val assignment = Regex("(?:var|let|const)\\s+${Regex.escape(variable)}\\s*=\\s*", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        val start = assignment.range.last + 1
        if (start !in html.indices || html[start] != '[') return null
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until html.length) {
            val character = html[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                continue
            }
            when (character) {
                '\'', '"', '`' -> quote = character
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JsonParser.parseString(html.substring(start, index + 1)) }.getOrNull()
                    }
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonObject.string(key: String): String = get(key)?.takeUnless(JsonElement::isJsonNull)?.let {
        runCatching { it.asString }.getOrNull()
    }.orEmpty().trim()
    private fun JsonObject.int(key: String): Int? = string(key).toIntOrNull()
    private fun JsonObject.firstString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> string(key).takeIf(String::isNotBlank) }.orEmpty()
    private fun JsonObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> string(key).toIntOrNull() }
    private fun JsonObject.hasAny(vararg keys: String): Boolean = keys.any(::has)
    private fun JsonObject.array(key: String): List<JsonElement> = get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()

    private fun courseKey(course: Course): List<Any> = listOf(course.courseName, course.teacher, course.classroom,
        course.dayOfWeek, course.startSection, course.endSection, course.startWeek, course.endWeek, course.weekType)

    companion object {
        private val COURSE_HEADERS = setOf("课程", "课程名", "课程名称", "kcmc", "kcm", "course", "coursename")
        private val TEACHER_HEADERS = setOf("教师", "老师", "任课教师", "授课教师", "代课教师", "teacher", "jsxm")
        private val ROOM_HEADERS = setOf("教室", "地点", "上课地点", "教学楼", "classroom", "room", "location", "sksjdd")
        private val DAY_HEADERS = setOf("星期", "周几", "上课日", "day", "weekday", "skxq")
        private val WEEK_HEADERS = setOf("周次", "上课周", "weeks", "week", "skzc", "zc")
        private val SECTION_HEADERS = setOf("节次", "上课节次", "section", "sections", "jcdm2", "sksj")
        private val START_SECTION_HEADERS = setOf("开始节次", "起始节次", "startsection", "skjc")
        private val END_SECTION_HEADERS = setOf("结束节次", "endsection", "jsjc")
        private val SECTION_COUNT_HEADERS = setOf("节数", "持续节次", "sectioncount", "cxjc")
        private val CURRENT_SHUWEI_FIELDS = setOf(
            "courseName", "weekday", "startUnit", "endUnit", "weekIndexes"
        )
    }
}
