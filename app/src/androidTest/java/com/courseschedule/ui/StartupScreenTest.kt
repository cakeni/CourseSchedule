package com.courseschedule.ui

import android.graphics.Bitmap
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.viewmodel.CourseViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StartupScreenTest {
    @Test fun loadedSemesterDisplaysDateDayHeadersAndSchedule() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            lateinit var model: CourseViewModel
            val observer = androidx.lifecycle.Observer<com.courseschedule.data.entity.Semester?> {
                if (it != null) ready.countDown()
            }
            scenario.onActivity {
                model = ViewModelProvider(it)[CourseViewModel::class.java]
                model.currentSemester.observeForever(observer)
            }
            try {
                assertTrue("Current semester must be ready", ready.await(15, TimeUnit.SECONDS))
                onView(withId(R.id.weekPager)).check(matches(isDisplayed()))
                onView(withId(R.id.dateHeader)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val header = activity.findViewById<DateHeaderView>(R.id.dateHeader)
                    assertTrue("Date title must not be blank", header.dateText.isNotBlank())
                    assertTrue(header.summaryText.isNotBlank())
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    assertEquals(View.VISIBLE, pager.visibility)
                    assertTrue("Schedule must have pages", (pager.adapter?.itemCount ?: 0) > 0)
                }
                onView(org.hamcrest.Matchers.allOf(withId(R.id.courseTableView), isDisplayed())).check(matches(isDisplayed()))
                onView(org.hamcrest.Matchers.allOf(withId(R.id.weekDayHeader), isDisplayed())).check(matches(isDisplayed()))
                onView(withId(R.id.tvWeekContext)).check(matches(isDisplayed()))
                instrumentation.waitForIdleSync()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "startup-screen.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            } finally {
                instrumentation.runOnMainSync { model.currentSemester.removeObserver(observer) }
            }
        }
    }
}
