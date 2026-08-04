package com.courseschedule.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "course_reminder_channel"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val remindersEnabled = SchedulePreferences(context).reminderEnabled
        if (!remindersEnabled) return

        val courseId = intent.getLongExtra("course_id", 0L)
        val courseName = intent.getStringExtra("course_name") ?: "课程"
        val classroom = intent.getStringExtra("classroom").orEmpty()
        createNotificationChannel(context)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = buildString {
            append(courseName)
            append("即将开始")
            if (classroom.isNotBlank()) append("，教室：$classroom")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("课程提醒")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID_BASE + Math.floorMod(courseId, 100_000L).toInt(),
            notification
        )

        if (courseId > 0) scheduleNextReminder(context, courseId)
    }

    private fun scheduleNextReminder(context: Context, courseId: Long) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context)
                val course = database.courseDao().getCourseById(courseId) ?: return@launch
                val semester = database.semesterDao().getSemesterById(course.semesterId) ?: return@launch
                ReminderManager(context).setReminder(course, semester)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "课程提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "课程开始前的提醒通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
