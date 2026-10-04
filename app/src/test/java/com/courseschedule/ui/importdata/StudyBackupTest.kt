package com.courseschedule.ui.importdata

import com.courseschedule.data.backup.*
import com.courseschedule.data.entity.StudyTask
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class StudyBackupTest {
    @Test fun newBackupRetainsDeadlinesCompletionAndReminderAndOldBackupStillReads() {
        val task = StudyTask(8, 7, title = "报告", kind = "report", dueAt = 1791100800000L, reminderMinutes = 1440, completedAt = 1234)
        val backup = ScheduleBackup(semester = SemesterSnapshot("测试", 1234, 20), settings = SettingsSnapshot(), courses = emptyList(), studyTasks = listOf(task))
        val parser = ImportParser(20)
        assertEquals(listOf(task), parser.parseJson(Gson().toJson(backup)).studyTasks)
        assertNull(parser.parseJson("""{"schemaVersion":2,"semester":{"name":"旧备份","startDate":1234,"totalWeeks":20},"settings":{},"courses":[]}""").studyTasks)
    }
    @Test fun corruptTaskBackupIsRejectedBeforeImport() {
        val parser = ImportParser(20)
        try { parser.parseJson("""{"schemaVersion":3,"semester":{"name":"坏备份","startDate":1234,"totalWeeks":20},"settings":{},"courses":[],"studyTasks":[{"title":"报告","semesterId":1,"kind":"report","dueAt":0}]}"""); fail() }
        catch (_: ImportFormatException) { }
    }
}
