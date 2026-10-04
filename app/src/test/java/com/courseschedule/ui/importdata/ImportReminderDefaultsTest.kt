package com.courseschedule.ui.importdata

import com.courseschedule.data.backup.ScheduleBackup
import com.courseschedule.data.backup.SemesterSnapshot
import com.courseschedule.data.backup.SettingsSnapshot
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportReminderDefaultsTest {
    private val course = Course(courseName = "测试课", dayOfWeek = 1, startSection = 1,
        endSection = 2, startWeek = 1, endWeek = 16)

    @Test fun ordinaryImportUsesDefault() {
        val parsed = ParsedImport(listOf(course))
        assertEquals(30, parsed.withDefaultReminder(course, 30).reminderMinutes)
    }

    @Test fun jsonPreservesExplicitOffAndUsesDefaultForMissingField() {
        val explicit = Gson().toJsonTree(course).asJsonObject
        val unspecified = explicit.deepCopy().apply { remove("reminderMinutes"); addProperty("courseName", "另一门课") }
        val parsed = ImportParser(20).parseJson("[$explicit,$unspecified]")
        assertEquals(-1, parsed.withDefaultReminder(parsed.courses[0], 15).reminderMinutes)
        assertEquals(15, parsed.withDefaultReminder(parsed.courses[1], 15).reminderMinutes)
    }

    @Test fun backupPreservesPerCourseRemindersEvenWhenDefaultsDiffer() {
        val backup = ScheduleBackup(semester = SemesterSnapshot.from(Semester(name = "秋季", startDate = 0)),
            settings = SettingsSnapshot(),
            courses = listOf(course, course.copy(courseName = "另一门课", reminderMinutes = 5)))
        val parsed = ImportParser(20).parseJson(Gson().toJson(backup))
        assertEquals(listOf(-1, 5), parsed.courses.map { parsed.withDefaultReminder(it, 30).reminderMinutes })
    }
}
