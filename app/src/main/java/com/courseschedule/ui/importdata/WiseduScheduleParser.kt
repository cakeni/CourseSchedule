package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun normalizeWiseduTerm(raw: String): String? {
    val direct = Regex("(20\\d{2})\\s*[-_/]\\s*(20\\d{2})\\s*[-_/]\\s*([1-3])")
        .find(raw)
    if (direct != null) {
        return "${direct.groupValues[1]}-${direct.groupValues[2]}-${direct.groupValues[3]}"
    }

    val display = Regex(
        "(20\\d{2})\\s*[-~～—–－至/]\\s*(20\\d{2})\\s*学年[\\s\\S]{0,16}?" +
            "(秋季|春季|夏季|第一|第二|第三|1|2|3)\\s*学期"
    ).find(raw)
    if (display != null) {
        val semester = when (display.groupValues[3]) {
            "秋季", "第一", "1" -> 1
            "春季", "第二", "2" -> 2
            "夏季", "第三", "3" -> 3
            else -> return null
        }
        return "${display.groupValues[1]}-${display.groupValues[2]}-$semester"
    }

    val compact = Regex("(?<!\\d)(20\\d{2})(20\\d{2})([1-3])(?!\\d)").find(raw)
        ?: return null
    return "${compact.groupValues[1]}-${compact.groupValues[2]}-${compact.groupValues[3]}"
}

/**
 * Parser for common Wisedu/金智 timetable responses.
 *
 * The school WebView performs the authenticated request. This class only
 * converts the returned JSON payload into the app's existing import model.
 */
