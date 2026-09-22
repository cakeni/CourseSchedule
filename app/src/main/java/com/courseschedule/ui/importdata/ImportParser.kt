package com.courseschedule.ui.importdata

import com.courseschedule.data.backup.ScheduleBackup
import com.courseschedule.data.backup.SemesterSnapshot
import com.courseschedule.data.backup.SettingsSnapshot
import com.courseschedule.data.entity.Course
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.InputStream

data class ParsedImport(
    val courses: List<Course>,
    val semester: SemesterSnapshot? = null,
    val settings: SettingsSnapshot? = null,
    val sourceLabel: String = "",
    val explicitReminderCourses: Set<Course> = emptySet()
) {
    fun withDefaultReminder(course: Course, minutes: Int): Course =
        if (course in explicitReminderCourses) course else course.copy(reminderMinutes = minutes)
}

enum class AcademicImportErrorCode {
    NO_TIMETABLE,
    UNSUPPORTED_VARIANT,
    MISSING_DAY,
    MISSING_SECTION,
    MISSING_WEEK,
    PARTIAL_PARSE,
    AMBIGUOUS_VARIANT,
    FRAME_BLOCKED,
    CAPTURE_MISS,
    PAYLOAD_TOO_LARGE,
    UNTRUSTED_SOURCE
}

class ImportFormatException(
    message: String,
    val errorCode: AcademicImportErrorCode? = null
) : IllegalArgumentException(message)

class ImportParser(private val defaultTotalWeeks: Int) {

    private val gson = Gson()

    fun parseJson(text: String): ParsedImport {
        val root = runCatching { JsonParser.parseString(text) }
            .getOrElse { throw ImportFormatException("JSON 文件格式不正确") }
        if (root.isJsonArray) {
            val type = object : TypeToken<List<Course>>() {}.type
            val courses: List<Course> = gson.fromJson(root, type) ?: emptyList()
            val explicit = courses.zip(root.asJsonArray).filter { (_, json) ->
                json.isJsonObject && json.asJsonObject.has("reminderMinutes")
            }.map { it.first }.toSet()
            return ParsedImport(courses = courses, sourceLabel = "JSON", explicitReminderCourses = explicit)
        }
        if (root.isJsonObject && root.asJsonObject.has("courses")) {
            val backup = runCatching { gson.fromJson(root, ScheduleBackup::class.java) }
                .getOrElse { throw ImportFormatException("备份文件内容不完整") }
            if (backup.schemaVersion > ScheduleBackup.CURRENT_SCHEMA_VERSION) {
                throw ImportFormatException("该备份来自更高版本的应用")
            }
            return ParsedImport(
                courses = backup.courses,
                semester = backup.semester,
                settings = backup.settings,
                sourceLabel = "完整备份",
                explicitReminderCourses = backup.courses.toSet()
            )
        }
        throw ImportFormatException("JSON 中没有课程列表")
    }

    fun parseCsv(text: String): ParsedImport {
        val rows = text.lineSequence()
            .filter { it.isNotBlank() }
            .map(::parseCsvRow)
            .toList()
        return parseTabularRows(rows, "CSV")
    }

    fun parseTabularRows(rows: List<List<String>>, sourceLabel: String): ParsedImport {
        val populatedRows = rows.filter { row -> row.any(String::isNotBlank) }
        if (populatedRows.isEmpty()) return ParsedImport(emptyList(), sourceLabel = sourceLabel)

        val headerIndex = populatedRows.indexOfFirst { row ->
            val header = row.map(::normalizeHeader)
            header.any { it in COURSE_HEADERS } &&
                header.any { it in DAY_HEADERS } &&
                header.any { it in SECTION_HEADERS || it in START_SECTION_HEADERS }
        }
        val courses = if (headerIndex >= 0) {
            parseStructuredRows(
                populatedRows[headerIndex].map(::normalizeHeader),
                populatedRows.drop(headerIndex + 1)
            )
        } else {
            parseTimetableGrid(populatedRows)
                .ifEmpty { populatedRows.mapNotNull(::parseLegacyCsvRow) }
        }
        return ParsedImport(courses, sourceLabel = sourceLabel)
    }

