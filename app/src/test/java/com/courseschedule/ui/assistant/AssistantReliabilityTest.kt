package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AssistantReliabilityTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val semester = Semester(id = 7, name = "测试学期", startDate = LocalDate.of(2026, 9, 7)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), totalWeeks = 16)
    private val math = Course(id = 42, semesterId = 7, courseName = "高等数学", teacher = "张老师", classroom = "A101",
        dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16, note = "保留备注", colorIndex = 8, createTime = 100)
    private val english = math.copy(id = 43, courseName = "英语", createTime = 101)
    private fun parse(payload: String, existing: List<Course> = listOf(math)) = AssistantCourseClient.parseResponse(JsonObject().apply {
        add("choices", JsonParser.parseString("""[{"finish_reason":"stop","message":{}}]"""))
        getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").addProperty("content", payload)
    }.toString(), 16, existing, semester, 4)

    @Test fun existingConflictsAllowMetadataEditsAndExactUndoButNotNewOverlaps() {
        val edited = math.copy(note = "新备注", reminderMinutes = 15)
        AssistantChangeRules.validate(listOf(edited), listOf(english), semester, mapOf(edited to math))
        AssistantChangeRules.validate(listOf(math), listOf(english), semester, mapOf(math to math), listOf(english))
        val grown = math.copy(endSection = 3)
        val neighbor = english.copy(startSection = 2, endSection = 3)
        assertThrows(IllegalArgumentException::class.java) {
            AssistantChangeRules.validate(listOf(grown), listOf(neighbor), semester, mapOf(grown to math))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantChangeRules.validate(listOf(math), listOf(english.copy(id = 99)), semester, mapOf(math to math), listOf(english))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantChangeRules.validate(listOf(math), listOf(english.copy(startSection = 2, endSection = 3)), semester,
                mapOf(math to math), listOf(english))
        }
    }

    @Test fun editingBothPreviouslyConflictingCoursesDoesNotCreateFalseConflict() {
        val first = math.copy(classroom = "B201")
        val second = english.copy(reminderMinutes = 10)
        AssistantChangeRules.validate(listOf(first, second), emptyList(), semester, mapOf(first to math, second to english))
    }

    @Test fun rejectsUnknownActionFieldsAndInconsistentVersionedReplies() {
        listOf("""{"reply":"已删除","delete_ids":[42]}""",
            """{"version":1,"action":"change","reply":"更改","courses":[],"updates":[],"deleteIds":[],"queryIds":[],"undo":false}""",
            """{"reply":"查询","query":{"dayOfWeek":8}}""",
            """{"reply":"更改","updates":[{"id":42,"unexpected":"x"}]}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { parse(it) }
        }
    }

    @Test fun singleOccurrenceMovePreservesAllOtherWeeksAndMetadata() {
        val update = parse("""{"reply":"只移第5周","occurrences":[{"id":42,"weeks":[5],"dayOfWeek":5,"startSection":3}]}""").updates.single()
        val moved = update.replacements.single { it.dayOfWeek == 5 }
        assertEquals(listOf(5), AssistantChangeRules.weeks(moved))
        assertEquals(3, moved.startSection)
        assertEquals(4, moved.endSection)
        assertEquals((1..16).filter { it != 5 }, update.replacements.filter { it.dayOfWeek == 3 }.flatMap { AssistantChangeRules.weeks(it) }.sorted())
        assertTrue(update.replacements.all { it.teacher == math.teacher && it.note == math.note && it.colorIndex == math.colorIndex && it.createTime == math.createTime })
        assertEquals(math.id, update.replacements.first().id)
        assertTrue(update.replacements.drop(1).all { it.id == 0L })
    }

    @Test fun occurrenceCancelDoesNotDeleteOtherWeeksAndRejectsWrongDates() {
        val cancelled = parse("""{"reply":"取消一次","occurrences":[{"id":42,"date":"2026-10-07","cancel":true}]}""")
        assertTrue(cancelled.deletions.isEmpty())
        assertFalse(cancelled.updates.single().replacements.any { 5 in AssistantChangeRules.weeks(it) })
        val all = (1..16).joinToString(",")
        assertEquals(listOf(math), parse("""{"reply":"取消所有","occurrences":[{"id":42,"weeks":[$all],"cancel":true}]}""").deletions)
        listOf("2026-10-08", "2027-01-06").forEach { date ->
            assertThrows(IllegalArgumentException::class.java) {
                parse("""{"reply":"取消一次","occurrences":[{"id":42,"date":"$date","cancel":true}]}""")
            }
        }
    }

    @Test fun expandedRecordsAreBoundedAndDuplicateTargetsRejected() {
        val row = """{"courseName":"课","teacher":"","classroom":"","dayOfWeek":1,"startSection":1,"endSection":1,"weeks":[1,2,5,6,9,10,13,14],"note":""}"""
        // 20 logical rows expand to exactly 80 records; adding a fifth range exceeds the limit.
        assertEquals(80, parse("""{"reply":"批量","courses":[${(1..20).joinToString(",") { row }}]}""").courses.size)
        val tooMany = row.replace("[1,2,5,6,9,10,13,14]", "[1,2,4,5,7,8,10,11,13,14]")
        assertThrows(IllegalArgumentException::class.java) { parse("""{"reply":"批量","courses":[${(1..20).joinToString(",") { tooMany }}]}""") }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"reply":"冲突目标","updates":[{"id":42,"note":"改"}],"occurrences":[{"id":42,"weeks":[5],"cancel":true}]}""")
        }
    }

    @Test fun localQueryReturnsVerifiedEmptyResultsAndFiltersDateWeekAndTeacher() {
        val courses = listOf(math, english.copy(dayOfWeek = 5, startWeek = 5))
        assertEquals(listOf(math), AssistantCourseQuery(date = "2026-10-07", teacher = "张").find(courses, semester, 4))
        assertTrue(AssistantCourseQuery(date = "2026-10-02").find(courses, semester, 4).isEmpty())
        assertEquals(listOf(math), parse("""{"reply":"查课","query":{"courseName":"数学","week":5}}""").query!!.find(courses, semester, 4))
        assertThrows(IllegalArgumentException::class.java) { AssistantCourseQuery(date = "2027-01-06").find(courses, semester, 4) }
        assertNotNull(AssistantCourseQuery.quick("今天有哪些课？", today))
        assertNull(AssistantCourseQuery.quick("今天有哪些课并删除数学", today))
    }

    @Test fun calendarDoesNotClampDatesOutsideSemesterOrConfuseDisplayedWeek() {
        assertEquals(4, AssistantCalendar.week(semester, today))
        assertNull(AssistantCalendar.week(semester, LocalDate.of(2026, 9, 6)))
        assertNull(AssistantCalendar.week(semester, LocalDate.of(2026, 12, 28)))
        assertEquals(listOf(5), AssistantCalendar.scopedWeeks("next_week", semester, 12, today))
        assertEquals(listOf(12), AssistantCalendar.scopedWeeks("displayed_week", semester, 12, today))
        assertThrows(IllegalArgumentException::class.java) { AssistantCalendar.scopedWeeks("this_week", semester, 1, LocalDate.of(2027, 1, 1)) }
        val request = AssistantCourseClient.createRequest("model", emptyList(), semester, 4, listOf("08:00"), listOf("08:45"), today = LocalDate.of(2027, 1, 1))
        assertTrue(request.contains("学期已经结束"))
        assertTrue(request.contains("今天不在本学期"))
        assertFalse(request.contains("实际当前周为 16"))
    }

    @Test fun groupedEditsAndAppendNotesPreserveUnmentionedFields() {
        val fragment = math.copy(id = 44, startWeek = 7, endWeek = 10)
        val edited = parse("""{"reply":"所有安排改地点","updates":[{"ids":[42,44],"classroom":"B201","appendNote":"带教材"}]}""", listOf(math, fragment)).updates
        assertEquals(2, edited.size)
        assertTrue(edited.all { it.replacements.single().note == "保留备注\n带教材" && it.replacements.single().classroom == "B201" })
        val longOriginal = math.copy(note = " 原备注 ".repeat(150))
        assertEquals(longOriginal.note + "\n带教材", parse("""{"reply":"追加","updates":[{"id":42,"appendNote":"带教材"}]}""",
            listOf(longOriginal)).updates.single().replacements.single().note)
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"reply":"不关联课程","updates":[{"ids":[42,43],"classroom":"B201"}]}""", listOf(math, english))
        }
    }

    @Test fun requestOmitsLongNotesBoundsCatalogAndSupportsOptionalJsonMode() {
        val courses = (1..100).map { math.copy(id = it.toLong(), courseName = "课程$it", note = "私有长备注".repeat(100)) }
        val request = AssistantCourseClient.createRequest("model", listOf(AssistantMessage("user", "查本周的课")), semester, 4,
            listOf("08:00"), listOf("08:45"), courses, jsonMode = true, today = today)
        assertTrue(request.length < 64_000)
        assertFalse(request.contains("私有长备注"))
        assertEquals("json_object", JsonParser.parseString(request).asJsonObject.getAsJsonObject("response_format").get("type").asString)
        val large = AssistantCourseClient.createRequest("model", listOf(AssistantMessage("user", "查英语")), semester, 4,
            listOf("08:00"), listOf("08:45"), courses + (101..500).map { math.copy(id = it.toLong(), courseName = "其他课$it") }, today = today)
        assertTrue(large.contains("候选不完整"))
        assertTrue(large.length < 64_000)
        val longFields = (1..200).map { math.copy(id = it.toLong(), courseName = "课$it" + "长".repeat(115),
            teacher = "师".repeat(80), classroom = "室".repeat(120), note = "私有长备注".repeat(100)) }
        val bounded = AssistantCourseClient.createRequest("model", listOf(AssistantMessage("user", "查数学")), semester, 4,
            listOf("08:00"), listOf("08:45"), longFields, today = today)
        assertTrue(bounded.length <= 64_000)
        assertFalse(bounded.contains("本次候选 200 条"))
        val pending = AssistantCourseReply("改教室", emptyList(), updates = listOf(AssistantCourseUpdate(courses.first(), listOf(courses.first().copy(classroom = "C201")))))
        val refinement = AssistantCourseClient.createRequest("model", listOf(AssistantMessage("user", "改成D201")), semester, 4,
            listOf("08:00"), listOf("08:45"), courses, pending = pending, today = today)
        assertFalse(refinement.contains("私有长备注"))
        assertTrue(refinement.contains("C201"))
        assertTrue(refinement.length < 64_000)
    }
}
