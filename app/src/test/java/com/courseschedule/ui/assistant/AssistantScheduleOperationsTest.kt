package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AssistantScheduleOperationsTest {
    private val start = LocalDate.of(2026, 9, 7)
    private val today = start.plusWeeks(1).plusDays(1)
    private val semester = Semester(id = 7, name = "测试学期", totalWeeks = 16,
        startDate = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val math = Course(id = 42, semesterId = 7, courseName = "高等数学", teacher = "张老师",
        classroom = "A101", dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16,
        colorIndex = 8, reminderMinutes = 15, note = "保留", createTime = 123)

    private fun response(payload: String) = JsonObject().apply {
        add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("finish_reason", "stop")
            add("message", JsonObject().apply { addProperty("content", payload) })
        }) })
    }.toString()

    private fun parse(payload: String, pending: AssistantCourseReply? = null, courses: List<Course> = listOf(math)) =
        AssistantCourseClient.parseResponse(response(payload), 16, courses, semester, 8, pending, today)

    @Test fun alternativeSectionsKeepDurationWeekScopeAndMetadataWithoutOverlaps() {
        val proposed = math.copy(startSection = 3, endSection = 4)
        val blocker = proposed.copy(id = 43, courseName = "冲突课程")
        val alternatives = AssistantScheduleOperations.alternativeSections(listOf(proposed), listOf(blocker), listOf(null), listOf(blocker), 0, 8)
        assertEquals(listOf(1, 5, 6), alternatives.map { it.startSection })
        alternatives.forEach {
            assertEquals(proposed.copy(startSection = it.startSection, endSection = it.startSection + 1), it)
            assertFalse(ScheduleRules.coursesOverlap(it, blocker))
        }
    }

    @Test fun alternativesRespectOddWeeksAndOtherChangesInTheSameBatch() {
        val odd = math.copy(weekType = 1)
        val blocker = odd.copy(id = 43, courseName = "冲突课程")
        val otherChange = odd.copy(id = 0, courseName = "同时新增", startSection = 3, endSection = 4)
        val even = odd.copy(id = 44, courseName = "双周课程", startSection = 5, endSection = 6, weekType = 2)
        val existing = listOf(blocker, even)
        val alternatives = AssistantScheduleOperations.alternativeSections(listOf(odd, otherChange), existing,
            listOf(null, null), existing, 0, 6)
        assertEquals(5, alternatives.single().startSection)
        assertEquals(1, alternatives.single().weekType)
        assertTrue(AssistantScheduleOperations.alternativeSections(listOf(odd, otherChange), listOf(blocker, even.copy(weekType = 0)),
            listOf(null, null), existing, 0, 6).isEmpty())
    }

    @Test fun noAlternativeIsSuggestedIfAnotherBatchConflictRemainsOrDurationCannotFit() {
        val blocker = math.copy(id = 43, courseName = "冲突课程")
        val otherChange = math.copy(id = 0, courseName = "其他冲突", startSection = 5, endSection = 6)
        val otherBlocker = otherChange.copy(id = 44, courseName = "占用课程")
        val existing = listOf(blocker, otherBlocker)
        assertTrue(AssistantScheduleOperations.alternativeSections(listOf(math, otherChange), existing,
            listOf(null, null), existing, 0, 12).isEmpty())
        assertTrue(AssistantScheduleOperations.alternativeSections(listOf(math.copy(startSection = 1, endSection = 12)),
            listOf(blocker), listOf(null), listOf(blocker), 0, 12).isEmpty())
    }

    @Test fun datesAreResolvedLocallyAndSemesterBoundariesNeverClamp() {
        assertEquals(AssistantResolvedDate(2, 3), AssistantScheduleOperations.resolve(
            AssistantDateSelection(dayOffset = 1), semester, 8, today))
        assertEquals(AssistantResolvedDate(3, null), AssistantScheduleOperations.resolve(
            AssistantDateSelection(weekOffset = 1), semester, 8, today))
        assertEquals(AssistantResolvedDate(8, null), AssistantScheduleOperations.resolve(
            AssistantDateSelection(useDisplayedWeek = true), semester, 8, today))
        for (outside in listOf(start.minusDays(1), start.plusWeeks(16))) {
            assertThrows(IllegalArgumentException::class.java) { AssistantScheduleOperations.resolve(
                AssistantDateSelection(weekOffset = 0), semester, 8, outside) }
        }
        assertThrows(IllegalArgumentException::class.java) { AssistantScheduleOperations.resolve(
            AssistantDateSelection(date = start.minusDays(1).toString()), semester, 8, today) }
        assertThrows(IllegalArgumentException::class.java) { AssistantScheduleOperations.resolve(
            AssistantDateSelection(week = 2, dayOffset = 1), semester, 8, today) }
        val future = AssistantCourseClient.createRequest("test", emptyList(), semester, 8, listOf("08:00"), listOf("08:45"), today = start.minusDays(1))
        assertTrue(future.contains("BEFORE"))
        assertTrue(future.contains("null（学期外"))
        assertFalse(future.contains("实际当前周为 1，"))
    }

    @Test fun queriesAndFreeSlotsUseActualWeekAndAllCourses() {
        val odd = math.copy(id = 43, courseName = "单周英语", startSection = 3, endSection = 4, weekType = 1)
        val other = math.copy(id = 44, semesterId = 8)
        val courses = listOf(math, odd, other)
        val query = parse("""{"version":1,"action":"query","reply":"查询明天","query":{"whenTo":{"dayOffset":1}}}""").query!!
        assertEquals(listOf(math), AssistantScheduleOperations.query(query, courses, semester, 8, today))
        assertTrue(AssistantScheduleOperations.query(query.copy(courseName = "不存在"), courses, semester, 8, today).isEmpty())
        assertEquals(listOf(3, 4, 5, 6), AssistantScheduleOperations.freeSections(
            query.copy(freeSlots = true), courses, semester, 8, 6, today))
        assertThrows(IllegalArgumentException::class.java) { AssistantScheduleOperations.freeSections(
            query.copy(freeSlots = true, courseName = "数学"), courses, semester, 8, 6, today) }
        assertThrows(IllegalArgumentException::class.java) { AssistantScheduleOperations.query(
            query.copy(dayOfWeek = 4), courses, semester, 8, today) }
    }

    @Test fun movingOneOccurrencePreservesOtherWeeksDurationAndMetadata() {
        val reply = parse("""{"version":1,"action":"change","reply":"只移动下周的一次课",
            "occurrences":[{"id":42,"operation":"move","source":{"weekOffset":1},
            "target":{"dayOfWeek":5,"startSection":3,"classroom":"B302"}}]}""")
        val replacements = reply.updates.single().replacements
        val kept = replacements.filter { it.dayOfWeek == 3 }
        assertEquals((1..16).filter { it != 3 }, kept.flatMap { row ->
            (1..16).filter { ScheduleRules.isCourseInWeek(row, it) } }.sorted())
        val moved = replacements.single { it.dayOfWeek == 5 }
        assertEquals(3, moved.startWeek)
        assertEquals(3, moved.endWeek)
        assertEquals(3, moved.startSection)
        assertEquals(4, moved.endSection)
        assertEquals("B302", moved.classroom)
        assertTrue(replacements.all { it.colorIndex == 8 && it.reminderMinutes == 15 && it.createTime == 123L && it.note == "保留" })
        assertEquals(42L, replacements.first().id)
        assertTrue(replacements.drop(1).all { it.id == 0L })
        assertTrue(reply.requiresConfirmation)
    }

    @Test fun cancellationAndMakeupNeverDeleteOtherOccurrences() {
        val single = math.copy(startWeek = 2, endWeek = 2)
        val cancelled = parse("""{"reply":"取消一次","occurrences":[{"id":42,"operation":"cancel","source":{"week":2}}]}""", courses = listOf(single))
        assertEquals(listOf(single), cancelled.deletions)
        val copy = parse("""{"reply":"补课","occurrences":[{"id":42,"operation":"copy","source":{"week":2},
            "target":{"week":3,"dayOfWeek":5}}]}""")
        assertTrue(copy.updates.isEmpty() && copy.deletions.isEmpty())
        assertEquals(3, copy.courses.single().startWeek)
        assertEquals(0L, copy.courses.single().id)
        assertEquals(15, copy.additionReminders[0])
        assertTrue(copy.requiresConfirmation)
        assertThrows(IllegalArgumentException::class.java) { parse("""{"reply":"取消","occurrences":[
            {"id":42,"operation":"cancel","source":{"date":"2026-09-17"}}]}""") }
    }

    @Test fun oldConflictsMayRemainButNewCellsNeighborsAndFragmentOverlapsAreRejected() {
        val english = math.copy(id = 43, courseName = "英语")
        val baseline = listOf(math, english)
        assertTrue(AssistantScheduleOperations.newConflicts(listOf(math.copy(note = "新备注")), listOf(english), listOf(math), baseline).isEmpty())
        assertTrue(AssistantScheduleOperations.newConflicts(listOf(math.copy(endWeek = 8)), listOf(english), listOf(math), baseline).isEmpty())
        assertTrue(AssistantScheduleOperations.newConflicts(listOf(math), listOf(english), listOf(math), baseline).isEmpty())
        assertFalse(AssistantScheduleOperations.newConflicts(listOf(math), listOf(english.copy(id = 99)), listOf(math), baseline).isEmpty())
        val enlarged = math.copy(endSection = 3)
        assertFalse(AssistantScheduleOperations.newConflicts(listOf(enlarged), listOf(english.copy(endSection = 3)),
            listOf(math), baseline).isEmpty())
        assertFalse(AssistantScheduleOperations.newConflicts(listOf(math.copy(endWeek = 8), math.copy(startWeek = 7)),
            emptyList(), listOf(math, math), baseline).isEmpty())
    }

    @Test fun revisionsStayInsidePendingPlanAndSurvivePersistence() {
        val pending = AssistantCourseReply("修改教室", emptyList(), updates = listOf(
            AssistantCourseUpdate(math, listOf(math.copy(classroom = "B201")))))
        val revised = parse("""{"version":1,"action":"revise","reply":"换成B302",
            "revisions":[{"index":0,"classroom":"B302","reminderMinutes":10}]}""", pending)
        assertTrue(revised.revisedPending)
        assertEquals(math, revised.updates.single().original)
        assertEquals(math.copy(classroom = "B302", reminderMinutes = 10), revised.updates.single().replacements.single())
        val state = AssistantConversationState(pending = AssistantPendingOperation(semester, revised.copy(revisedPending = false)),
            retryRequest = AssistantRetryRequest("再改教室", 8), requestRunning = true)
        assertEquals(state, AssistantConversationCodec.decode(AssistantConversationCodec.encode(state), 7))
        val payloads = listOf(
            """{"reply":"改其他目标","updates":[{"id":42,"note":"新内容"}]}""",
            """{"reply":"换目标","revisions":[{"index":0,"id":99,"classroom":"B302"}]}""",
            """{"reply":"越界","revisions":[{"index":1,"classroom":"B302"}]}""",
            """{"reply":"偷偷新增","revisions":[{"index":0,"classroom":"B302"}],"deleteIds":[42]}"""
        )
        payloads.forEach { assertThrows(IllegalArgumentException::class.java) { parse(it, pending) } }
    }

    @Test fun unknownFieldsInconsistentActionsAndOversizedExpansionFailClosed() {
        val payloads = listOf(
            """{"reply":"已删除","delete_ids":[42]}""",
            """{"version":1,"action":"chat","reply":"删除","deleteIds":[42]}""",
            """{"version":2,"action":"undo","reply":"撤销","undo":true}""",
            """{"version":1,"action":"query","reply":"查课"}""",
            """{"version":1,"action":"query","reply":"查课","queryIds":[],"deleteIds":[42]}""",
            """{"reply":"查询","query":{"whenTo":{"week":2,"dayOffset":1}}}""",
            """{"reply":"移动","occurrences":[{"id":42,"operation":"move","source":{"week":2},"target":{"weeks":[1,2]}}]}"""
        )
        payloads.forEach { assertThrows(IllegalArgumentException::class.java) { parse(it) } }
        val weeks = (1..52).filter { it % 4 in 1..2 }.joinToString(",")
        val rows = (1..20).joinToString(",") { """{"courseName":"课程$it","teacher":"","classroom":"","note":"",
            "dayOfWeek":1,"startSection":1,"endSection":1,"weeks":[$weeks]}""" }
        assertThrows(IllegalArgumentException::class.java) { AssistantCourseClient.parseResponse(response("""{"reply":"新增","courses":[$rows]}"""), 52) }
    }
}
