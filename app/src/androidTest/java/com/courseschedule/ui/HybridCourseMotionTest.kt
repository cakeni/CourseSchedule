package com.courseschedule.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.WeekMotionStyle
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.view.CourseTableView
import com.courseschedule.view.EmptyCalendarView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HybridCourseMotionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }

    private suspend fun fixture(block: suspend (List<Course>) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val old = database.semesterDao().getCurrentSemesterSync()
        val preferences = SchedulePreferences(context)
        val settings = preferences.snapshot()
        val draft = Semester(name = "2026 秋季学期", startDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), totalWeeks = 20)
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft))
        fun course(name: String, day: Int, section: Int, color: Int, type: Int = 0) =
            Course(courseName = name, teacher = "王老师", classroom = "教一 302", dayOfWeek = day,
                startSection = section, endSection = section + 1, startWeek = 1, endWeek = 20,
                semesterId = semester.id, colorIndex = color, weekType = type)
        val drafts = listOf(
            course("高等数学", 1, 1, 2), course("大学英语", 3, 1, 5),
            course("大学物理", 2, 3, 8), course("程序设计", 4, 3, 14),
            course("高等数学", 5, 1, 2), course("大学英语", 1, 5, 5),
            course("程序设计", 3, 5, 14), course("体育", 5, 5, 10, 1),
            course("体育", 2, 5, 9, 2).copy(classroom = "教二 104"), course("线性代数", 2, 1, 6, 1),
            course("离散数学", 2, 1, 3, 2), course("创新实践", 4, 5, 9, 1),
            course("专业导论", 4, 5, 13, 2)
        )
        try {
            preferences.showWeekend = true
            preferences.showInactiveCourses = false
            preferences.sectionHeightDp = 64
            preferences.weekMotionStyle = WeekMotionStyle.CONTINUITY
            val ids = database.courseDao().insertCourses(drafts)
            database.semesterDao().switchCurrentSemester(semester.id)
            block(drafts.zip(ids) { course, id -> course.copy(id = id) })
        } finally {
            database.courseDao().deleteCoursesBySemester(semester.id)
            database.semesterDao().deleteSemester(semester)
            old?.let { database.semesterDao().switchCurrentSemester(it.id) }
            preferences.applySnapshot(settings)
        }
    }

    private fun settle(milliseconds: Long = 700L, waitForIdle: Boolean = true) {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, milliseconds)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        if (waitForIdle) instrumentation.waitForIdleSync()
    }

    private fun holder(pager: ViewPager2, position: Int = pager.currentItem): View =
        (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(position)!!.itemView

    private fun ready(scenario: ActivityScenario<MainActivity>, expectedCourses: Int = 10) {
        val loaded = CountDownLatch(1)
        scenario.onActivity { activity ->
            val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
            val observer = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (pager.visibility == View.VISIBLE && (pager.adapter?.itemCount ?: 0) == 20 &&
                        (pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(0)
                            ?.itemView?.findViewById<CourseTableView>(R.id.courseTableView)
                            ?.continuityCourses()?.size == expectedCourses) {
                        pager.viewTreeObserver.removeOnPreDrawListener(this)
                        loaded.countDown()
                    }
                    return true
                }
            }
            pager.viewTreeObserver.addOnPreDrawListener(observer)
        }
        assertTrue("Native timetable must finish loading", loaded.await(15, TimeUnit.SECONDS))
        settle()
    }

    @Test fun entirelyDifferentWeeksStillChangeAtGridPositionsAndReverseWithoutGhosts(): Unit = runBlocking {
        fixture { original ->
            val semesterId = original.first().semesterId
            val oldDrafts = listOf(
                original[0].copy(id = 0L, endWeek = 1),
                original[2].copy(id = 0L, endWeek = 1),
                original[11].copy(id = 0L, weekType = 0, endWeek = 1)
            )
            val newDrafts = listOf(
                oldDrafts[0].copy(courseName = "工程力学", startWeek = 2, endWeek = 20, colorIndex = 14),
                oldDrafts[1].copy(courseName = "综合英语", dayOfWeek = 5, startWeek = 2, endWeek = 20, colorIndex = 5),
                oldDrafts[2].copy(courseName = "线性代数", dayOfWeek = 3, startWeek = 2, endWeek = 20, colorIndex = 3)
            )
            val drafts = oldDrafts + newDrafts
            val ids = database.courseDao().replaceCoursesBySemester(semesterId, drafts)
            val lessons = drafts.zip(ids) { course, id -> course.copy(id = id) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario, expectedCourses = 3)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                lateinit var resting: RectF
                screenshot("all-changed-week-one")
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    val page = holder(pager)
                    resting = page.findViewById<CourseTableView>(R.id.courseTableView).continuityBounds(lessons[1])
                        .apply { offset(0f, page.findViewById<View>(R.id.weekDayHeader).height.toFloat()) }
                    assertTrue(pager.beginFakeDrag())
                    pager.fakeDragBy(-pager.width * 0.4f)
                }
                settle(220L, waitForIdle = false)
                scenario.onActivity { activity ->
                    assertTrue("These weeks have no shared course identities", overlay.sharedCourseBounds().isEmpty())
                    val changing = overlay.changingCourseBounds()
                    assertEquals("All unmatched courses must be on the stage", 5, changing.size)
                    val exiting = changing[lessons[1].id]!!
                    assertEquals("Unmatched cards must stay in their columns, rather than sliding a page width", resting.centerX(), exiting.centerX(), 0.1f)
                    if (ValueAnimator.areAnimatorsEnabled()) assertTrue("Leaving cards must visibly rise", exiting.top < resting.top - 4f)
                    val bitmap = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
                    overlay.draw(Canvas(bitmap))
                    val density = activity.resources.displayMetrics.density
                    val alpha = android.graphics.Color.alpha(bitmap.getPixel((exiting.left + 3f * density).toInt(), exiting.centerY().toInt()))
                    val firstDay = holder(pager, 0).findViewById<android.widget.TextView>(R.id.tvDay1)
                    val labelTop = firstDay.top + firstDay.totalPaddingTop
                    val labelBottom = labelTop + firstDay.layout.getLineBottom(0)
                    var weekdayPixels = 0
                    for (y in labelTop until labelBottom) for (x in firstDay.left until firstDay.right) {
                        if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) weekdayPixels++
                    }
                    var dateAlpha = 0
                    for (y in labelTop + firstDay.layout.getLineTop(1) until labelTop + firstDay.layout.getLineBottom(1)) {
                        for (x in firstDay.left until firstDay.right) {
                            dateAlpha = maxOf(dateAlpha, android.graphics.Color.alpha(bitmap.getPixel(x, y)))
                        }
                    }
                    bitmap.recycle()
                    assertTrue("An unmatched exiting card must actually be drawn at its fixed column", alpha in 150..180)
                    assertTrue("Weekday labels must remain drawn in the first course column during the transition", weekdayPixels > 10)
                    assertTrue("Old and new date numbers must not overlap while weekdays stay readable", dateAlpha in 1..40)
                    proof("all-changed", mapOf("sharedCourses" to 0, "changingCourses" to changing.size,
                        "horizontalDrift" to exiting.centerX() - resting.centerX(), "drawnAlpha" to alpha,
                        "weekdayPixels" to weekdayPixels, "dateAlpha" to dateAlpha))
                }
                screenshot("all-changed-midpoint")
                scenario.onActivity {
                    pager.fakeDragBy(pager.width * 0.4f)
                    pager.endFakeDrag()
                }
                settle(700L)
                scenario.onActivity {
                    assertEquals(0, pager.currentItem)
                    assertTrue(overlay.changingCourseBounds().isEmpty())
                }
                onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(700L)
                scenario.onActivity { assertEquals(1, pager.currentItem) }
                screenshot("all-changed-week-two")
            }
        }
    }

    @Test fun softSlideMovesTheWholeWeekKeepsTheAxisFixedAndCancelsCleanly(): Unit = runBlocking {
        fixture { courses ->
            SchedulePreferences(context).weekMotionStyle = WeekMotionStyle.SOFT_SLIDE
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                lateinit var original: RectF
                lateinit var restingAxis: IntArray
                screenshot("soft-slide-resting")
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    val table = holder(pager).findViewById<CourseTableView>(R.id.courseTableView)
                    original = table.continuityBounds(courses[4])
                    val axisBitmap = Bitmap.createBitmap(table.width, 400, Bitmap.Config.ARGB_8888)
                    table.draw(Canvas(axisBitmap))
                    restingAxis = IntArray(400 * holder(pager).findViewById<View>(R.id.tvMonthLabel).width)
                    axisBitmap.getPixels(restingAxis, 0, holder(pager).findViewById<View>(R.id.tvMonthLabel).width,
                        0, 0, holder(pager).findViewById<View>(R.id.tvMonthLabel).width, 400)
                    axisBitmap.recycle()
                    assertTrue(pager.beginFakeDrag())
                    pager.fakeDragBy(-pager.width * .4f)
                }
                settle(180L, waitForIdle = false)
                scenario.onActivity { activity ->
                    assertTrue(overlay.isChangingWeeks)
                    val old = overlay.slidingCourseBounds(false)
                    val incoming = overlay.slidingCourseBounds(true)
                    assertEquals(10, old.size)
                    assertEquals(10, incoming.size)
                    val moved = old[courses[4].id]!!
                    assertTrue("The whole week must clearly travel horizontally", moved.left < original.left - pager.width * .3f)
                    val originalOther = holder(pager).findViewById<CourseTableView>(R.id.courseTableView).continuityBounds(courses[2])
                    assertEquals("Every lesson must travel as one coherent sheet", original.centerX() - originalOther.centerX(),
                        (moved.centerX() - old[courses[2].id]!!.centerX()) / (moved.width() / original.width()), .1f)
                    val axisWidth = holder(pager).findViewById<View>(R.id.tvMonthLabel).width
                    val headerHeight = holder(pager).findViewById<View>(R.id.weekDayHeader).height
                    val bitmap = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
                    overlay.draw(Canvas(bitmap))
                    val actualAxis = IntArray(restingAxis.size)
                    bitmap.getPixels(actualAxis, 0, axisWidth, 0, headerHeight, axisWidth, 400)
                    assertTrue("The time axis must remain stationary and readable", restingAxis.contentEquals(actualAxis))
                    bitmap.recycle()
                    proof("soft-slide", mapOf("oldCourses" to old.size, "newCourses" to incoming.size,
                        "oldCardX" to moved.left, "axisUnchanged" to true))
                }
                screenshot("soft-slide-midpoint")
                scenario.onActivity { pager.fakeDragBy(pager.width * .4f); pager.endFakeDrag() }
                settle(650L)
                scenario.onActivity { assertEquals(0, pager.currentItem); assertFalse(overlay.isChangingWeeks) }
                val demo = InstrumentationRegistry.getArguments().getString("demoSwipes") == "true"
                repeat(if (demo) 3 else 1) {
                    onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(650L)
                    screenshot("soft-slide-week-two")
                    onView(withId(R.id.weekPager)).perform(swipeRight()); settle(650L)
                }
                scenario.onActivity { assertEquals(0, pager.currentItem); assertFalse(overlay.isChangingWeeks) }
            }
        }
    }

    @Test fun softSlideKeepsBothWeeksAlignedWhenTheirSavedVerticalScrollDiffers(): Unit = runBlocking {
        fixture { courses ->
            SchedulePreferences(context).weekMotionStyle = WeekMotionStyle.SOFT_SLIDE
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                var savedScroll = 0
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    val destination = holder(pager, 1).findViewById<androidx.core.widget.NestedScrollView>(R.id.scheduleScroll)
                    destination.scrollTo(0, 96)
                    savedScroll = destination.scrollY
                    assertTrue(savedScroll > 0)
                    assertTrue(pager.beginFakeDrag())
                    pager.fakeDragBy(-pager.width * .5f)
                }
                settle(180L, waitForIdle = false)
                scenario.onActivity {
                    val old = overlay.slidingCourseBounds(false)
                    val incoming = overlay.slidingCourseBounds(true)
                    courses.filter { it.weekType == 0 }.forEach { course ->
                        assertEquals("The same time slot must align across both sliding sheets",
                            old[course.id]!!.top, incoming[course.id]!!.top, 1f)
                    }
                    pager.fakeDragBy(pager.width * .5f)
                    pager.endFakeDrag()
                }
                settle(650L)
                scenario.onActivity {
                    assertEquals(0, pager.currentItem)
                    assertFalse(overlay.isChangingWeeks)
                    assertEquals(0, holder(pager).findViewById<androidx.core.widget.NestedScrollView>(R.id.scheduleScroll).scrollY)
                }
                onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(650L)
                scenario.onActivity {
                    assertEquals(1, pager.currentItem)
                    assertFalse(overlay.isChangingWeeks)
                    assertEquals(savedScroll, holder(pager).findViewById<androidx.core.widget.NestedScrollView>(R.id.scheduleScroll).scrollY)
                }
            }
        }
    }

    @Test fun emptyWeekContentArrivesOnceAndStaysSettledThroughRetargeting(): Unit = runBlocking {
        WeekMotionStyle.entries.forEach { style ->
            fixture { courses ->
                SchedulePreferences(context).weekMotionStyle = style
                val first = courses.take(2).map { it.copy(id = 0L, startWeek = 1, endWeek = 1) }
                val third = first.map { it.copy(id = 0L, startWeek = 3, endWeek = 20) }
                database.courseDao().replaceCoursesBySemester(courses.first().semesterId, first + third)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    ready(scenario, expectedCourses = 2)
                    lateinit var pager: ViewPager2
                    lateinit var listener: ViewTreeObserver.OnPreDrawListener
                    val failures = mutableListOf<String>()
                    var settledEmptyFrames = 0
                    var checking = false
                    scenario.onActivity { activity ->
                        pager = activity.findViewById(R.id.weekPager)
                        listener = ViewTreeObserver.OnPreDrawListener {
                            if (checking && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                                val page = holder(pager)
                                if (page.findViewById<CourseTableView>(R.id.courseTableView).continuityCourses().isEmpty()) {
                                    settledEmptyFrames++
                                    val empty = page.findViewById<View>(R.id.emptyState)
                                    if (empty.visibility != View.VISIBLE) failures += "Empty content disappeared after landing"
                                    listOf(R.id.emptyState, R.id.emptyIconContainer, R.id.tvEmptyTitle, R.id.btnEmptyAdd).forEach { id ->
                                        val view = page.findViewById<View>(id)
                                        if (view.alpha != 1f || view.translationX != 0f || view.translationY != 0f ||
                                            view.scaleX != 1f || view.scaleY != 1f) failures += "An empty-state element restarted its entrance"
                                    }
                                }
                            }
                            true
                        }
                        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
                        checking = true
                    }
                    try {
                        onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(800L)
                        screenshot("empty-arrived-${style.storedValue}")
                        scenario.onActivity {
                            assertEquals(1, pager.currentItem)
                            assertTrue(holder(pager).findViewById<View>(R.id.btnEmptyAdd).isClickable)
                        }
                        onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(650L)
                        onView(withId(R.id.weekPager)).perform(swipeRight()); settle(800L)
                        onView(withId(R.id.weekPager)).perform(swipeRight()); settle(650L)
                        scenario.onActivity { activity ->
                            assertEquals(0, pager.currentItem)
                            activity.findViewById<View>(R.id.btnNextWeek).performClick()
                            Handler(Looper.getMainLooper()).postDelayed({ activity.findViewById<View>(R.id.btnNextWeek).performClick() }, 85L)
                        }
                        settle(1000L)
                        scenario.onActivity {
                            assertEquals(2, pager.currentItem)
                            assertEquals(View.GONE, holder(pager).findViewById<View>(R.id.emptyState).visibility)
                            assertTrue("The rendered empty weeks must be observed", settledEmptyFrames > 0)
                            proof("empty-settled-${style.storedValue}", mapOf("settledFrames" to settledEmptyFrames, "failures" to failures))
                            assertTrue(failures.take(5).joinToString("\n"), failures.isEmpty())
                        }
                    } finally {
                        scenario.onActivity { activity ->
                            checking = false
                            activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener)
                        }
                    }
                }
            }
        }
    }

    @Test fun consecutiveEmptyWeeksVisiblyChangeAndReverseWithoutRestarting(): Unit = runBlocking {
        WeekMotionStyle.entries.forEach { style ->
            fixture { courses ->
                SchedulePreferences(context).weekMotionStyle = style
                database.courseDao().replaceCoursesBySemester(courses.first().semesterId, emptyList())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    ready(scenario, expectedCourses = 0)
                    lateinit var pager: ViewPager2
                    lateinit var overlay: CourseContinuityOverlay
                    lateinit var empty: View
                    lateinit var resting: Bitmap
                    var left = 0
                    var top = 0
                    val changedPixels = mutableListOf<Int>()
                    scenario.onActivity { activity ->
                        pager = activity.findViewById(R.id.weekPager)
                        overlay = activity.findViewById(R.id.courseContinuityOverlay)
                        empty = holder(pager).findViewById(R.id.emptyState)
                        resting = Bitmap.createBitmap(empty.width, empty.height, Bitmap.Config.ARGB_8888)
                        empty.draw(Canvas(resting))
                        val location = IntArray(2).also { empty.getLocationInWindow(it) }
                        val origin = IntArray(2).also { overlay.getLocationInWindow(it) }
                        left = location[0] - origin[0]
                        top = location[1] - origin[1]
                        assertTrue(pager.beginFakeDrag()); pager.fakeDragBy(-pager.width * .2f)
                    }
                    try {
                        for ((index, progress) in listOf(.2f, .5f, .8f).withIndex()) {
                            if (index > 0) scenario.onActivity { pager.fakeDragBy(-pager.width * .3f) }
                            settle(160L, waitForIdle = false)
                            scenario.onActivity {
                                assertTrue(overlay.isChangingWeeks)
                                assertEquals(View.INVISIBLE, empty.visibility)
                                val stage = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
                                overlay.draw(Canvas(stage))
                                var changed = 0
                                for (y in 0 until resting.height) for (x in 0 until resting.width) {
                                    val pixel = resting.getPixel(x, y)
                                    if (android.graphics.Color.alpha(pixel) == 255 && left + x in 0 until stage.width && top + y in 0 until stage.height) {
                                        if (pixel != stage.getPixel(left + x, top + y)) changed++
                                    }
                                }
                                if (ValueAnimator.areAnimatorsEnabled()) {
                                    assertTrue("The icon and text must visibly change at progress $progress in $style", changed > 500)
                                } else assertEquals("Reduced motion keeps the shared empty message still", 0, changed)
                                changedPixels += changed
                                stage.recycle()
                            }
                        }
                        screenshot("empty-to-empty-${style.storedValue}")
                        scenario.onActivity { pager.fakeDragBy(pager.width * .8f); pager.endFakeDrag() }
                        settle(650L)
                        scenario.onActivity {
                            assertEquals(0, pager.currentItem)
                            assertEquals(View.VISIBLE, empty.visibility)
                            assertFalse(overlay.isChangingWeeks)
                            val restored = Bitmap.createBitmap(empty.width, empty.height, Bitmap.Config.ARGB_8888)
                            empty.draw(Canvas(restored))
                            // The calendar is a live illustration; cancellation
                            // restores the message without freezing its paper.
                            val textTop = holder(pager).findViewById<View>(R.id.emptyIconContainer).height
                            for (y in textTop until resting.height) for (x in 0 until resting.width) {
                                assertEquals("Cancelling restores the original text and action", resting.getPixel(x, y), restored.getPixel(x, y))
                            }
                            restored.recycle()
                            proof("empty-moving-${style.storedValue}", mapOf("changedPixels" to changedPixels))
                        }
                        onView(withId(R.id.btnNextWeek)).perform(click()); settle(800L)
                        scenario.onActivity {
                            assertEquals(1, pager.currentItem)
                            assertEquals(1f, holder(pager).findViewById<View>(R.id.emptyState).alpha, 0f)
                        }
                    } finally { resting.recycle() }
                }
            }
        }
    }

    @Test fun nativeEmptyWeekPreview(): Unit = runBlocking {
        fixture { courses ->
            val style = WeekMotionStyle.fromStoredValue(InstrumentationRegistry.getArguments().getString("previewStyle"))
            SchedulePreferences(context).weekMotionStyle = style
            database.courseDao().replaceCoursesBySemester(courses.first().semesterId,
                courses.take(2).map { it.copy(id = 0L, startWeek = 1, endWeek = 1) })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario, expectedCourses = 2)
                screenshot("empty-preview-before-${style.storedValue}")
                onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(650L)
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    assertEquals(1, pager.currentItem)
                    assertEquals(1f, holder(pager).findViewById<View>(R.id.emptyState).alpha, 0f)
                }
                screenshot("empty-preview-resting-${style.storedValue}")
                onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(650L)
                onView(withId(R.id.weekPager)).perform(swipeRight()); settle(650L)
                onView(withId(R.id.weekPager)).perform(swipeRight()); settle(650L)
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    assertEquals(0, pager.currentItem)
                    assertEquals(View.GONE, holder(pager).findViewById<View>(R.id.emptyState).visibility)
                }
            }
        }
    }

    @Test fun emptyIllustrationStartsOnSelectionPlaysOnceAndStaysStopped(): Unit = runBlocking {
        val composition = LottieCompositionFactory.fromRawResSync(context, R.raw.empty_calendar).value
        assertNotNull("The bundled illustration must parse", composition)
        assertTrue("Every authored layer must be supported", composition!!.warnings.isEmpty())
        assertEquals(60f, composition.frameRate, 0f)
        val storyboard = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val storyboardCanvas = Canvas(storyboard)
        storyboardCanvas.drawColor(android.graphics.Color.rgb(232, 239, 247))
        instrumentation.runOnMainSync {
            val drawable = LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, 512, 512)
            }
            val poses = listOf(0f, 12f, 27f, 46f, 57f, 72f, 91f, 110f, 125f, 140f, 162f, 168f)
            poses.forEachIndexed { index, frame ->
                drawable.progress = frame / 168f
                val tile = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                drawable.draw(Canvas(tile))
                storyboardCanvas.drawBitmap(tile, index % 4 * 512f, index / 4 * 512f, null)
                tile.recycle()
            }
        }
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "native")
        val storyboardFile = File(context.getExternalFilesDir(null), "course-transition-proof/calendar-storyboard-$suffix.png")
        storyboardFile.parentFile!!.mkdirs()
        storyboardFile.outputStream().use { storyboard.compress(Bitmap.CompressFormat.PNG, 100, it) }
        storyboard.recycle()
        WeekMotionStyle.entries.forEach { style ->
            fixture { courses ->
                SchedulePreferences(context).weekMotionStyle = style
                database.courseDao().replaceCoursesBySemester(courses.first().semesterId,
                    courses.take(2).map { it.copy(id = 0L, startWeek = 1, endWeek = 1) })
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    ready(scenario, expectedCourses = 2)
                    lateinit var pager: ViewPager2
                    lateinit var calendar: EmptyCalendarView
                    lateinit var title: View
                    lateinit var button: View
                    lateinit var initialTitle: Bitmap
                    lateinit var initialButton: Bitmap
                    fun snapshot(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        .also { view.draw(Canvas(it)) }
                    val phases = mutableListOf<Float>()
                    val running = mutableListOf<Boolean>()
                    val frames = mutableListOf<Bitmap>()
                    val delays = listOf(180L, 600L, 1250L, 2000L, 4500L, 5900L, 7900L)
                    val sampled = CountDownLatch(1)
                    val arrived = CountDownLatch(1)
                    var arrivalPhase = -1f
                    var sampleError: Throwable? = null
                    fun enterEmptyPage(onArrival: (Float) -> Unit) {
                        val animate = ValueAnimator.areAnimatorsEnabled()
                        val callback = object : ViewPager2.OnPageChangeCallback() {
                            override fun onPageScrollStateChanged(state: Int) {
                                if (state == ViewPager2.SCROLL_STATE_IDLE && pager.currentItem == 1) {
                                    pager.postOnAnimation {
                                        onArrival(calendar.motionProgress)
                                        pager.unregisterOnPageChangeCallback(this)
                                    }
                                }
                            }
                        }
                        if (animate) pager.registerOnPageChangeCallback(callback)
                        pager.setCurrentItem(1, animate)
                        if (!animate) pager.postOnAnimation { onArrival(calendar.motionProgress) }
                    }
                    scenario.onActivity { activity ->
                        pager = activity.findViewById(R.id.weekPager)
                        val page = holder(pager, 1)
                        calendar = page.findViewById(R.id.emptyCalendar)
                        title = page.findViewById(R.id.tvEmptyTitle)
                        button = page.findViewById(R.id.btnEmptyAdd)
                        assertEquals("Preloaded empty weeks must not play", 0, calendar.entranceCount)
                        assertFalse(calendar.isMotionRunning)
                        initialTitle = snapshot(title)
                        initialButton = snapshot(button)
                        enterEmptyPage { phase -> arrivalPhase = phase; arrived.countDown() }
                        val began = android.os.SystemClock.uptimeMillis()
                        val handler = Handler(Looper.getMainLooper())
                        val sampler = object : Runnable {
                            private var index = 0
                            override fun run() {
                                try {
                                    frames += snapshot(calendar)
                                    phases += calendar.motionProgress
                                    running += calendar.isMotionRunning
                                    val titleFrame = snapshot(title)
                                    val buttonFrame = snapshot(button)
                                    assertTrue("The entrance must keep text still", initialTitle.sameAs(titleFrame))
                                    assertTrue("The entrance must keep the action still", initialButton.sameAs(buttonFrame))
                                    titleFrame.recycle(); buttonFrame.recycle()
                                    index++
                                    if (index < delays.size) handler.postAtTime(this, began + delays[index])
                                    else sampled.countDown()
                                } catch (error: Throwable) {
                                    sampleError = error
                                    sampled.countDown()
                                }
                            }
                        }
                        handler.postAtTime(sampler, began + delays.first())
                    }
                    try {
                        assertTrue("One-shot sampling must finish", sampled.await(12, TimeUnit.SECONDS))
                        assertTrue("The empty page must arrive", arrived.await(2, TimeUnit.SECONDS))
                        sampleError?.let { throw it }
                        scenario.onActivity {
                            if (ValueAnimator.areAnimatorsEnabled()) {
                                assertTrue("The first arrived frame must already be playing ($style: $arrivalPhase)", arrivalPhase > 0f && arrivalPhase < .3f)
                                assertTrue("The sheet must visibly turn", !frames[0].sameAs(frames[2]))
                                assertTrue(running.take(4).any { it })
                            } else {
                                assertTrue(phases.all { it == 1f })
                                assertTrue(running.none { it })
                            }
                            assertTrue("Finished animation must stay finished", phases.drop(4).all { it == 1f })
                            assertTrue("Finished animation must stop frame callbacks", running.drop(4).none { it })
                            assertTrue("A long stay must not replay the illustration", frames[4].sameAs(frames[5]) && frames[5].sameAs(frames[6]))
                            assertEquals("Only the selection event may start it", 1, calendar.entranceCount)
                            assertTrue(button.isClickable)
                            proof("calendar-once-${style.storedValue}", mapOf("phases" to phases, "running" to running,
                                "phaseAtFirstArrivedFrame" to arrivalPhase, "textStable" to true,
                                "noReplayAfter7900ms" to true, "entrances" to calendar.entranceCount))
                        }
                        scenario.moveToState(Lifecycle.State.CREATED)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        settle(200L, waitForIdle = false)
                        scenario.onActivity {
                            assertEquals("Foregrounding must not replay a finished entrance", 1, calendar.entranceCount)
                            assertEquals(1f, calendar.motionProgress, 0f)
                            pager.setCurrentItem(0, false)
                            assertFalse("Leaving must stop the old illustration", calendar.isMotionRunning)
                        }
                        settle(300L, waitForIdle = false)
                        val reentry = CountDownLatch(1)
                        var reentryPhase = 1f
                        scenario.onActivity {
                            enterEmptyPage { phase -> reentryPhase = phase; reentry.countDown() }
                        }
                        assertTrue(reentry.await(5, TimeUnit.SECONDS))
                        if (ValueAnimator.areAnimatorsEnabled()) assertTrue("Each reentry starts afresh", reentryPhase > 0f && reentryPhase < .3f)
                        settle(2900L, waitForIdle = false)
                        scenario.onActivity {
                            assertEquals(2, calendar.entranceCount)
                            assertFalse(calendar.isMotionRunning)
                            assertEquals(1f, calendar.motionProgress, 0f)
                            assertTrue(pager.beginFakeDrag())
                            pager.fakeDragBy(-pager.width * .22f)
                            pager.fakeDragBy(pager.width * .22f)
                            pager.endFakeDrag()
                        }
                        settle(500L, waitForIdle = false)
                        scenario.onActivity {
                            assertEquals("Cancelled drags must not start another entrance", 2, calendar.entranceCount)
                            assertFalse(calendar.isMotionRunning)
                            assertEquals("A cancelled neighboring page must never play", 0,
                                holder(pager, 2).findViewById<EmptyCalendarView>(R.id.emptyCalendar).entranceCount)
                            proof("calendar-reentry-${style.storedValue}", mapOf("phaseAtFirstArrivedFrame" to reentryPhase,
                                "entrances" to calendar.entranceCount, "cancelDidNotReplay" to true, "foregroundDidNotReplay" to true))
                        }
                    } finally {
                        frames.forEach(Bitmap::recycle)
                        initialTitle.recycle(); initialButton.recycle()
                    }
                }
            }
        }
    }

    @Test fun nativeCalendarIllustrationPreview(): Unit = runBlocking {
        fixture { courses ->
            val style = WeekMotionStyle.fromStoredValue(InstrumentationRegistry.getArguments().getString("previewStyle"))
            SchedulePreferences(context).weekMotionStyle = style
            database.courseDao().replaceCoursesBySemester(courses.first().semesterId,
                courses.take(2).map { it.copy(id = 0L, startWeek = 1, endWeek = 1) })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario, expectedCourses = 2)
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.weekPager).setCurrentItem(1, true) }
                settle(3800L, waitForIdle = false)
                screenshot("calendar-preview-${style.storedValue}")
                settle(3800L, waitForIdle = false)
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.weekPager).setCurrentItem(2, true) }
                settle(3300L, waitForIdle = false)
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.weekPager).setCurrentItem(1, true) }
                settle(3300L, waitForIdle = false)
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    assertEquals(1, pager.currentItem)
                    val calendar = holder(pager).findViewById<EmptyCalendarView>(R.id.emptyCalendar)
                    assertEquals(2, calendar.entranceCount)
                    assertFalse(calendar.isMotionRunning)
                }
            }
        }
    }

    @Test fun settingsSwitchBetweenBothEffectsAndPersistTheSelectedStyle(): Unit = runBlocking {
        fixture {
            SchedulePreferences(context).weekMotionStyle = WeekMotionStyle.SOFT_SLIDE
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                onView(withId(R.id.nav_settings)).perform(click()); settle(850L)
                onView(withId(R.id.rowWeekMotion)).perform(click())
                onView(withText(R.string.week_motion_continuity)).perform(click())
                assertEquals(WeekMotionStyle.CONTINUITY, SchedulePreferences(context).weekMotionStyle)
                onView(withId(R.id.nav_home)).perform(click()); settle(750L)
                scenario.onActivity { activity ->
                    val overlay = activity.findViewById<CourseContinuityOverlay>(R.id.courseContinuityOverlay)
                    assertEquals(WeekMotionStyle.CONTINUITY, overlay.motionStyle)
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    assertTrue(pager.beginFakeDrag()); pager.fakeDragBy(-pager.width * .4f)
                }
                settle(180L, waitForIdle = false)
                scenario.onActivity { activity ->
                    assertEquals(8, activity.findViewById<CourseContinuityOverlay>(R.id.courseContinuityOverlay).sharedCourseBounds().size)
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    pager.fakeDragBy(pager.width * .4f); pager.endFakeDrag()
                }
                settle(650L)
                onView(withId(R.id.nav_settings)).perform(click()); settle(850L)
                onView(withId(R.id.rowWeekMotion)).perform(click())
                screenshot("motion-choices")
                onView(withText(R.string.week_motion_soft)).perform(click())
                onView(withId(R.id.nav_home)).perform(click()); settle(750L)
                scenario.onActivity { activity ->
                    assertEquals(WeekMotionStyle.SOFT_SLIDE, activity.findViewById<CourseContinuityOverlay>(R.id.courseContinuityOverlay).motionStyle)
                    val saved = SchedulePreferences(activity).snapshot()
                    assertEquals("soft_slide", saved.weekMotionStyle)
                    SchedulePreferences(activity).applySnapshot(saved)
                    assertEquals(WeekMotionStyle.SOFT_SLIDE, SchedulePreferences(activity).weekMotionStyle)
                }
            }
        }
    }

    @Test fun softSlideIncludesEmptyWeeksAndRestoresTheirInteractionAfterCancel(): Unit = runBlocking {
        fixture { courses ->
            SchedulePreferences(context).weekMotionStyle = WeekMotionStyle.SOFT_SLIDE
            val drafts = courses.take(2).map { it.copy(id = 0L, startWeek = 2) }
            database.courseDao().replaceCoursesBySemester(courses.first().semesterId, drafts)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario, expectedCourses = 0)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                lateinit var empty: View
                lateinit var icon: View
                lateinit var iconBounds: RectF
                lateinit var emptyBounds: RectF
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    empty = holder(pager).findViewById(R.id.emptyState)
                    icon = holder(pager).findViewById(R.id.emptyIconContainer)
                    val iconOrigin = IntArray(2).also { icon.getLocationInWindow(it) }
                    val stageOrigin = IntArray(2).also { overlay.getLocationInWindow(it) }
                    val emptyOrigin = IntArray(2).also { empty.getLocationInWindow(it) }
                    emptyBounds = RectF((emptyOrigin[0] - stageOrigin[0]).toFloat(), (emptyOrigin[1] - stageOrigin[1]).toFloat(),
                        (emptyOrigin[0] - stageOrigin[0] + empty.width).toFloat(), (emptyOrigin[1] - stageOrigin[1] + empty.height).toFloat())
                    iconBounds = RectF((iconOrigin[0] - stageOrigin[0]).toFloat(), (iconOrigin[1] - stageOrigin[1]).toFloat(),
                        (iconOrigin[0] - stageOrigin[0] + icon.width).toFloat(), (iconOrigin[1] - stageOrigin[1] + icon.height).toFloat())
                    assertEquals(View.VISIBLE, empty.visibility)
                    assertTrue(pager.beginFakeDrag()); pager.fakeDragBy(-pager.width * .25f)
                }
                settle(180L, waitForIdle = false)
                scenario.onActivity { activity ->
                    assertTrue(overlay.isChangingWeeks)
                    assertEquals(View.INVISIBLE, empty.visibility)
                    val monthWidth = holder(pager).findViewById<View>(R.id.tvMonthLabel).width
                    val headerHeight = holder(pager).findViewById<View>(R.id.weekDayHeader).height
                    val frame = WeekSlideMotion.frame(-.25f, (pager.width - monthWidth).toFloat(),
                        activity.resources.displayMetrics.density, ValueAnimator.areAnimatorsEnabled())
                    val pivotX = (pager.width + monthWidth) / 2f
                    val pivotY = headerHeight.toFloat()
                    val emptyFrame = EmptyWeekMotion.frame(-.25f, activity.resources.displayMetrics.density,
                        true, ValueAnimator.areAnimatorsEnabled())
                    val emptyPivotY = emptyBounds.top + emptyBounds.height() * .35f
                    iconBounds = RectF(
                        emptyBounds.centerX() + (iconBounds.left - emptyBounds.centerX()) * emptyFrame.scale,
                        emptyPivotY + (iconBounds.top - emptyPivotY) * emptyFrame.scale + emptyFrame.offsetY,
                        emptyBounds.centerX() + (iconBounds.right - emptyBounds.centerX()) * emptyFrame.scale,
                        emptyPivotY + (iconBounds.bottom - emptyPivotY) * emptyFrame.scale + emptyFrame.offsetY)
                    val visual = RectF(pivotX + (iconBounds.left - pivotX) * frame.scale + frame.offsetX,
                        pivotY + (iconBounds.top - pivotY) * frame.scale,
                        pivotX + (iconBounds.right - pivotX) * frame.scale + frame.offsetX,
                        pivotY + (iconBounds.bottom - pivotY) * frame.scale)
                    val bitmap = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
                    overlay.draw(Canvas(bitmap))
                    var iconPixels = 0
                    for (y in visual.top.toInt() until visual.bottom.toInt()) for (x in visual.left.toInt() until visual.right.toInt()) {
                        if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) iconPixels++
                    }
                    bitmap.recycle()
                    assertTrue("The empty-week illustration must move with its week", iconPixels > 30)
                    proof("soft-empty", mapOf("iconPixels" to iconPixels, "stageActive" to true))
                }
                screenshot("soft-empty-midpoint")
                scenario.onActivity { pager.fakeDragBy(pager.width * .25f); pager.endFakeDrag() }
                settle(650L)
                scenario.onActivity {
                    assertFalse(overlay.isChangingWeeks)
                    assertEquals(View.VISIBLE, empty.visibility)
                    assertTrue(holder(pager).findViewById<View>(R.id.btnEmptyAdd).isClickable)
                }
            }
        }
    }

    @Test fun inactiveCourseKeepsItsIdentityAndBecomesActiveWithoutSlidingAway(): Unit = runBlocking {
        fixture { original ->
            SchedulePreferences(context).showInactiveCourses = true
            val drafts = original.take(2) + original[2].copy(id = 0L, courseName = "建筑概论",
                dayOfWeek = 6, startSection = 1, endSection = 2, startWeek = 2, colorIndex = 6)
            val ids = database.courseDao().replaceCoursesBySemester(original.first().semesterId, drafts.map { it.copy(id = 0L) })
            val future = drafts.last().copy(id = ids.last())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario, expectedCourses = 3)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                lateinit var resting: RectF
                screenshot("inactive-week-one")
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    val page = holder(pager)
                    resting = page.findViewById<CourseTableView>(R.id.courseTableView).continuityBounds(future)
                        .apply { offset(0f, page.findViewById<View>(R.id.weekDayHeader).height.toFloat()) }
                    assertTrue(pager.beginFakeDrag())
                    pager.fakeDragBy(-pager.width * 0.5f)
                }
                settle(180L, waitForIdle = false)
                scenario.onActivity { activity ->
                    val current = overlay.sharedCourseBounds()[future.id]!!
                    assertEquals(resting, current)
                    val bitmap = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
                    overlay.draw(Canvas(bitmap))
                    val density = activity.resources.displayMetrics.density
                    val alpha = android.graphics.Color.alpha(bitmap.getPixel((current.left + 3f * density).toInt(), current.centerY().toInt()))
                    bitmap.recycle()
                    assertEquals("Inactive-to-active course must blend its actual opacity", 186f, alpha.toFloat(), 2f)
                    proof("inactive", mapOf("anchored" to true, "midpointAlpha" to alpha))
                    pager.fakeDragBy(-pager.width * 0.5f)
                    pager.endFakeDrag()
                }
                settle(700L)
                scenario.onActivity {
                    assertEquals(1, pager.currentItem)
                    assertTrue(overlay.sharedCourseBounds().isEmpty())
                }
                screenshot("inactive-week-two")
            }
        }
    }

    private fun proof(name: String, value: Any) {
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "animated")
        File(context.getExternalFilesDir(null), "course-transition-proof").apply { mkdirs() }
            .resolve("$name-$suffix.json").writeText(Gson().toJson(value))
    }

    private fun screenshot(name: String) {
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "animated")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "course-transition-proof").apply { mkdirs() }
            .resolve("$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun repeatedFullSemesterSwipesKeepEveryTransitionOnTheStage() =
        verifyFullSemesterTraversal(WeekMotionStyle.CONTINUITY)

    @Test fun softPagesKeepEveryTransitionAfterFullSemesterRecycling() =
        verifyFullSemesterTraversal(WeekMotionStyle.SOFT_SLIDE)

    private fun verifyFullSemesterTraversal(style: WeekMotionStyle): Unit = runBlocking {
        fixture { courses ->
            SchedulePreferences(context).weekMotionStyle = style
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                lateinit var listener: ViewTreeObserver.OnPreDrawListener
                val failures = mutableListOf<String>()
                var renderedTransitions = 0
                var checking = false
                var traversal = 0
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    listener = ViewTreeObserver.OnPreDrawListener {
                        if (checking && pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) {
                            val recycler = pager.getChildAt(0) as RecyclerView
                            val lower = (recycler.layoutManager as androidx.recyclerview.widget.LinearLayoutManager)
                                .findFirstVisibleItemPosition()
                            val page = recycler.findViewHolderForAdapterPosition(lower)?.itemView
                            val offset = page?.let { -it.left.toFloat() / pager.width } ?: 0f
                            if (offset in 0.03f..0.97f) {
                                renderedTransitions++
                                val bounds = if (style == WeekMotionStyle.CONTINUITY) overlay.sharedCourseBounds()
                                    else overlay.slidingCourseBounds(false)
                                val stableIds = courses.take(7).map { it.id }.toSet()
                                if (!bounds.keys.containsAll(stableIds) && failures.size < 30) {
                                    failures += "traversal=$traversal, lower=$lower, offset=$offset, shared=${bounds.size}, laidOut=${page?.findViewById<CourseTableView>(R.id.courseTableView)?.isLaidOut}"
                                }
                            }
                        }
                        true
                    }
                    activity.window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
                    checking = true
                }
                try {
                    repeat(2) { pass ->
                        traversal = pass * 2
                        repeat(19) { onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(60L) }
                        settle(650L)
                        scenario.onActivity { assertEquals(19, pager.currentItem) }
                        traversal++
                        repeat(19) { onView(withId(R.id.weekPager)).perform(swipeRight()); settle(60L) }
                        settle(650L)
                        scenario.onActivity { assertEquals(0, pager.currentItem) }
                        if (pass == 0) {
                            onView(withId(R.id.nav_settings)).perform(click()); settle(300L)
                            onView(withId(R.id.nav_home)).perform(click()); settle(700L)
                        }
                    }
                    scenario.onActivity {
                        proof("repeated-traversal-${style.storedValue}", mapOf("renderedTransitions" to renderedTransitions,
                            "swipes" to 76, "failures" to failures))
                        assertTrue("Every swipe must produce sampled native transition frames", renderedTransitions > 76)
                        assertTrue(failures.joinToString("\n"), failures.isEmpty())
                        assertFalse("The stage must hand back to native cards after landing", overlay.isChangingWeeks)
                    }
                } finally {
                    scenario.onActivity { activity ->
                        checking = false
                        activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                }
            }
        }
    }

    @Test fun nativePagingKeepsRepeatedCoursesAnchoredThroughBothDirectionsAndRapidRetargeting(): Unit = runBlocking {
        val recordOnly = InstrumentationRegistry.getArguments().getString("recordOnly") == "true"
        fixture { courses ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                lateinit var listener: ViewTreeObserver.OnPreDrawListener
                var baseline = emptyMap<Long, RectF>()
                var navigation = Rect()
                var sharedFrames = 0
                var movingFrames = 0
                val movingCoursePositions = mutableSetOf<Int>()
                var checking = false
                val errors = mutableListOf<String>()
                val pixels = mutableMapOf<Long, List<Int>>()
                fun sample(view: View, bounds: Map<Long, RectF>): Map<Long, List<Int>> {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val result = bounds.mapValues { (_, rect) ->
                        val y = (rect.top + 12f).toInt().coerceIn(0, bitmap.height - 1)
                        (12..20).map { dx -> bitmap.getPixel((rect.left + dx).toInt(), y) }
                    }
                    bitmap.recycle()
                    return result
                }
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    val table = holder(pager).findViewById<CourseTableView>(R.id.courseTableView)
                    val offset = holder(pager).findViewById<View>(R.id.weekDayHeader).height.toFloat()
                    baseline = courses.filter { it.weekType == 0 }.associate {
                        it.id to table.continuityBounds(it).apply { offset(0f, offset) }
                    }
                    // Record solid card pixels before paging, not just motion-state values.
                    val tablePixels = sample(table, baseline.mapValues { (_, bounds) -> RectF(bounds).apply { offset(0f, -offset) } })
                    pixels.putAll(tablePixels)
                    activity.findViewById<View>(R.id.bottomNavigation).getGlobalVisibleRect(navigation)
                    listener = ViewTreeObserver.OnPreDrawListener {
                        if (checking) {
                            if (pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) movingFrames++
                            val bounds = overlay.sharedCourseBounds()
                            if (bounds.isNotEmpty()) {
                                sharedFrames++
                                val stable = bounds.filterKeys { it in baseline }
                                val sportsId = (bounds.keys - baseline.keys).singleOrNull()
                                if (stable.keys != baseline.keys || sportsId == null ||
                                    courses.none { it.id == sportsId && it.courseName == "体育" }) errors += "Different lessons were merged"
                                stable.forEach { (id, rect) ->
                                    val expected = baseline[id]!!
                                    if (kotlin.math.abs(rect.left - expected.left) > 0.1f ||
                                        kotlin.math.abs(rect.top - expected.top) > 0.1f) errors += "A repeated course drifted during paging"
                                }
                                // A moving lesson can pass in front of a stationary one.
                                // Verify unchanged pixels where the moving card does not cover them.
                                val moving = bounds.filterKeys { it !in baseline }.values
                                val unobstructed = stable.filterValues { rect -> moving.none { RectF.intersects(it, rect) } }
                                // Full checks rasterize a second bitmap every frame. Skip that
                                // measurement only for the separate, normal-speed recording.
                                if (!recordOnly) {
                                    val actualPixels = sample(overlay, unobstructed)
                                    actualPixels.forEach { (id, row) ->
                                        if (row != pixels[id] && errors.size < 5) {
                                            errors += "Card ${courses.first { it.id == id }.courseName}: expected=${pixels[id]}, actual=$row, bounds=${unobstructed[id]}"
                                        }
                                    }
                                }
                                sportsId?.let { movingCoursePositions += bounds[it]!!.left.toInt() }
                            }
                            val nav = Rect().also { activity.findViewById<View>(R.id.bottomNavigation).getGlobalVisibleRect(it) }
                            if (nav != navigation) errors += "Bottom navigation moved"
                        }
                        true
                    }
                    activity.window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
                    checking = true
                }
                try {
                    screenshot("week-one")
                    onView(withId(R.id.weekPager)).perform(swipeLeft()); settle(650L)
                    scenario.onActivity { assertEquals(1, pager.currentItem) }
                    screenshot("week-two")
                    onView(withId(R.id.weekPager)).perform(swipeRight()); settle(650L)
                    scenario.onActivity { assertEquals(0, pager.currentItem) }
                    // Repeated arrow input must reach the requested week without ghosts.
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(R.id.btnNextWeek).performClick()
                        if (!ValueAnimator.areAnimatorsEnabled()) assertEquals(ViewPager2.SCROLL_STATE_IDLE, pager.scrollState)
                        Handler(Looper.getMainLooper()).postDelayed({ activity.findViewById<View>(R.id.btnNextWeek).performClick() }, 85L)
                    }
                    settle(1000L)
                    scenario.onActivity {
                        assertEquals(2, pager.currentItem)
                        assertTrue("Actual native movement frames must be observed", movingFrames > 3)
                        assertTrue("Repeated courses must be drawn continuously", sharedFrames > 3)
                        assertTrue("Odd/even-week course must travel continuously to its new position", movingCoursePositions.size > 3)
                        proof("paging", mapOf("movingFrames" to movingFrames, "sharedFrames" to sharedFrames,
                            "stableCourses" to baseline.size, "movingCoursePositions" to movingCoursePositions,
                            "errors" to errors, "finalWeek" to pager.currentItem + 1))
                        assertTrue(errors.take(5).joinToString("\n"), errors.isEmpty())
                        assertTrue("Continuity layer must be empty at rest", overlay.sharedCourseBounds().isEmpty())
                    }
                    onView(withId(R.id.btnPreviousWeek)).perform(click()); settle(650L)
                } finally {
                    scenario.onActivity { activity ->
                        checking = false
                        activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                }
            }
        }
    }

    @Test fun canceledDragAndDifferentVerticalScrollPositionsHandBackToRealCourses(): Unit = runBlocking {
        fixture { courses ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                lateinit var pager: ViewPager2
                lateinit var overlay: CourseContinuityOverlay
                var oldTop = 0f
                var scroll = 0
                scenario.onActivity { activity ->
                    pager = activity.findViewById(R.id.weekPager)
                    overlay = activity.findViewById(R.id.courseContinuityOverlay)
                    val destination = holder(pager, 1).findViewById<androidx.core.widget.NestedScrollView>(R.id.scheduleScroll)
                    destination.scrollTo(0, 96)
                    scroll = destination.scrollY
                    assertTrue("Different scroll offsets must be represented", scroll > 0)
                    oldTop = holder(pager).findViewById<View>(R.id.weekDayHeader).height + 3f * activity.resources.displayMetrics.density
                    assertTrue(pager.beginFakeDrag())
                    pager.fakeDragBy(-pager.width * 0.35f)
                }
                settle(140L, waitForIdle = false)
                scenario.onActivity {
                    val bounds = overlay.sharedCourseBounds()[courses.first().id]!!
                    assertEquals("Shared course must interpolate saved vertical positions", oldTop - scroll * 0.35f, bounds.top, 1f)
                    pager.fakeDragBy(pager.width * 0.35f)
                    pager.endFakeDrag()
                }
                settle(650L)
                scenario.onActivity {
                    assertEquals(0, pager.currentItem)
                    assertTrue(overlay.sharedCourseBounds().isEmpty())
                    var selected: Course? = null
                    val table = holder(pager).findViewById<CourseTableView>(R.id.courseTableView)
                    table.setOnCourseClickListener { course, _, _ -> selected = course }
                    val bounds = table.continuityBounds(courses.first())
                    val time = android.os.SystemClock.uptimeMillis()
                    listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                        val event = android.view.MotionEvent.obtain(time, time + 30, action, bounds.centerX(), bounds.centerY(), 0)
                        table.dispatchTouchEvent(event); event.recycle()
                    }
                    assertEquals("Course interaction must work after cancellation", courses.first().id, selected?.id)
                }
            }
        }
    }

    @Test fun returningFromSettingsAnimatesCardsAndLeavesTimeAndDateReferencesReadable(): Unit = runBlocking {
        fixture {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                var returnFrames = 0
                var changingFrames = 0
                val errors = mutableListOf<String>()
                lateinit var listener: ViewTreeObserver.OnPreDrawListener
                var checking = false
                var previousPixels: IntArray? = null
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.weekPager)
                    listener = ViewTreeObserver.OnPreDrawListener {
                        if (checking) {
                            returnFrames++
                            if (activity.findViewById<View>(R.id.dateHeader).alpha != 1f ||
                                activity.findViewById<View>(R.id.weekInfo).alpha != 1f ||
                                holder(pager).findViewById<View>(R.id.weekDayHeader).alpha != 1f) errors += "A reference header faded on return"
                            val table = holder(pager).findViewById<CourseTableView>(R.id.courseTableView)
                            val bitmap = Bitmap.createBitmap(table.width, 200, Bitmap.Config.ARGB_8888)
                            table.draw(Canvas(bitmap))
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            previousPixels?.let { if (!it.contentEquals(pixels)) changingFrames++ }
                            previousPixels = pixels
                            bitmap.recycle()
                        }
                        true
                    }
                    activity.window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
                }
                try {
                    onView(withId(R.id.nav_settings)).perform(click()); settle(600L)
                    instrumentation.runOnMainSync { checking = true }
                    onView(withId(R.id.nav_home)).perform(click()); settle(800L)
                    scenario.onActivity {
                        assertTrue(returnFrames > 1)
                        if (ValueAnimator.areAnimatorsEnabled()) assertTrue("Cards must visibly animate in rendered frames", changingFrames > 2)
                        else assertEquals("Reduced motion must show final card positions immediately", 0, changingFrames)
                        assertTrue(errors.joinToString(), errors.isEmpty())
                        proof("return", mapOf("frames" to returnFrames, "changingFrames" to changingFrames,
                            "animationsEnabled" to ValueAnimator.areAnimatorsEnabled(), "errors" to errors))
                    }
                    screenshot("return-settled")
                } finally {
                    scenario.onActivity { activity ->
                        checking = false
                        activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                }
            }
        }
    }
}
