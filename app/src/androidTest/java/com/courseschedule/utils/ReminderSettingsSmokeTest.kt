package com.courseschedule.utils

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.ui.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 可用于已有数据的安装：只读取状态并发送一条测试通知，不更改课程和设置。 */
@RunWith(AndroidJUnit4::class)
class ReminderSettingsSmokeTest {
    @Test fun settingsAndTestNotificationWorkWithoutChangingCourses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            SystemClock.sleep(2500)
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.tvReminderStatus)
                assertTrue(status.text.toString(), status.text.contains("条课程记录"))
                assertFalse(status.text.toString(), status.text.contains("失败"))
                status.requestRectangleOnScreen(Rect(0, 0, status.width, status.height), true)
                assertTrue(AlarmReceiver.notificationsAvailable(activity))
                activity.findViewById<View>(R.id.buttonReminderTest).performClick()
            }
            SystemClock.sleep(15_000)
            val manager = context.getSystemService(NotificationManager::class.java)
            assertNotNull("Scheduled test notification should arrive", manager.activeNotifications.firstOrNull { it.tag == "test" })
        }
    }
}
