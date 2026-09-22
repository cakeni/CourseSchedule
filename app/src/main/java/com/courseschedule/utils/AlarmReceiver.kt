package com.courseschedule.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.domain.ReminderTimeCalculator
import com.courseschedule.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val CHANNEL_ID = "course_reminder_channel"
        private const val TAG = "AlarmReceiver"

        fun notificationsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.getBooleanExtra("test_reminder", false)) {
                    showNotification(context, "test", context.getString(R.string.reminder_test_content))
                    return@launch
                }
                ReminderManager.schedulingMutex.withLock {
                    deliverCourseReminder(context, intent)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Reminder delivery/rescheduling failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun deliverCourseReminder(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra("course_id", 0L)
        if (courseId <= 0) return
        val manager = ReminderManager(context)
        val database = AppDatabase.getDatabase(context)
        val course = database.courseDao().getCourseById(courseId)
        val semester = course?.let { database.semesterDao().getSemesterById(it.semesterId) }
        if (course == null || semester == null || !semester.isCurrent ||
            course.reminderMinutes <= 0 || !SchedulePreferences(context).reminderEnabled) {
            manager.cancelReminder(courseId)
            return
        }
        val classStart = intent.getLongExtra("class_start", 0L)
        val now = System.currentTimeMillis()
        val expected = ReminderTimeCalculator.nextOccurrence(course, semester,
            SchedulePreferences(context).sectionTimes,
            now = classStart - course.reminderMinutes * 60_000L - 1L)
        val valid = classStart > now && expected?.classStart == classStart &&
            now >= classStart - course.reminderMinutes * 60_000L &&
            manager.lastDelivered(courseId) < classStart
        var delivered = false
        try {
            if (valid) {
                val content = buildString {
                    append(course.courseName)
                    append("即将开始")
                    if (course.classroom.isNotBlank()) append("，教室：${course.classroom}")
                }
                delivered = showNotification(context, "course_$courseId", content)
                if (delivered) manager.markDelivered(courseId, classStart)
            } else {
                Log.i(TAG, "Skipped stale, duplicate or expired course=$courseId")
            }
        } finally {
            // 通知受阻仍排下一次；用户恢复权限并回到应用时可补发尚未上课的本次提醒。
            manager.setReminder(course, semester,
                now = if (valid && !delivered) classStart else System.currentTimeMillis())
        }
    }

    private fun showNotification(context: Context, tag: String, content: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "课程提醒",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "课程开始前的提醒通知"
            enableVibration(true)
        })
        if (!notificationsAvailable(context)) {
            Log.w(TAG, "Notification blocked tag=$tag")
            return false
        }
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (tag == "test") R.string.reminder_test_title else R.string.course_reminders))
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()
        return try {
            manager.notify(tag, 1000, notification)
            Log.i(TAG, "Notification posted tag=$tag")
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission changed", error)
            false
        }
    }
}
