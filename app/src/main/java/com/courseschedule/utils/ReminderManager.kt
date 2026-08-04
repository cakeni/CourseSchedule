package com.courseschedule.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ReminderTimeCalculator

/**
 * 提醒管理器 - 负责设置和取消课程提醒
 */
class ReminderManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "ReminderManager"
    }

    /**
     * 设置课程提醒
     * 根据学期开学日期找到最近一次真实上课时间作为提醒点
     */
    fun setReminder(course: Course, semester: Semester) {
        if (course.reminderMinutes <= 0 || !SchedulePreferences(context).reminderEnabled) return

        val reminderTime = ReminderTimeCalculator.nextReminderTime(
            course,
            semester,
            SchedulePreferences(context).sectionTimes
        ) ?: return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("course_id", course.id)
            putExtra("course_name", course.courseName)
            putExtra("classroom", course.classroom)
            putExtra("section", course.startSection)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            course.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 设置闹钟
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminderTime,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // 没有精确闹钟权限，使用普通闹钟
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                reminderTime,
                pendingIntent
            )
        }
    }

    /**
     * 取消课程提醒
     */
    fun cancelReminder(courseId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            courseId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    /**
     * 取消所有提醒
     */
    fun cancelAllReminders(courses: List<Course>) {
        courses.forEach { cancelReminder(it.id) }
    }

    /**
     * 重新设置所有提醒
     */
    fun rescheduleReminders(courses: List<Course>, semester: Semester) {
        courses.forEach { setReminder(it, semester) }
    }
}
