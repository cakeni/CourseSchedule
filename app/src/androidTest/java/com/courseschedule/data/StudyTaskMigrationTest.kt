package com.courseschedule.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.entity.StudyTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyTaskMigrationTest {
    @Test fun versionThreeUpgradeKeepsCourseConversationAndReminderThenSupportsTaskAssociations(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "study-tasks-v3-migration"
        context.deleteDatabase(name)
        try {
            val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE semesters (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, startDate INTEGER NOT NULL, totalWeeks INTEGER NOT NULL, isCurrent INTEGER NOT NULL, createTime INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseName TEXT NOT NULL, teacher TEXT NOT NULL, classroom TEXT NOT NULL, dayOfWeek INTEGER NOT NULL, startSection INTEGER NOT NULL, endSection INTEGER NOT NULL, startWeek INTEGER NOT NULL, endWeek INTEGER NOT NULL, weekType INTEGER NOT NULL, semesterId INTEGER NOT NULL, colorIndex INTEGER NOT NULL, note TEXT NOT NULL, reminderMinutes INTEGER NOT NULL, createTime INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE assistant_conversations (id TEXT NOT NULL PRIMARY KEY, semesterId INTEGER NOT NULL, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, stateJson TEXT NOT NULL, revision INTEGER NOT NULL, FOREIGN KEY(semesterId) REFERENCES semesters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_assistant_conversations_semesterId ON assistant_conversations(semesterId)")
                old.execSQL("CREATE TABLE assistant_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, kind TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES assistant_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_assistant_messages_conversationId ON assistant_messages(conversationId)")
                old.execSQL("INSERT INTO semesters VALUES (42, '原学期', 1000, 20, 1, 100)")
                old.execSQL("INSERT INTO courses VALUES (17, '原课程', '教师', 'B302', 4, 1, 2, 1, 20, 0, 42, 8, '原备注', 15, 1234)")
                old.execSQL("INSERT INTO assistant_conversations VALUES ('kept-chat', 42, '原聊天', 1234, '{}', 0)")
                old.execSQL("INSERT INTO assistant_messages VALUES (9, 'kept-chat', 'user', '原聊天内容', 'chat', 1234)")
                old.version = 3
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_3_4).build()
            try {
                assertEquals("原学期", db.semesterDao().getCurrentSemesterSync()!!.name)
                assertEquals(15, db.courseDao().getCourseById(17)!!.reminderMinutes)
                assertEquals("原聊天内容", db.assistantConversationDao().messages("kept-chat", 10).single().content)
                val task = StudyTask(semesterId = 42, courseId = 17, courseName = "原课程", title = "作业", dueAt = 1791100800000L)
                    .let { it.copy(id = db.studyTaskDao().insert(it)) }
                val course = db.courseDao().getCourseById(17)!!
                db.courseDao().updateCourse(course.copy(courseName = "新课程名"))
                assertEquals("新课程名", db.studyTaskDao().find(task.id)!!.courseName)
                db.courseDao().insertCourse(course.copy(courseName = "替换后课程名"))
                assertEquals("替换后课程名", db.studyTaskDao().find(task.id)!!.courseName)
                db.courseDao().deleteCourseById(course.id)
                assertEquals(task.dueAt, db.studyTaskDao().find(task.id)!!.dueAt)
                db.semesterDao().deleteSemester(db.semesterDao().getSemesterById(42)!!)
                assertNull(db.studyTaskDao().find(task.id))
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
