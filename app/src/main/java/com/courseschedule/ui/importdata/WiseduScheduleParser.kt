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
 * Parser for Wisedu/金智 jwapp timetable responses.
 *
 * The school WebView performs the authenticated request. This class only
 * converts the returned JSON payload into the app's existing import model.
 */
class WiseduScheduleParser(private val defaultTotalWeeks: Int) {

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

        return findCourseArray(payload, depth = 0).orEmpty()
    }

    private fun findCourseArray(element: JsonElement, depth: Int): List<JsonObject>? {
        if (depth > 10 || element.isJsonNull || element.isJsonPrimitive) return null
        if (element.isJsonArray) {
            val rows = element.asJsonArray
                .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                .filter { row -> row.has("KCM") && row.has("SKXQ") && row.has("KSJC") }
            if (rows.isNotEmpty()) return rows
            return element.asJsonArray.firstNotNullOfOrNull { child ->
                findCourseArray(child, depth + 1)
            }
        }
        return element.asJsonObject.entrySet().firstNotNullOfOrNull { (_, child) ->
            findCourseArray(child, depth + 1)
        }
    }

    private fun parseRow(row: JsonObject): List<Course> {
        val name = row.string("KCM").trim()
        val day = row.string("SKXQ").firstInteger()
        val startSection = row.string("KSJC").firstInteger()
        val endSection = row.string("JSJC").firstInteger() ?: startSection
        if (name.isBlank() || day == null || day !in 1..7 || startSection == null || endSection == null) {
            return emptyList()
        }

        val teacher = row.string("SKJS").trim()
        val classroom = row.string("JASMC").trim()
        val weekPattern = row.string("SKZC").trim()
        val weekGroups = compressWeeks(parseWeeks(weekPattern))
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

    private fun parseWeeks(pattern: String): List<Int> {
        if (pattern.isBlank()) return (1..defaultTotalWeeks.coerceAtLeast(1)).toList()

        val normalized = pattern.trim()
        if (normalized.matches(Regex("[01]+"))) {
            return normalized.mapIndexedNotNull { index, value ->
                if (value == '1') index + 1 else null
            }
        }

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

    private fun compressWeeks(weeks: List<Int>): List<WeekGroup> {
        val sorted = weeks.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        if (sorted.size == 1) return listOf(WeekGroup(sorted.first(), sorted.first(), 0))

        val groups = mutableListOf<WeekGroup>()
        var index = 0
        while (index < sorted.size) {
            val start = sorted[index]
            val canUseParityRun = index + 1 < sorted.size && sorted[index + 1] - start == 2
            if (canUseParityRun) {
                var endIndex = index + 1
                while (endIndex + 1 < sorted.size && sorted[endIndex + 1] - sorted[endIndex] == 2) {
                    endIndex++
                }
                groups += WeekGroup(
                    start = start,
                    end = sorted[endIndex],
                    weekType = if (start % 2 == 0) 2 else 1
                )
                index = endIndex + 1
                continue
            }

            var endIndex = index
            while (endIndex + 1 < sorted.size && sorted[endIndex + 1] - sorted[endIndex] == 1) {
                endIndex++
            }
            groups += WeekGroup(start, sorted[endIndex], 0)
            index = endIndex + 1
        }
        return groups
    }

    private data class WeekGroup(val start: Int, val end: Int, val weekType: Int)

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