class WiseduScheduleParser(
    private val defaultTotalWeeks: Int,
    private val requireExplicitTimes: Boolean = false
) {

    fun parse(text: String): ParsedImport {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw ImportFormatException("教务系统返回的数据格式不正确") }
        val payload = root.objectOrNull("payload") ?: root
        val rows = findRows(payload)
        if (rows.isEmpty()) {
            throw ImportFormatException("教务系统没有返回课程数据，请确认当前学期已有课表")
        }

        val courses = rows.flatMap(::parseRow)
        if (courses.isEmpty()) {
            throw ImportFormatException("已获取教务数据，但没有识别到有效课程")
        }
        return ParsedImport(
            courses = courses,
            sourceLabel = "西南石油大学教务系统"
        )
    }

    private fun findRows(payload: JsonObject): List<JsonObject> {
        val direct = payload.objectOrNull("datas")
            ?.objectOrNull("xskcb")
            ?.arrayOrNull("rows")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()
        if (direct.isNotEmpty()) return direct

        val fallback = payload.objectOrNull("xskcb")
            ?.arrayOrNull("rows")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()
        if (fallback.isNotEmpty()) return fallback

        val rootRows = payload.arrayOrNull("rows")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()
        if (rootRows.isNotEmpty()) return rootRows

        return findCourseArray(payload, depth = 0, visited = intArrayOf(0)).orEmpty()
    }

    private fun findCourseArray(element: JsonElement, depth: Int, visited: IntArray): List<JsonObject>? {
        if (depth > 10 || ++visited[0] > 12_000 || element.isJsonNull || element.isJsonPrimitive) return null
        if (element.isJsonArray) {
            val rows = element.asJsonArray
                .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                .filter { row ->
                    (row.has("KCM") || row.has("KCMC")) && row.has("SKXQ") && row.has("KSJC") ||
                        row.has("KCMC") && row.has("PKSJDD") ||
                        row.has("kcmc") && row.has("xqj") && row.has("djj") && row.has("qmz")
                }
            if (rows.isNotEmpty()) return rows
            return element.asJsonArray.firstNotNullOfOrNull { child ->
                findCourseArray(child, depth + 1, visited)
            }
        }
        return element.asJsonObject.entrySet().firstNotNullOfOrNull { (_, child) ->
            findCourseArray(child, depth + 1, visited)
        }
    }

    private fun parseRow(row: JsonObject): List<Course> {
        if (row.has("kcmc")) return parseMobileRow(row)
        if (row.has("KCMC") && row.has("PKSJDD")) return parsePackedRow(row)
        val name = row.string("KCM").ifBlank { row.string("KCMC") }.trim()
        fun number(key: String): Int? = if (requireExplicitTimes) row.string(key).trim().toIntOrNull()
            else row.string(key).firstInteger()
        val day = number("SKXQ")
        val startSection = number("KSJC")
        val endSection = number("JSJC") ?: startSection.takeUnless { requireExplicitTimes }
        if (name.isBlank() || day == null || day !in 1..7 || startSection == null || endSection == null) {
            if (requireExplicitTimes) throw ImportFormatException("课程名称、星期或节次字段不完整，本次未导入；此页面可能不是受支持的金智课表版本")
            return emptyList()
        }

        val teacher = row.string("SKJS").trim()
        val classroom = row.string("JASMC").ifBlank { row.string("SKDD") }.trim()
        val weekPattern = row.string("SKZC").trim()
        val weekGroups = compressImportWeeks(parseWeeks(weekPattern))
        val colorIndex = Math.floorMod(name.hashCode(), 16)

        return weekGroups.map { group ->
            Course(
                courseName = name,
                teacher = teacher,
                classroom = classroom,
                dayOfWeek = day,
                startSection = startSection,
                endSection = endSection,
                startWeek = group.start,
                endWeek = group.end,
                weekType = group.weekType,
                colorIndex = colorIndex
            )
        }
    }

    /** Some xkjglapp responses pack every meeting into a single PKSJDD string. */
    private fun parsePackedRow(row: JsonObject): List<Course> {
        val name = row.string("KCMC").trim()
        val meetings = row.string("PKSJDD").split(Regex("[;；\\n]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (name.isBlank() || meetings.isEmpty()) {
            throw ImportFormatException("金智课表的课程名称或上课安排字段不完整")
        }
        val teacher = row.string("RKJS").ifBlank { row.string("SKJS") }.trim()
        val fallbackRoom = row.string("JASMC").ifBlank { row.string("SKDD") }.trim()
        val dayPattern = Regex("(?:星期|周)([一二三四五六日天七])")
        val sectionPattern = Regex("(\\d{1,2})(?:\\s*[-~～—–－至]\\s*(\\d{1,2}))?\\s*节")
        val weekPattern = Regex(
            "(?:\\d{1,2}\\s*(?:[-~～—–－至]\\s*\\d{1,2})?\\s*[,，、]?\\s*)+" +
                "[单双]?\\s*周(?:\\s*[（(][单双][）)])?"
        )
        val colorIndex = Math.floorMod(name.hashCode(), 16)
        return meetings.flatMap { meeting ->
            val dayText = dayPattern.find(meeting)?.groupValues?.get(1)
                ?: throw ImportFormatException("金智课表的星期字段不完整")
            val day = when (dayText) {
                "天", "七" -> 7
                else -> "一二三四五六日".indexOf(dayText).plus(1)
            }
            val sectionMatch = sectionPattern.find(meeting)
                ?: throw ImportFormatException("金智课表的节次字段不完整")
            val startSection = sectionMatch.groupValues[1].toInt()
            val endSection = sectionMatch.groupValues[2].toIntOrNull() ?: startSection
            val weeks = weekPattern.findAll(meeting)
                .flatMap { match -> parseWeeks(match.value).asSequence() }
                .distinct()
                .sorted()
                .toList()
            if (day !in 1..7 || startSection !in 1..30 || endSection !in startSection..30 || weeks.isEmpty()) {
                throw ImportFormatException("金智课表的星期、节次或周次字段不完整")
            }
            val room = meeting.substring(sectionMatch.range.last + 1)
                .trim().trimStart(']', '】', '、', ',', '，').trim()
                .ifBlank { fallbackRoom }
            compressImportWeeks(weeks).map { group ->
                Course(
                    courseName = name,
                    teacher = teacher,
                    classroom = room,
                    dayOfWeek = day,
                    startSection = startSection,
                    endSection = endSection,
                    startWeek = group.start,
                    endWeek = group.end,
                    weekType = group.weekType,
                    colorIndex = colorIndex
                )
            }
        }
    }

    private fun parseMobileRow(row: JsonObject): List<Course> {
        val name = row.string("kcmc").trim()
        val day = row.string("xqj").trim().toIntOrNull()
        val section = row.string("djj").trim().toIntOrNull()
        val weeks = parseWeeks(row.string("qmz")).filter { week ->
            when (row.string("dsz").toIntOrNull()) {
                0 -> week % 2 == 0
                1 -> week % 2 == 1
                2 -> true
                else -> throw ImportFormatException("金智移动课表的单双周字段不完整")
            }
        }
        if (name.isBlank() || day !in 1..7 || section !in 1..30 || weeks.isEmpty()) {
            throw ImportFormatException("金智移动课表的课程名称、星期、节次或周次字段不完整")
        }
        return compressImportWeeks(weeks).map { group ->
            Course(
                courseName = name,
                teacher = row.string("jsxm").trim(),
                classroom = row.string("skdd").trim(),
                dayOfWeek = day!!,
                startSection = section!!,
                endSection = section,
                startWeek = group.start,
                endWeek = group.end,
                weekType = group.weekType,
                colorIndex = Math.floorMod(name.hashCode(), 16)
            )
        }
    }

    private fun parseWeeks(pattern: String): List<Int> {
        if (requireExplicitTimes && pattern.isBlank()) throw ImportFormatException("课程周次缺失，本次未导入")
        if (pattern.isBlank()) return (1..defaultTotalWeeks.coerceAtLeast(1)).toList()

        val normalized = pattern.trim()
        if (normalized.matches(Regex("[01]+"))) {
            if (requireExplicitTimes && normalized.length > 52) throw ImportFormatException("课程周次超出支持范围")
            return normalized.mapIndexedNotNull { index, value ->
                if (value == '1') index + 1 else null
            }
        }
        if (requireExplicitTimes) return QiangzhiScheduleParser(defaultTotalWeeks).parseWeeks(normalized)

        val explicit = mutableSetOf<Int>()
        Regex("(\\d+)\\s*(?:[-~～—–－]|至)\\s*(\\d+)").findAll(normalized).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (start > 0 && end >= start) explicit += start..end
        }
        Regex("\\d+").findAll(normalized)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it > 0 }
            .forEach(explicit::add)

        val hasOddMarker = normalized.contains('单')
        val hasEvenMarker = normalized.contains('双')
        val candidates = explicit.ifEmpty {
            (1..defaultTotalWeeks.coerceAtLeast(1)).toMutableSet()
        }
        return candidates.filter { week ->
            when {
                hasOddMarker && !hasEvenMarker -> week % 2 == 1
                hasEvenMarker && !hasOddMarker -> week % 2 == 0
                else -> true
            }
        }.sorted()
    }

    private fun JsonObject.string(key: String): String = get(key)
        ?.takeUnless { it.isJsonNull }
        ?.let { element -> runCatching { element.asString }.getOrNull() }
        .orEmpty()

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject

    private fun JsonObject.arrayOrNull(key: String) = get(key)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray

    private fun String.firstInteger(): Int? = Regex("\\d+").find(this)?.value?.toIntOrNull()
}
