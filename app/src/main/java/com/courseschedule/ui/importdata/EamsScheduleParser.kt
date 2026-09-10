package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Strict parser for the EAMS `table0 + unitCount` page-global contract. */
internal class EamsScheduleParser {
    fun parse(data: JsonElement?): ParsedImport {
        val holder = data?.let { findHolder(it, 0, mutableSetOf()) }
            ?: throw ImportFormatException(
                "未找到 EAMS table0 课表数据",
                AcademicImportErrorCode.NO_TIMETABLE
            )
        val unitCount = holder.primitiveInt("unitCount")
            ?: throw ImportFormatException("EAMS 节次数缺失", AcademicImportErrorCode.MISSING_SECTION)
        if (unitCount !in 1..30) {
            throw ImportFormatException("EAMS 节次数异常", AcademicImportErrorCode.MISSING_SECTION)
        }
        val activities = holder.getAsJsonArray("activities")
            ?: throw ImportFormatException("EAMS 活动列表缺失", AcademicImportErrorCode.NO_TIMETABLE)
        if (activities.size() > unitCount * 7) {
            throw ImportFormatException("EAMS 课表网格超出七天范围", AcademicImportErrorCode.PARTIAL_PARSE)
        }

        data class Meeting(
            val name: String,
            val teacher: String,
            val room: String,
            val day: Int,
            val section: Int,
            val weeks: List<Int>
        )

        var courseItems = 0
        val meetings = activities.flatMapIndexed { index, cell ->
            val values = cell.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?: throw ImportFormatException(
                    "EAMS 单元格结构不完整",
                    AcademicImportErrorCode.PARTIAL_PARSE
                )
            values.map { value ->
                courseItems++
                if (courseItems > 1_500) {
                    throw ImportFormatException("EAMS 课程数量异常", AcademicImportErrorCode.PAYLOAD_TOO_LARGE)
                }
                val item = value.takeIf(JsonElement::isJsonObject)?.asJsonObject
                    ?: throw ImportFormatException(
                        "EAMS 课程结构不完整",
                        AcademicImportErrorCode.PARTIAL_PARSE
                    )
                val name = item.primitiveString("courseName").trim()
                if (name.isBlank()) {
                    throw ImportFormatException("EAMS 课程名称缺失", AcademicImportErrorCode.PARTIAL_PARSE)
                }
                Meeting(
                    name = name,
                    teacher = item.primitiveString("teacherName").trim(),
                    room = item.primitiveString("roomName").trim(),
                    day = index / unitCount + 1,
                    section = index % unitCount + 1,
                    weeks = parseWeekBitmap(item.primitiveString("vaildWeeks"))
                )
            }
        }
        if (meetings.isEmpty()) {
            throw ImportFormatException("当前 EAMS 页面没有课程", AcademicImportErrorCode.NO_TIMETABLE)
        }

        val courses = meetings.groupBy {
            listOf(it.name, it.teacher, it.room, it.day.toString(), it.weeks.joinToString(","))
        }.values.flatMap { sameCourse ->
            val first = sameCourse.first()
            val sectionRuns = contiguousRuns(sameCourse.map { it.section }.distinct())
            sectionRuns.flatMap { sections ->
                compressImportWeeks(first.weeks).map { weeks ->
                    Course(
                        courseName = first.name,
                        teacher = first.teacher,
                        classroom = first.room,
                        dayOfWeek = first.day,
                        startSection = sections.first,
                        endSection = sections.last,
                        startWeek = weeks.start,
                        endWeek = weeks.end,
                        weekType = weeks.weekType,
                        colorIndex = Math.floorMod(first.name.hashCode(), 16)
                    )
                }
            }
        }
        return ParsedImport(courses.distinctBy(::courseKey), sourceLabel = "EAMS · table0")
    }

    private fun parseWeekBitmap(raw: String): List<Int> {
        var bitmap = raw.trim()
        if (!bitmap.matches(Regex("[01]{2,53}"))) {
            throw ImportFormatException("EAMS 周次位图缺失或无效", AcademicImportErrorCode.MISSING_WEEK)
        }
        // EAMS spells this field `vaildWeeks`; index zero is a sentinel.
        bitmap = bitmap.drop(1)
        if (bitmap.length > 52) {
            throw ImportFormatException("EAMS 周次超出范围", AcademicImportErrorCode.MISSING_WEEK)
        }
        return bitmap.mapIndexedNotNull { index, bit -> (index + 1).takeIf { bit == '1' } }
            .ifEmpty {
                throw ImportFormatException("EAMS 周次为空", AcademicImportErrorCode.MISSING_WEEK)
            }
    }

    private fun contiguousRuns(values: List<Int>): List<IntRange> {
        val sorted = values.distinct().sorted()
        val result = mutableListOf<IntRange>()
        for (value in sorted) {
            val last = result.lastOrNull()
            if (last != null && last.last + 1 == value) result[result.lastIndex] = last.first..value
            else result += value..value
        }
        return result
    }

    private fun findHolder(value: JsonElement, depth: Int, seen: MutableSet<JsonElement>): JsonObject? {
        if (depth > 10 || seen.size > 3_000 || !seen.add(value)) return null
        if (value.isJsonObject) {
            val objectValue = value.asJsonObject
            if (objectValue.get("activities")?.isJsonArray == true && objectValue.has("unitCount")) {
                return objectValue
            }
            objectValue.entrySet().take(160).forEach { (_, child) ->
                findHolder(child, depth + 1, seen)?.let { return it }
            }
        } else if (value.isJsonArray) {
            value.asJsonArray.take(600).forEach { child ->
                findHolder(child, depth + 1, seen)?.let { return it }
            }
        }
        return null
    }

    private fun JsonObject.primitiveString(key: String): String = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()

    private fun JsonObject.primitiveInt(key: String): Int? = get(key)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asString?.toIntOrNull()

    private fun courseKey(course: Course): List<Any> = listOf(
        course.courseName, course.teacher, course.classroom, course.dayOfWeek,
        course.startSection, course.endSection, course.startWeek, course.endWeek, course.weekType
    )
}
