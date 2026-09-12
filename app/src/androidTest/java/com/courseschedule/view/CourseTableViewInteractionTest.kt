package com.courseschedule.view

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.addcourse.AddCourseActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.utils.SchedulePreferences
import com.google.android.material.chip.Chip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseTableViewInteractionTest {

    @Test fun overlapSelectsCurrentWeekThenNearestInactiveCourse() {
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = attachedTable(activity.findViewById(android.R.id.content))
                val far = course("较远非本周", 10)
                val near = course("较近非本周", 7)
                val past = course("已经结束", 3)
                val current = course("本周", 5)
                var selected: Course? = null
                view.setCurrentWeek(5)
                view.setOnCourseClickListener { course, _, _ -> selected = course }

                view.setCourses(
                    ScheduleRules.selectCoursesForWeek(listOf(current, near, far), 5, true)
                )
                tap(view, day = 1, section = 1)
                assertEquals(current, selected)

                selected = null
                view.setCourses(
                    ScheduleRules.selectCoursesForWeek(listOf(far, near, past), 5, true)
                )
                tap(view, day = 1, section = 1)
                assertEquals(near, selected)

                selected = null
                var emptySlot: Pair<Int, Int>? = null
                view.setOnEmptySlotClickListener { day, section -> emptySlot = day to section }
                view.setCourses(ScheduleRules.selectCoursesForWeek(listOf(past), 5, true))
                tap(view, day = 1, section = 1)
                assertNull(selected)
                assertEquals(1 to 1, emptySlot)
            }
        }
    }

    @Test fun blankSlotReportsItsDayAndSection() {
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = attachedTable(activity.findViewById(android.R.id.content))
                var selected: Pair<Int, Int>? = null
                view.setOnEmptySlotClickListener { day, section -> selected = day to section }

                tap(view, day = 4, section = 3)

                assertEquals(4 to 3, selected)
            }
        }
    }

    @Test fun longPressDragThenPlusReportsSelectedRange() {
        lateinit var view: CourseTableView
        var selected: Triple<Int, Int, Int>? = null
        var downTime = 0L
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = attachedTable(activity.findViewById(android.R.id.content))
                view.setOnQuickAddCourseListener { day, start, end ->
                    selected = Triple(day, start, end)
                }
                downTime = SystemClock.uptimeMillis()
                send(
                    view,
                    MotionEvent.ACTION_DOWN,
                    day = 4,
                    section = 5,
                    downTime = downTime,
                    eventTime = downTime
                )
            }

            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                send(
                    view,
                    MotionEvent.ACTION_MOVE,
                    day = 4,
                    section = 3,
                    downTime = downTime,
                    eventTime = SystemClock.uptimeMillis()
                )
                send(
                    view,
                    MotionEvent.ACTION_UP,
                    day = 4,
                    section = 3,
                    downTime = downTime,
                    eventTime = SystemClock.uptimeMillis()
                )
            }

            SystemClock.sleep(200L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                tap(view, day = 4, section = 4)
                assertEquals(Triple(4, 3, 5), selected)
            }
        }
    }

    @Test fun rangeExtrasPrefillExistingCourseEditor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, AddCourseActivity::class.java).apply {
            putExtra(AddCourseActivity.EXTRA_DAY_OF_WEEK, 4)
            putExtra(AddCourseActivity.EXTRA_SECTION, 3)
            putExtra(AddCourseActivity.EXTRA_END_SECTION, 5)
        }
        ActivityScenario.launch<AddCourseActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(true, activity.findViewById<Chip>(R.id.chipThu).isChecked)
                assertEquals(
                    activity.getString(R.string.section_format, 3),
                    activity.findViewById<AutoCompleteTextView>(R.id.spinnerStartSection).text.toString()
                )
                assertEquals(
                    activity.getString(R.string.section_format, 5),
                    activity.findViewById<AutoCompleteTextView>(R.id.spinnerEndSection).text.toString()
                )
            }
        }
    }

    private fun attachedTable(parent: ViewGroup): CourseTableView {
        val density = parent.resources.displayMetrics.density
        val height = (64f * density * 12).toInt()
        return CourseTableView(parent.context).apply {
            applyDisplaySettings(true, true, 64, SchedulePreferences.DEFAULT_SECTION_TIMES)
            parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            val width = parent.width.takeIf { it > 0 } ?: parent.resources.displayMetrics.widthPixels
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, width, height)
        }
    }

    private fun tap(view: CourseTableView, day: Int, section: Int) {
        val downTime = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, day, section, downTime, downTime)
        send(view, MotionEvent.ACTION_UP, day, section, downTime, downTime + 10L)
    }

    private fun send(
        view: CourseTableView,
        action: Int,
        day: Int,
        section: Int,
        downTime: Long,
        eventTime: Long
    ) {
        val density = view.resources.displayMetrics.density
        val timeColumnWidth = 48f * density
        val dayWidth = (view.width - timeColumnWidth) / 7f
        val x = timeColumnWidth + (day - 0.5f) * dayWidth
        val y = (section - 0.5f) * 64f * density
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun course(name: String, week: Int) = Course(
        courseName = name,
        dayOfWeek = 1,
        startSection = 1,
        endSection = 1,
        startWeek = week,
        endWeek = week
    )
}
