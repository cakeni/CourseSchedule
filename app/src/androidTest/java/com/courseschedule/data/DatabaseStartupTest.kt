package com.courseschedule.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.AssistantConversation
import com.courseschedule.data.entity.AssistantChatMessage
import com.courseschedule.domain.AiCourseColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class DatabaseStartupTest {
    @Test fun mainVersionTwoUpgradePreservesUserColors(): Unit = runBlocking {
        verifyVersionTwoUpgrade(withAssistant = false)
    }

    @Test fun assistantVersionTwoUpgradePreservesChatsAndOperationState(): Unit = runBlocking {
        verifyVersionTwoUpgrade(withAssistant = true)
    }

    private suspend fun verifyVersionTwoUpgrade(withAssistant: Boolean) {
        val name = "assistant-migration-v2-$withAssistant-test"
        context.deleteDatabase(name)
        val semester = Semester(id = 42, name = "原学期", startDate = 1000,
            totalWeeks = 16, isCurrent = true, createTime = 100)
        val first = Course(id = 17, courseName = "原课程", teacher = "原老师", classroom = "A101",
            dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16,
            semesterId = 42, colorIndex = 8, note = AiCourseColors.DEEPSEEK_NOTE,
            reminderMinutes = 15, createTime = 1234)
        val second = first.copy(id = 18, dayOfWeek = 5, colorIndex = 9)
        val manual = first.copy(id = 19, dayOfWeek = 4, note = "原备注", colorIndex = 3)
        val conversation = AssistantConversation("version-two-chat", 42, "原对话", 4567,
            stateJson = "{\"requestRunning\":true}", revision = 7)
        val message = AssistantChatMessage(id = 23, conversationId = conversation.id,
            role = "user", content = "周三有哪些课？", createdAt = 4567)
        try {
            open(name, initialize = false).use { old ->
                old.semesterDao().insertSemester(semester)
                old.courseDao().insertCourses(listOf(first, second, manual))
                if (withAssistant) {
                    old.assistantConversationDao().insertConversation(conversation)
                    old.assistantConversationDao().insertMessage(message)
                } else {
                    old.openHelper.writableDatabase.execSQL("DROP TABLE assistant_messages")
                    old.openHelper.writableDatabase.execSQL("DROP TABLE assistant_conversations")
                }
                old.openHelper.writableDatabase.version = 2
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_2_3).build().use { upgraded ->
                    assertEquals(3, upgraded.openHelper.readableDatabase.version)
                    assertEquals(semester, upgraded.semesterDao().getCurrentSemesterSync())
                    assertEquals(listOf(first, if (withAssistant) second.copy(colorIndex = 8) else second, manual),
                        upgraded.courseDao().getCoursesBySemesterSync(42).sortedBy { it.id })
                    if (withAssistant) {
                        assertEquals(conversation, upgraded.assistantConversationDao().conversation(conversation.id))
                        assertEquals(listOf(message), upgraded.assistantConversationDao().messages(conversation.id, 10))
                    } else {
                        assertTrue(upgraded.assistantConversationDao().conversations(42).isEmpty())
                    }
                }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionOneMigrationKeepsOriginalCoursesDatesAndReminders(): Unit = runBlocking {
        val name = "assistant-migration-v1-test"
        context.deleteDatabase(name)
        try {
            val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE semesters (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, startDate INTEGER NOT NULL, totalWeeks INTEGER NOT NULL, isCurrent INTEGER NOT NULL, createTime INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseName TEXT NOT NULL, teacher TEXT NOT NULL, classroom TEXT NOT NULL, dayOfWeek INTEGER NOT NULL, startSection INTEGER NOT NULL, endSection INTEGER NOT NULL, startWeek INTEGER NOT NULL, endWeek INTEGER NOT NULL, weekType INTEGER NOT NULL, semesterId INTEGER NOT NULL, colorIndex INTEGER NOT NULL, note TEXT NOT NULL, reminderMinutes INTEGER NOT NULL, createTime INTEGER NOT NULL)")
                old.execSQL("INSERT INTO semesters VALUES (42, '原学期', 1000, 16, 1, 100)")
                old.execSQL("INSERT INTO courses VALUES (17, '原课程', '原老师', 'A101', 3, 1, 2, 1, 16, 0, 42, 8, '原备注', 15, 1234)")
                old.version = 1
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build().use { upgraded ->
                    assertEquals(Semester(id = 42, name = "原学期", startDate = 1000,
                        totalWeeks = 16, isCurrent = true, createTime = 100), upgraded.semesterDao().getCurrentSemesterSync())
                    assertEquals(Course(id = 17, courseName = "原课程", teacher = "原老师", classroom = "A101",
                        dayOfWeek = 3, startSection = 1, endSection = 2, startWeek = 1, endWeek = 16,
                        semesterId = 42, colorIndex = 8, note = "原备注", reminderMinutes = 15, createTime = 1234),
                        upgraded.courseDao().getCourseById(17))
                    val conversation = com.courseschedule.data.entity.AssistantConversation("migration-chat", 42,
                        "保留课程", 1234)
                    upgraded.assistantConversationDao().insertConversation(conversation)
                    upgraded.assistantConversationDao().insertMessage(com.courseschedule.data.entity.AssistantChatMessage(
                        conversationId = conversation.id, role = "user", content = "周三有哪些课？"))
                    assertEquals(1, upgraded.assistantConversationDao().messageCount(conversation.id))
                    upgraded.semesterDao().deleteSemester(upgraded.semesterDao().getSemesterById(42)!!)
                    assertNull(upgraded.assistantConversationDao().conversation(conversation.id))
                    assertEquals(0, upgraded.assistantConversationDao().messageCount(conversation.id))
                }
        } finally { context.deleteDatabase(name) }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun <T> AppDatabase.use(block: (AppDatabase) -> T): T =
        try { block(this) } finally { close() }

    private fun open(name: String, initialize: Boolean = true): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .apply { if (initialize) addCallback(AppDatabase.DatabaseCallback()) }
            .build()

    @Test fun freshDatabaseHasCurrentSemesterOnItsFirstQuery(): Unit = runBlocking {
        val name = "startup-fresh-test"
        context.deleteDatabase(name)
        try {
            open(name).use { database ->
                val semester = database.semesterDao().getCurrentSemesterSync()
                assertNotNull("The first query must not wait for background initialization", semester)
                assertEquals(20, semester!!.totalWeeks)
                val date = Calendar.getInstance().apply { timeInMillis = semester.startDate }
                assertEquals(Calendar.MONDAY, date.get(Calendar.DAY_OF_WEEK))
                assertEquals(0, date.get(Calendar.HOUR_OF_DAY))
                assertEquals(0, date.get(Calendar.MINUTE))
            }
            open(name).use { database ->
                val count = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM semesters")
                    .use { it.moveToFirst(); it.getInt(0) }
                assertEquals("Reopening must not create more default semesters", 1, count)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun existingEmptyDatabaseRecoversWithoutRequiringOnCreate(): Unit = runBlocking {
        val name = "startup-empty-test"
        context.deleteDatabase(name)
        try {
            open(name, initialize = false).use { database ->
                assertNull(database.semesterDao().getCurrentSemesterSync())
            }
            open(name).use { database -> assertNotNull(database.semesterDao().getCurrentSemesterSync()) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun restoresNewestSemesterAndKeepsCoursesAndUserDates(): Unit = runBlocking {
        val name = "startup-existing-test"
        context.deleteDatabase(name)
        val old = Semester(id = 8, name = "旧学期", startDate = 1000, totalWeeks = 18)
        val recent = Semester(id = 12, name = "本学期", startDate = 2000, totalWeeks = 16)
        val course = Course(id = 5, courseName = "高等数学", dayOfWeek = 2, startSection = 1,
            endSection = 2, startWeek = 1, endWeek = 16, semesterId = recent.id)
        try {
            open(name, initialize = false).use { database ->
                database.semesterDao().insertSemester(old)
                database.semesterDao().insertSemester(recent)
                database.courseDao().insertCourse(course)
                assertNull(database.semesterDao().getCurrentSemesterSync())
            }
            open(name).use { database ->
                assertEquals(recent.copy(isCurrent = true), database.semesterDao().getCurrentSemesterSync())
                assertEquals(old, database.semesterDao().getSemesterById(old.id))
                assertEquals(listOf(course), database.courseDao().getCoursesBySemesterSync(recent.id))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun keepsExplicitCurrentSemesterEvenWhenAnotherSemesterIsNewer(): Unit = runBlocking {
        val name = "startup-current-test"
        context.deleteDatabase(name)
        val selected = Semester(id = 3, name = "用户选定学期", startDate = 1000, isCurrent = true)
        try {
            open(name, initialize = false).use { database ->
                database.semesterDao().insertSemester(selected)
                database.semesterDao().insertSemester(Semester(id = 4, name = "新学期", startDate = 2000))
            }
            open(name).use { database -> assertEquals(selected, database.semesterDao().getCurrentSemesterSync()) }
        } finally { context.deleteDatabase(name) }
    }
}
