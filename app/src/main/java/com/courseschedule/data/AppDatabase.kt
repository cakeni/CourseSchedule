package com.courseschedule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.courseschedule.data.dao.CourseDao
import com.courseschedule.data.dao.SemesterDao
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 应用数据库
 */
@Database(
    entities = [Course::class, Semester::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "course_schedule_database"
                )
                    .addCallback(DatabaseCallback())
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * 数据库创建回调 - 初始化默认学期
     */
    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    initDefaultSemester(database.semesterDao())
                }
            }
        }

        private suspend fun initDefaultSemester(semesterDao: SemesterDao) {
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

            val defaultSemester = Semester(
                name = semesterName,
                startDate = calendar.timeInMillis,
                totalWeeks = 20,
                isCurrent = true
            )

            semesterDao.insertSemester(defaultSemester)
        }
    }
}
