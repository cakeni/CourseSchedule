package com.courseschedule.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.courseschedule.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机启动接收器 - 重新设置所有课程提醒
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (!SchedulePreferences(context).reminderEnabled) return
            val pendingResult = goAsync()
            // 重新设置所有提醒
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = AppDatabase.getDatabase(context)
                    val semester = database.semesterDao().getCurrentSemesterSync()
                    semester?.let {
                        val courses = database.courseDao().getCoursesBySemesterSync(it.id)
                        ReminderManager(context).rescheduleReminders(courses, it)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
