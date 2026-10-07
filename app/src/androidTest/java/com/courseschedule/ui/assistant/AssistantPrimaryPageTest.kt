package com.courseschedule.ui.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Semester
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.ScheduleNavigationGlass
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.android.material.R as MaterialR

@RunWith(AndroidJUnit4::class)
class AssistantPrimaryPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val tabIds = listOf(R.id.nav_home, R.id.nav_study, R.id.nav_assistant, R.id.nav_import, R.id.nav_settings)

    private suspend fun fixture(block: suspend () -> Unit) {
        val old = database.semesterDao().getCurrentSemesterSync()
        val start = LocalDate.now().minusWeeks(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val draft = Semester(name = "助手独立页面测试", startDate = start, totalWeeks = 20)
        val semester = draft.copy(id = database.semesterDao().insertSemester(draft))
        database.semesterDao().switchCurrentSemester(semester.id)
        val previousTheme = AppCompatDelegate.getDefaultNightMode()
        val theme = InstrumentationRegistry.getArguments().getString("themeMode", "light")
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(if (theme == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
        try {
            block()
            assertTrue("Navigation must never create courses", database.courseDao().getCoursesBySemesterSync(semester.id).isEmpty())
            assertTrue("Navigation must never create reminders", database.studyTaskDao().forSemester(semester.id).isEmpty())
        } finally {
            database.semesterDao().deleteSemester(semester)
            old?.let { database.semesterDao().switchCurrentSemester(it.id) }
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousTheme) }
        }
    }

    private fun resumed(): Activity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .firstOrNull { it.window.decorView.hasWindowFocus() }

    private fun await(check: () -> Boolean) {
        val ready = CountDownLatch(1)
        val deadline = SystemClock.uptimeMillis() + 10000
        instrumentation.runOnMainSync {
            fun poll() {
                if (check()) ready.countDown()
                else if (SystemClock.uptimeMillis() < deadline) Handler(Looper.getMainLooper()).postDelayed({ poll() }, 30)
            }
            poll()
        }
        assertTrue("Primary page did not reach the expected state", ready.await(11, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun awaitTab(id: Int) = await {
        val page = resumed()
        val nav = page?.findViewById<BottomNavigationView>(R.id.bottomNavigation)
        nav?.selectedItemId == id && (page !is CourseAssistantActivity || ViewModelProvider(page)[CourseAssistantViewModel::class.java].initialConversationLoaded)
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val suffix = InstrumentationRegistry.getArguments().getString("proofSuffix", "light")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "assistant-primary-page-proof").apply { mkdirs() }
            .resolve("$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assertNavigation(page: Activity, selected: Int) {
        val nav = page.findViewById<BottomNavigationView>(R.id.bottomNavigation)
        assertEquals(tabIds, (0 until nav.menu.size()).map { nav.menu.getItem(it).itemId })
        assertEquals(selected, nav.selectedItemId)
        assertNotNull(page.findViewById<ScheduleNavigationGlass>(R.id.navigationGlass))
        for (id in tabIds) {
            val label = nav.findViewById<View>(id).findViewById<TextView>(MaterialR.id.navigation_bar_item_large_label_view)
            assertNotNull(label.layout)
            assertEquals("Five labels must fit without truncation", 0, label.layout.getEllipsisCount(0))
        }
    }

    @Test fun tabSwitchesKeepDraftConversationAndDisplayedWeek(): Unit = runBlocking {
        fixture {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTab(R.id.nav_home)
                await { resumed()?.findViewById<ViewPager2>(R.id.weekPager)?.adapter?.itemCount == 20 }
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.weekPager).setCurrentItem(4, false) }
                await { resumed()?.findViewById<ViewPager2>(R.id.weekPager)?.currentItem == 4 }
                onView(withId(R.id.nav_assistant)).perform(click())
                awaitTab(R.id.nav_assistant)
                var conversationId: String? = null
                instrumentation.runOnMainSync {
                    val page = resumed() as CourseAssistantActivity
                    assertNavigation(page, R.id.nav_assistant)
                    assertEquals(5, page.intent.getIntExtra(CourseAssistantActivity.EXTRA_DISPLAYED_WEEK, -1))
                    conversationId = ViewModelProvider(page)[CourseAssistantViewModel::class.java].conversationId
                }
                screenshot("assistant-welcome")
                onView(withId(R.id.etMessage)).perform(replaceText("切换后保留这条草稿"))
                Espresso.closeSoftKeyboard()
                for (id in listOf(R.id.nav_study, R.id.nav_import, R.id.nav_settings, R.id.nav_assistant)) {
                    onView(withId(id)).perform(click())
                    awaitTab(id)
                    instrumentation.runOnMainSync {
                        assertNavigation(resumed()!!, id)
                        assertEquals("Selected week must travel through all tabs", 5,
                            resumed()!!.intent.getIntExtra(CourseAssistantActivity.EXTRA_DISPLAYED_WEEK, -1))
                    }
                }
                onView(withId(R.id.etMessage)).check { view, _ -> assertEquals("切换后保留这条草稿", (view as TextView).text.toString()) }
                instrumentation.runOnMainSync { assertEquals(conversationId, ViewModelProvider(resumed() as CourseAssistantActivity)[CourseAssistantViewModel::class.java].conversationId) }
                onView(withId(R.id.nav_home)).perform(click())
                awaitTab(R.id.nav_home)
                scenario.onActivity { assertEquals("Returning to schedule must keep the displayed week", 4, it.findViewById<ViewPager2>(R.id.weekPager).currentItem) }
            }
        }
    }

    private fun showKeyboard() = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Show keyboard and wait for its insets"
        override fun perform(controller: UiController, view: View) {
            view.requestFocus()
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, 0)
            val deadline = SystemClock.uptimeMillis() + 5000
            while (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true && SystemClock.uptimeMillis() < deadline)
                controller.loopMainThreadForAtLeast(100)
            assertTrue(ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        }
    }

    @Test fun composerAndFiveTabsStayAboveKeyboard(): Unit = runBlocking {
        fixture {
            ActivityScenario.launch<CourseAssistantActivity>(Intent(context, CourseAssistantActivity::class.java)).use {
                awaitTab(R.id.nav_assistant)
                onView(withId(R.id.etMessage)).perform(click(), replaceText("第一行\n第二行\n第三行\n第四行"), showKeyboard())
                await { resumed()?.findViewById<View>(R.id.starterContent)?.visibility == View.GONE }
                instrumentation.runOnMainSync {
                    val page = resumed()!!
                    assertNavigation(page, R.id.nav_assistant)
                    val nav = page.findViewById<View>(R.id.bottomNavigation)
                    val footer = page.findViewById<View>(R.id.chatFooter)
                    val input = page.findViewById<View>(R.id.etMessage)
                    val send = page.findViewById<View>(R.id.btnSend)
                    val navRect = Rect().also { assertTrue(nav.getGlobalVisibleRect(it)) }
                    val footerRect = Rect().also { assertTrue(footer.getGlobalVisibleRect(it)) }
                    val inputRect = Rect().also { assertTrue(input.getGlobalVisibleRect(it)) }
                    val sendRect = Rect().also { assertTrue(send.getGlobalVisibleRect(it)) }
                    val root = page.window.decorView
                    val keyboard = ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    assertTrue("Navigation must stay above keyboard", navRect.bottom <= root.height - keyboard + 1)
                    assertTrue("Composer must not overlap navigation", footerRect.bottom <= navRect.top + 1)
                    assertEquals("The entire input must be visible", input.height, inputRect.height())
                    assertEquals("Send action must remain fully visible", send.height, sendRect.height())
                }
                screenshot("assistant-keyboard")
                Espresso.closeSoftKeyboard()
                await { ViewCompat.getRootWindowInsets(resumed()!!.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
                screenshot("assistant-draft")
            }
        }
    }
}
