package com.courseschedule

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.courseschedule.utils.SchedulePreferences
import app.rive.runtime.kotlin.core.Rive

/**
 * 应用程序类
 */
class CourseScheduleApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "course_reminder_channel"
    }

    override fun onCreate() {
        super.onCreate()

        AppCompatDelegate.setDefaultNightMode(
            when (SchedulePreferences(this).darkModeOverride) {
                true -> AppCompatDelegate.MODE_NIGHT_YES
                false -> AppCompatDelegate.MODE_NIGHT_NO
                null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        runCatching { Rive.init(this) }

        // 创建通知渠道
        createNotificationChannel()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "课程提醒"
            val descriptionText = "课程开始前的提醒通知"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
