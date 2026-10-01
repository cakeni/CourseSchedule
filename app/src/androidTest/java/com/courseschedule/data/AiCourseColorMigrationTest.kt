package com.courseschedule.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.AiCourseColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiCourseColorMigrationTest {
    @Test fun upgradeUnifiesOnlyAiColorsAndPreservesCourseAndSemesterData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ai_color_upgrade_${System.nanoTime()}"
        val semesters = listOf(Semester(id = 1, name = "测试学期一", startDate = 0, isCurrent = true),
            Semester(id = 2, name = "测试学期二", startDate = 1000))
        val first = Course(id = 1, courseName = "同名课程", dayOfWeek = 1, startSection = 1, endSection = 2,
            startWeek = 1, endWeek = 16, semesterId = 1, colorIndex = 0, note = AiCourseColors.DEEPSEEK_NOTE)
        val before = listOf(first,
            first.copy(id = 2, dayOfWeek = 3, colorIndex = 13, teacher = "另一位教师", classroom = "另一个教室"),
            first.copy(id = 3, dayOfWeek = 5, colorIndex = 7, note = AiCourseColors.OPENAI_NOTE),
            first.copy(id = 4, semesterId = 2, colorIndex = 12),
            first.copy(id = 5, semesterId = 2, dayOfWeek = 4, colorIndex = 9),
            first.copy(id = 6, colorIndex = 8, note = "系统导入"),
            first.copy(id = 7, colorIndex = 5, note = "手动备注", reminderMinutes = 15))
        var database: AppDatabase? = null
        try {
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val originalSemesterDao = database.semesterDao()
            semesters.forEach { originalSemesterDao.insertSemester(it) }
            database.courseDao().insertCourses(before)
            // Version 1 had only courses and semesters.
            database.openHelper.writableDatabase.execSQL("DROP TABLE assistant_messages")
            database.openHelper.writableDatabase.execSQL("DROP TABLE assistant_conversations")
            database.openHelper.writableDatabase.version = 1
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
            val expected = before.map {
                if (AiCourseColors.isAiCourse(it)) it.copy(colorIndex = if (it.semesterId == 1L) 0 else 12) else it
            }
            assertEquals(expected, database.courseDao().getAllCoursesSync().sortedBy { it.id })
            val migratedSemesterDao = database.semesterDao()
            semesters.forEach { assertEquals(it, migratedSemesterDao.getSemesterById(it.id)) }
            assertEquals(3, database.openHelper.writableDatabase.version)

            val edited = expected[1].copy(colorIndex = 10)
            database.courseDao().updateCourse(edited)
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
            assertEquals(edited, database.courseDao().getCourseById(edited.id))
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
