package com.courseschedule.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ReminderTimeCalculator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReminderManager(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val history = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "ReminderManager"
        internal val schedulingMutex = Mutex()
        internal const val TEST_REQUEST_CODE = -1
    }

    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        alarmManager.canScheduleExactAlarms()

    fun lastDelivered(courseId: Long): Long = history.getLong("delivered_$courseId", 0L)

    // 在广播的 IO 协程内同步落盘，确保进程退出后仍能去重。
    @SuppressLint("ApplySharedPref")
    fun markDelivered(courseId: Long, classStart: Long) {
        history.edit().putLong("delivered_$courseId", classStart).commit()
    }

    fun setReminder(course: Course, semester: Semester, now: Long = System.currentTimeMillis()) {
        if (!SchedulePreferences(context).reminderEnabled) {
            cancelReminder(course.id)
            return
        }
        val occurrence = ReminderTimeCalculator.nextOccurrence(
            course, semester, SchedulePreferences(context).sectionTimes, now,
            lastDeliveredClassStart = lastDelivered(course.id)
        )
        if (occurrence == null) {
            cancelReminder(course.id)
            return
        }
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("course_id", course.id)
            putExtra("class_start", occurrence.classStart)
        }
        schedule(occurrence.reminderTime, pendingIntent(course.id.toInt(), intent))
        Log.i(TAG, "Scheduled course=${course.id} classStart=${occurrence.classStart} trigger=${occurrence.reminderTime}")
    }

    private fun pendingIntent(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(time: Long, pendingIntent: PendingIntent) {
        if (canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                return
            } catch (error: SecurityException) {
                Log.w(TAG, "Exact alarm permission changed; using idle-compatible fallback", error)
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
    }

    fun scheduleTestReminder() {
        val intent = Intent(context, AlarmReceiver::class.java).putExtra("test_reminder", true)
        schedule(System.currentTimeMillis() + 10_000L, pendingIntent(TEST_REQUEST_CODE, intent))
    }

    fun cancelReminder(courseId: Long) {
        val pending = PendingIntent.getBroadcast(context, courseId.toInt(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    fun cancelAllReminders(courses: List<Course>) = courses.forEach { cancelReminder(it.id) }

    fun rescheduleReminders(courses: List<Course>, semester: Semester) {
        courses.forEach { setReminder(it, semester) }
    }

    /** 从数据库恢复，避免页面首次加载时 LiveData 尚未就绪而跳过排程。 */
    suspend fun restoreReminders() = schedulingMutex.withLock {
        val database = AppDatabase.getDatabase(context)
        val semester = database.semesterDao().getCurrentSemesterSync()
        database.courseDao().getAllCoursesSync().forEach { course ->
            if (semester != null && course.semesterId == semester.id) {
                setReminder(course, semester)
            } else {
                cancelReminder(course.id)
            }
        }
    }
}
