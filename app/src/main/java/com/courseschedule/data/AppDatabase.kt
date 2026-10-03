package com.courseschedule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.courseschedule.data.dao.CourseDao
import com.courseschedule.data.dao.SemesterDao
import com.courseschedule.data.dao.AssistantConversationDao
import com.courseschedule.data.entity.AssistantConversation
import com.courseschedule.data.entity.AssistantChatMessage
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.data.dao.StudyTaskDao
import com.courseschedule.domain.AiCourseColors
import java.util.Calendar

/**
 * 应用数据库
 */
@Database(
    entities = [Course::class, Semester::class, AssistantConversation::class, AssistantChatMessage::class, StudyTask::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
    abstract fun assistantConversationDao(): AssistantConversationDao
    abstract fun studyTaskDao(): StudyTaskDao

    companion object {
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS study_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, semesterId INTEGER NOT NULL, courseId INTEGER, courseName TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, dueAt INTEGER NOT NULL, reminderMinutes INTEGER NOT NULL, note TEXT NOT NULL, completedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(semesterId) REFERENCES semesters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_study_tasks_semesterId ON study_tasks(semesterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_study_tasks_dueAt ON study_tasks(dueAt)")
                createStudyCourseTriggers(db)
            }
        }

        private fun createStudyCourseTriggers(db: SupportSQLiteDatabase) {
            // Course inserts also cover the existing DAO's INSERT OR REPLACE edit path.
            for ((name, event) in listOf("insert" to "INSERT", "update" to "UPDATE OF courseName")) {
                db.execSQL("CREATE TRIGGER IF NOT EXISTS study_course_name_$name AFTER $event ON courses BEGIN UPDATE study_tasks SET courseName = NEW.courseName WHERE courseId = NEW.id AND semesterId = NEW.semesterId; END")
            }
        }
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep the earliest AI block's color for each course in its own semester.
                db.execSQL("""
                    UPDATE courses SET colorIndex = (
                        SELECT ((first.colorIndex % 16) + 16) % 16
                        FROM courses AS first
                        WHERE first.semesterId = courses.semesterId
                          AND trim(first.courseName) = trim(courses.courseName)
                          AND first.note IN (?, ?)
                        ORDER BY first.id LIMIT 1
                    ) WHERE note IN (?, ?)
                """.trimIndent(), arrayOf(AiCourseColors.DEEPSEEK_NOTE, AiCourseColors.OPENAI_NOTE,
                    AiCourseColors.DEEPSEEK_NOTE, AiCourseColors.OPENAI_NOTE))
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The locally distributed assistant build also used version 2, before
                // the main branch's color migration. Preserve both version-2 schemas.
                val hasAssistantTables = db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'assistant_conversations'")
                    .use { it.moveToFirst() }
                if (hasAssistantTables) MIGRATION_1_2.migrate(db)
                db.execSQL("CREATE TABLE IF NOT EXISTS assistant_conversations (id TEXT NOT NULL PRIMARY KEY, semesterId INTEGER NOT NULL, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, stateJson TEXT NOT NULL, revision INTEGER NOT NULL, FOREIGN KEY(semesterId) REFERENCES semesters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assistant_conversations_semesterId ON assistant_conversations(semesterId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS assistant_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, kind TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES assistant_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assistant_messages_conversationId ON assistant_messages(conversationId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "course_schedule_database"
                )
                    .addCallback(DatabaseCallback())
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * 数据库打开时，确保第一次查询就能取得当前学期。
     */
    internal class DatabaseCallback : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            createStudyCourseTriggers(db)
            db.beginTransaction()
            try {
                val hasCurrent = db.query("SELECT 1 FROM semesters WHERE isCurrent = 1 LIMIT 1")
                    .use { it.moveToFirst() }
                if (!hasCurrent) {
                    val existingId = db.query("SELECT id FROM semesters ORDER BY startDate DESC, id DESC LIMIT 1")
                        .use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (existingId != null) {
                        db.execSQL("UPDATE semesters SET isCurrent = 1 WHERE id = ?", arrayOf(existingId))
                    } else {
                        val semester = defaultSemester()
                        db.execSQL(
                            "INSERT INTO semesters (name, startDate, totalWeeks, isCurrent, createTime) VALUES (?, ?, ?, 1, ?)",
                            arrayOf(semester.name, semester.startDate, semester.totalWeeks, semester.createTime)
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private fun defaultSemester(): Semester {
            // 创建默认学期
            val calendar = Calendar.getInstance()
            val currentYear = calendar.get(Calendar.YEAR)
            val currentMonth = calendar.get(Calendar.MONTH) + 1

            // 根据当前月份确定学期
            val semesterName: String
            val startMonth: Int

            if (currentMonth >= 9) {
                // 秋季学期
                semesterName = "${currentYear}-${currentYear + 1} 第一学期"
                startMonth = 9
            } else if (currentMonth >= 2) {
                // 春季学期
                semesterName = "${currentYear - 1}-${currentYear} 第二学期"
                startMonth = 2
            } else {
                // 寒假，显示上一秋季学期
                semesterName = "${currentYear - 1}-${currentYear} 第一学期"
                startMonth = 9
                calendar.set(Calendar.YEAR, currentYear - 1)
            }

            calendar.set(Calendar.MONTH, startMonth - 1)
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            // 设置为周一
            while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            return Semester(
                name = semesterName,
                startDate = calendar.timeInMillis,
                totalWeeks = 20,
                isCurrent = true
            )
        }
    }
}
