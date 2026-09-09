package com.courseschedule.view

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.utils.SchedulePreferences
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
        val density = view.resources.displayMetrics.density
        val timeColumnWidth = 48f * density
        val dayWidth = (view.width - timeColumnWidth) / 7f
        val x = timeColumnWidth + (day - 0.5f) * dayWidth
        val y = (section - 0.5f) * 64f * density
        val downTime = SystemClock.uptimeMillis()
        listOf(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(downTime, downTime + 10L, MotionEvent.ACTION_UP, x, y, 0)
        ).forEach { event ->
            try {
                view.onTouchEvent(event)
            } finally {
                event.recycle()
            }
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