    fun parseText(text: String): ParsedImport {
        if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
            return parseJson(text)
        }
        val courses = text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = when {
                    line.contains('|') -> line.split('|')
                    line.contains('\t') -> line.split('\t')
                    line.contains(',') -> parseCsvRow(line)
                    else -> line.trim().split(Regex("\\s+"))
                }.map(String::trim)
                if (parts.any { normalizeHeader(it) in COURSE_HEADERS }) null
                else parseTextRow(parts)
            }
            .toList()
        return ParsedImport(courses, sourceLabel = "文本")
    }

    fun parseHtml(text: String): ParsedImport = parseHtmlDocument(Jsoup.parse(text))

    /** HTML exports can declare GBK/GB2312. Let Jsoup honor the charset before parsing. */
    fun parseHtml(input: InputStream): ParsedImport {
        val bytes = ByteArray(2_000_001)
        var size = 0
        while (size < bytes.size) {
            val read = input.read(bytes, size, bytes.size - size)
            if (read < 0) break
            if (read == 0) throw ImportFormatException("HTML 文件读取中断，请重新选择文件")
            size += read
        }
        if (size == bytes.size) throw ImportFormatException("HTML 文件过大，请只导出学期课表页面（不超过 2 MB）")
        return parseHtmlDocument(Jsoup.parse(ByteArrayInputStream(bytes, 0, size), null, ""))
    }

    private fun parseHtmlDocument(document: Document): ParsedImport {
        if (document.getElementById("kbtable") != null) {
            return QiangzhiScheduleParser(defaultTotalWeeks).parseDocument(document)
        }
        val parsed = mutableListOf<Course>()
        document.select("table").forEach { table ->
            val rows = table.select("tr")
            if (rows.isEmpty()) return@forEach
            val textRows = rows.map { row -> row.select("th,td").map { it.wholeText().trim() } }
            val headerIndex = textRows.indexOfFirst { row ->
                val normalized = row.map(::normalizeHeader)
                normalized.any { it in COURSE_HEADERS } &&
                    normalized.any { it in DAY_HEADERS } &&
                    normalized.any { it in SECTION_HEADERS || it in START_SECTION_HEADERS }
            }
            if (headerIndex >= 0) {
                parsed += parseStructuredRows(
                    textRows[headerIndex].map(::normalizeHeader),
                    textRows.drop(headerIndex + 1)
                )
            } else {
                parsed += parseTimetableGrid(textRows)
            }
        }
        if (parsed.isEmpty()) {
            throw ImportFormatException("未找到带星期和节次信息的课程表")
        }
        return ParsedImport(parsed, sourceLabel = "HTML")
    }

    private fun parseStructuredRows(header: List<String>, rows: List<List<String>>): List<Course> {
        val nameIndex = header.indexOfFirst { it in COURSE_HEADERS }
        val dayIndex = header.indexOfFirst { it in DAY_HEADERS }
        val sectionIndex = header.indexOfFirst { it in SECTION_HEADERS }
        val startSectionIndex = header.indexOfFirst { it in START_SECTION_HEADERS }
        val endSectionIndex = header.indexOfFirst { it in END_SECTION_HEADERS }
        if (nameIndex < 0 || dayIndex < 0 || (sectionIndex < 0 && startSectionIndex < 0)) {
            return emptyList()
        }
        val teacherIndex = header.indexOfFirst { it in TEACHER_HEADERS }
        val roomIndex = header.indexOfFirst { it in ROOM_HEADERS }
        val weeksIndex = header.indexOfFirst { it in WEEKS_HEADERS }
        val startWeekIndex = header.indexOfFirst { it in START_WEEK_HEADERS }
        val endWeekIndex = header.indexOfFirst { it in END_WEEK_HEADERS }
        val weekTypeIndex = header.indexOfFirst { it in WEEK_TYPE_HEADERS }

        return rows.mapNotNull { row ->
            val name = row.valueAt(nameIndex).trim()
            val day = parseDay(row.valueAt(dayIndex)) ?: return@mapNotNull null
            val sections = if (sectionIndex >= 0) {
                parseRange(row.valueAt(sectionIndex))
            } else {
                val start = row.valueAt(startSectionIndex).firstNumber()
                val end = row.valueAt(endSectionIndex).firstNumber() ?: start
                if (start == null || end == null) null else start to end
            } ?: return@mapNotNull null
            val weeks = when {
                weeksIndex >= 0 -> parseRange(row.valueAt(weeksIndex))
                startWeekIndex >= 0 -> {
                    val start = row.valueAt(startWeekIndex).firstNumber()
                    val end = row.valueAt(endWeekIndex).firstNumber() ?: start
                    if (start == null || end == null) null else start to end
                }
                else -> 1 to defaultTotalWeeks
            } ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            Course(
                courseName = name,
                teacher = row.valueAt(teacherIndex),
                classroom = row.valueAt(roomIndex),
                dayOfWeek = day,
                startSection = sections.first,
                endSection = sections.second,
                startWeek = weeks.first,
                endWeek = weeks.second,
                weekType = parseWeekType(
                    row.valueAt(weekTypeIndex).ifBlank { row.valueAt(weeksIndex) }
                )
            )
        }
    }

    private fun parseTimetableGrid(rows: List<List<String>>): List<Course> {
        val headerIndex = rows.indexOfFirst { row -> row.count { parseDay(it) != null } >= 2 }
        if (headerIndex < 0) return emptyList()
        val dayColumns = rows[headerIndex].mapIndexedNotNull { index, value ->
            parseDay(value)?.let { index to it }
        }
        val courses = mutableListOf<Course>()
        rows.drop(headerIndex + 1).forEach { row ->
            val sectionCell = row.take(dayColumns.minOfOrNull { it.first } ?: 1)
                .firstNotNullOfOrNull(::parseRange)
                ?: return@forEach
            dayColumns.forEach { (column, day) ->
                val cell = row.valueAt(column).trim()
                courses += parseGridCell(cell, day, sectionCell)
            }
        }
        return courses
    }

    private fun parseGridCell(
        text: String,
        day: Int,
        fallbackSections: Pair<Int, Int>
    ): List<Course> {
        if (text.isBlank() || text.trim() in setOf("-", "--", "无", "1")) return emptyList()
        return splitCourseRecords(text).flatMap { record ->
            val header = record.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                ?: return@flatMap emptyList()
            val hasCourseCode = COURSE_RECORD_START.containsMatchIn(header)
            val name = if (hasCourseCode) {
                header
                    .replaceFirst(Regex("^\\s*\\d{6,}\\s*-\\s*"), "")
                    .replace(Regex("\\s*\\[[^]]+]\\s*$"), "")
                    .trim()
            } else {
                header
            }
            if (name.isBlank()) return@flatMap emptyList()

            val sectionMatch = SECTION_AND_ROOM.find(record)
            val sections = sectionMatch?.let {
                it.groupValues[1].toInt() to it.groupValues[2].toInt()
            } ?: fallbackSections
            val room = sectionMatch?.groupValues?.getOrNull(3)
                ?.trim()
                ?.trimEnd(',', '，')
                .orEmpty()
                .ifBlank { extractLabeled(record, "教室|地点|校区") }
            val teacher = extractLabeled(record, "教师|老师")
            val weekRanges = parseWeekRanges(record).ifEmpty {
                listOf(1 to defaultTotalWeeks)
            }

            weekRanges.map { weeks ->
                Course(
                    courseName = name,
                    teacher = teacher,
                    classroom = room,
                    dayOfWeek = day,
                    startSection = sections.first,
                    endSection = sections.second,
                    startWeek = weeks.first,
                    endWeek = weeks.second,
                    weekType = parseWeekType(record)
                )
            }
        }
    }

    private fun splitCourseRecords(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        val starts = COURSE_RECORD_START.findAll(normalized).map { it.range.first }.toList()
        if (starts.isEmpty()) return listOf(normalized)
        return starts.mapIndexed { index, start ->
            normalized.substring(start, starts.getOrElse(index + 1) { normalized.length })
                .trim()
                .trimEnd(',', '，')
        }
    }

    private fun parseWeekRanges(text: String): List<Pair<Int, Int>> {
        val dayMarkerStart = DAY_MARKER.find(text)?.range?.first ?: text.length
        return WEEK_RANGE.findAll(text.substring(0, dayMarkerStart))
            .mapNotNull { match ->
                val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val end = match.groupValues[2].toIntOrNull() ?: start
                start to end
            }
            .distinct()
            .toList()
    }

    private fun parseLegacyCsvRow(parts: List<String>): Course? {
        if (parts.size < 5) return null
        val day = parseDay(parts[3]) ?: return null
        val startSection = parts[4].firstNumber() ?: return null
        val endSection = parts.getOrNull(5)?.firstNumber() ?: startSection
        val startWeek = parts.getOrNull(6)?.firstNumber() ?: 1
        val endWeek = parts.getOrNull(7)?.firstNumber() ?: defaultTotalWeeks
        return Course(
            courseName = parts[0].trim(),
            teacher = parts.getOrElse(1) { "" }.trim(),
            classroom = parts.getOrElse(2) { "" }.trim(),
            dayOfWeek = day,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = parseWeekType(parts.getOrElse(8) { "" })
        ).takeIf { it.courseName.isNotBlank() }
    }

    private fun parseTextRow(parts: List<String>): Course? {
        if (parts.size < 3) return null
        val fullRow = parts.size >= 5
        val name = parts[0]
        val teacher = if (fullRow) parts.getOrElse(1) { "" } else ""
        val room = if (fullRow) parts.getOrElse(2) { "" } else ""
        val dayText = if (fullRow) parts[3] else parts[1]
        val sectionText = if (fullRow) parts[4] else parts[2]
        val weekText = if (fullRow) parts.getOrElse(5) { "" } else parts.getOrElse(3) { "" }
        val day = parseDay(dayText) ?: return null
        val sections = parseRange(sectionText) ?: return null
        val weeks = parseRange(weekText) ?: (1 to defaultTotalWeeks)
        return Course(
            courseName = name,
            teacher = teacher,
            classroom = room,
            dayOfWeek = day,
            startSection = sections.first,
            endSection = sections.second,
            startWeek = weeks.first,
            endWeek = weeks.second,
            weekType = parseWeekType(weekText)
        ).takeIf { it.courseName.isNotBlank() }
    }

    private fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += field.toString().trim()
                    field.clear()
                }
                else -> field.append(char)
            }
            index++
        }
        result += field.toString().trim()
        return result
    }

    private fun parseDay(text: String): Int? {
        val normalized = text.trim().lowercase()
        return when {
            Regex("^(周|星期|礼拜)?一$|^1$|^mon(day)?$").matches(normalized) -> 1
            Regex("^(周|星期|礼拜)?二$|^2$|^tue(sday)?$").matches(normalized) -> 2
            Regex("^(周|星期|礼拜)?三$|^3$|^wed(nesday)?$").matches(normalized) -> 3
            Regex("^(周|星期|礼拜)?四$|^4$|^thu(rsday)?$").matches(normalized) -> 4
            Regex("^(周|星期|礼拜)?五$|^5$|^fri(day)?$").matches(normalized) -> 5
            Regex("^(周|星期|礼拜)?六$|^6$|^sat(urday)?$").matches(normalized) -> 6
            Regex("^(周|星期|礼拜)?[日天]$|^7$|^sun(day)?$").matches(normalized) -> 7
            else -> null
        }
    }

    private fun parseRange(text: String): Pair<Int, Int>? {
        val numbers = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()
        return when {
            numbers.size >= 2 -> numbers[0] to numbers[1]
            numbers.size == 1 -> numbers[0] to numbers[0]
            else -> null
        }
    }

    private fun parseWeekType(text: String): Int = when {
        text.contains("单") || text.contains("odd", ignoreCase = true) -> 1
        text.contains("双") || text.contains("even", ignoreCase = true) -> 2
        else -> 0
    }

    private fun extractLabeled(text: String, label: String): String {
        return Regex("(?:$label)\\s*[:：]?\\s*([^\\s,，;；]+)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun normalizeHeader(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[\\s_/\\-]"), "")

    private fun List<String>.valueAt(index: Int): String =
        if (index in indices) this[index].trim() else ""

    private fun String.firstNumber(): Int? = Regex("\\d+").find(this)?.value?.toIntOrNull()

    companion object {
        private val COURSE_RECORD_START = Regex("(?m)^\\s*\\d{6,}\\s*-")
        private val DAY_MARKER = Regex("(?:星期|周)\\s*[一二三四五六日天1-7]")
        private val WEEK_RANGE = Regex("(\\d+)\\s*(?:[-~至]\\s*(\\d+))?\\s*周")
        private val SECTION_AND_ROOM = Regex(
            "第\\s*(\\d+)\\s*节\\s*[-~至]\\s*第?\\s*(\\d+)\\s*节\\s*([^,，\\n]*)"
        )
        private val COURSE_HEADERS = setOf("课程", "课程名", "课程名称", "course", "name")
        private val TEACHER_HEADERS = setOf("教师", "老师", "任课教师", "teacher")
        private val ROOM_HEADERS = setOf("教室", "地点", "上课地点", "classroom", "room", "location")
        private val DAY_HEADERS = setOf("星期", "周几", "上课日", "day", "weekday")
        private val SECTION_HEADERS = setOf("节次", "上课节次", "section", "sections")
        private val START_SECTION_HEADERS = setOf("开始节次", "起始节次", "startsection")
        private val END_SECTION_HEADERS = setOf("结束节次", "endsection")
        private val WEEKS_HEADERS = setOf("周次", "上课周", "weeks", "week")
        private val START_WEEK_HEADERS = setOf("开始周", "起始周", "startweek")
        private val END_WEEK_HEADERS = setOf("结束周", "endweek")
        private val WEEK_TYPE_HEADERS = setOf("单双周", "周类型", "weektype")
    }
}
