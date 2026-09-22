package com.courseschedule.utils

import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.R
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import java.util.Calendar
import java.util.Locale

/** 只在独立测试包运行，绝不覆盖用户课程。 */
@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val manager by lazy { ReminderManager(context) }
    private val notifications by lazy { context.getSystemService(NotificationManager::class.java) }
    private lateinit var course: Course
    private lateinit var semester: Semester
    private var classStart = 0L

    @Before fun setup() = runBlocking {
        assumeTrue("Use isolated-test.gradle", context.packageName == "com.courseschedule.remindertest")
        // 初始化数据库后等待其默认学期回调完成，再建立完全独立的测试数据。
        database.courseDao().getAllCoursesSync()
        SystemClock.sleep(500)
        database.courseDao().getAllCoursesSync().forEach { manager.cancelReminder(it.id) }
        database.clearAllTables()
        notifications.cancelAll()
        context.getSharedPreferences("reminder_delivery", 0).edit().clear().commit()
        val start = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 3)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (get(Calendar.HOUR_OF_DAY) == 23 && get(Calendar.MINUTE) == 59) add(Calendar.MINUTE, 1)
        }
        classStart = start.timeInMillis
        val day = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        val monday = (start.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1 - day)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }
        val preferences = SchedulePreferences(context)
        preferences.reminderEnabled = true
        val minute = start.get(Calendar.HOUR_OF_DAY) * 60 + start.get(Calendar.MINUTE)
        val firstMinute = minOf(minute, 1427)
        fun time(value: Int) = "%02d:%02d".format(Locale.US, value / 60, value % 60)
        preferences.setSectionTimes(List(12) { time(firstMinute + it) },
            List(12) { time(firstMinute + it + 1) })
        semester = Semester(name = "提醒验证", startDate = monday.timeInMillis, totalWeeks = 2, isCurrent = true)
        semester = semester.copy(id = database.semesterDao().insertSemester(semester))
        course = Course(courseName = "提醒验证课程", classroom = "测试教室", dayOfWeek = day,
            startSection = minute - firstMinute + 1, endSection = minute - firstMinute + 1, startWeek = 1, endWeek = 2,
            semesterId = semester.id, reminderMinutes = 5)
        course = course.copy(id = database.courseDao().insertCourse(course))
    }

    @After fun cleanup() = runBlocking {
        if (context.packageName == "com.courseschedule.remindertest") {
            database.courseDao().getAllCoursesSync().forEach { manager.cancelReminder(it.id) }
            manager.cancelReminder(-1)
            notifications.cancelAll()
        }
    }

    private fun deliver(start: Long = classStart) {
        PendingIntent.getBroadcast(context, 900_000,
            Intent(context, AlarmReceiver::class.java).putExtra("course_id", course.id).putExtra("class_start", start),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE).send()
        SystemClock.sleep(1500)
    }

    private fun notification() = notifications.activeNotifications.firstOrNull { it.tag == "course_${course.id}" }

    @Test fun realAlarmCatchesUpAndDoesNotRepeatAfterRestore() = runBlocking {
        assertTrue(AlarmReceiver.notificationsAvailable(context))
        manager.setReminder(course, semester)
        // 此设备的 AlarmManager min_futurity=5s，留出广播处理时间。
        SystemClock.sleep(8000)
        val first = notification()
        assertNotNull("An actual AlarmManager broadcast must post a notification", first)
        assertEquals(classStart, manager.lastDelivered(course.id))
        manager.restoreReminders()
        deliver()
        assertEquals(first!!.postTime, notification()!!.postTime)
    }

    @Test fun disabledDeletedAndOldSemesterAlarmsDoNotNotify() = runBlocking {
        database.courseDao().updateCourse(course.copy(reminderMinutes = -1))
        deliver()
        assertNull(notification())
        database.courseDao().updateCourse(course)
        database.semesterDao().updateSemester(semester.copy(isCurrent = false))
        deliver()
        assertNull(notification())
        database.semesterDao().updateSemester(semester)
        database.courseDao().deleteCourseById(course.id)
        deliver()
        assertNull(notification())
    }

    @Test fun staleOrExpiredBroadcastDoesNotShowOldCourseContent() = runBlocking {
        // 先记为已提醒，避免无效广播的自愈逻辑触发有效补发，单独验证旧广播被拒绝。
        manager.markDelivered(course.id, classStart)
        deliver(classStart - 7L * 24 * 60 * 60 * 1000)
        assertNull(notification())
        deliver(classStart + 60_000)
        assertNull(notification())
    }

    @Test fun globalOffCancelsPendingCourse() = runBlocking {
        SchedulePreferences(context).reminderEnabled = false
        manager.restoreReminders()
        deliver()
        assertNull(notification())
        assertEquals(0L, manager.lastDelivered(course.id))
    }

    @Test fun reopeningAppRepairsCanceledAlarm() = runBlocking {
        manager.cancelReminder(course.id)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            SystemClock.sleep(8000)
            assertNotNull(notification())
        }
    }

    @Test fun blockedNotificationDoesNotMarkDeliveredAndCanRecover() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("blockNotifications") == "true")
        assertFalse(AlarmReceiver.notificationsAvailable(context))
        deliver()
        assertEquals(0L, manager.lastDelivered(course.id))
        assertNull(notification())
        assertNotNull(PendingIntent.getBroadcast(context, course.id.toInt(), Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test fun prepareBlockedNotificationTest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prepareBlocked") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        // 单独执行；测试应用主动放弃自己的通知权限，不需要修改手机安全设置。
        context.revokeSelfPermissionOnKill(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun canScheduleWithoutExactPermission() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inexact") == "true")
        assertFalse(manager.canScheduleExactAlarms())
        manager.setReminder(course.copy(reminderMinutes = 1), semester)
        assertNotNull(PendingIntent.getBroadcast(context, course.id.toInt(), Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test fun settingsExposeCourseCountAndNextReminder(): Unit = runBlocking {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            SystemClock.sleep(2500)
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.tvReminderStatus).text.toString()
                assertTrue(text, text.contains("1 条课程记录，1 条已开启提醒"))
                assertTrue(text, text.contains("下次提醒"))
            }
        }
    }
}
