package com.courseschedule.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.ui.assistant.StudyTasksActivity
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivityMainBinding
import com.courseschedule.databinding.DialogCourseFocusBinding
import com.google.gson.Gson
import com.courseschedule.ui.assistant.CourseAssistantActivity
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.addcourse.AddCourseActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.domain.SemesterPhase
import com.courseschedule.domain.SemesterWeekStatus
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.utils.ReminderManager
import kotlinx.coroutines.launch
import com.courseschedule.view.CourseTableView
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal fun weekAtProgressPosition(x: Float, width: Int, totalWeeks: Int): Int {
    if (width <= 0 || totalWeeks <= 1) return 1
    return (x / width * totalWeeks).roundToInt().coerceIn(1, totalWeeks)
}

/**
 * 主界面 - 课程表显示
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: CourseViewModel
    private lateinit var weekPagerAdapter: WeekPagerAdapter
    private var courseToolsPopup: PopupWindow? = null
    private var courseFocusDialog: CourseFocusDialog? = null
    private var courseFocusEditor: CourseFocusEditor? = null
    private var pendingCourseFocusState: Bundle? = null

    private var returnPreDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var returnTable: CourseTableView? = null
    private var continuityPosition = 0
    private var continuityProgress = 0f
    private val continuityPreDraw = android.view.ViewTreeObserver.OnPreDrawListener {
        if (continuityProgress > 0f) updateCourseContinuity()
        true
    }
    private var currentWeek = 1
    private var currentSemester: Semester? = null
    private var currentCourses: List<Course> = emptyList()
    private var semesterWeekStatus: SemesterWeekStatus? = null
    private var pageSettings = WeekPageSettings()
    private var lastPagerPosition = -1
    private var lastAnimatedPagerPosition = -1
    private var pendingPagerMotionPosition = -1
    private var pendingPagerMotionForward = true
    private var pagerMotionReady = false
    private var semesterDataLoaded = false
    private var coursesDataLoaded = false
    private var suppressBottomNavigationMotion = false
    private var hasResumedOnce = false
    private var weekProgressScrubbing = false
    private var weekProgressScrubbedWeek = 1
    private var dateHeaderWeek: Int? = null
    private var dateHeaderSemesterId: Long? = null
    private val headerInterpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            continuityPosition = position
            continuityProgress = positionOffset
            updateCourseContinuity()
        }

        override fun onPageSelected(position: Int) {
            // Start the selected empty illustration during the page transition,
            // rather than waiting for idle or carrying an old looping playhead.
            weekPagerAdapter.selectPage(binding.weekPager, position)
            if (position != lastPagerPosition) cancelScheduleReturnEntrance()
            val previousPosition = lastPagerPosition
            lastPagerPosition = position
            val week = position + 1
            if (currentWeek != week) {
                currentWeek = week
                viewModel.setCurrentWeek(week)
            }
            updateWeekDisplay()
            if (pagerMotionReady && !weekProgressScrubbing &&
                previousPosition >= 0 && previousPosition != position
            ) {
                pendingPagerMotionPosition = position
                pendingPagerMotionForward = position > previousPosition
                animateWeekHeader(forward = pendingPagerMotionForward)
                if (binding.weekPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    playSelectedPageMotion(position, pendingPagerMotionForward)
                    pendingPagerMotionPosition = -1
                }
            }
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                cancelScheduleReturnEntrance()
                weekPagerAdapter.prepareNeighborIllustrations(binding.weekPager)
            }
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                continuityProgress = 0f
                binding.courseContinuityOverlay.clear()
            }
            if (state != ViewPager2.SCROLL_STATE_IDLE || pendingPagerMotionPosition < 0) return
            playSelectedPageMotion(pendingPagerMotionPosition, pendingPagerMotionForward)
            pendingPagerMotionPosition = -1
        }
    }

    // 通知权限请求 launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            restoreCourseReminders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCourseFocusState = savedInstanceState?.getBundle("courseFocusState")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initNavigationGlass()

        // 设置工具栏
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.tvProjectLink.installPressScale(pressedScale = 0.98f)
        binding.tvProjectLink.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_repository_url))))
            }
        }

        // 初始化ViewModel
        viewModel = ViewModelProvider(this)[CourseViewModel::class.java]

        // 初始化视图
        initViews()
        // 观察数据变化
        observeData()
        // 请求通知权限（Android 13+）
        requestNotificationPermissionIfNeeded()
    }

    private fun initNavigationGlass() {
        // Material's transparent shape still casts its compatibility shadow.
        binding.bottomNavigation.setBackgroundColor(Color.TRANSPARENT)
        binding.bottomNavigation.elevation = 0f
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = resources.getBoolean(R.bool.window_light_system_bars)
            isAppearanceLightNavigationBars = resources.getBoolean(R.bool.window_light_system_bars)
        }
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(safe.left, safe.top, safe.right, 0)
            binding.bottomNavigation.layoutParams = binding.bottomNavigation.layoutParams.apply {
                (this as ViewGroup.MarginLayoutParams).bottomMargin = safe.bottom
            }
            binding.bottomNavigation.setPadding(0, 0, 0, 0)
            binding.navigationGlass.layoutParams = binding.navigationGlass.layoutParams.apply {
                height = (108f * density).roundToInt() + safe.bottom
            }
            if (::weekPagerAdapter.isInitialized) weekPagerAdapter.setNavigationInset(binding.weekPager, safe.bottom)
            // This root positions both system-safe controls and the glass behind the bars.
            WindowInsetsCompat.CONSUMED
        }
        binding.navigationGlass.bind(binding.weekPager, binding.courseContinuityOverlay)
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun initViews() {
        weekPagerAdapter = WeekPagerAdapter(
            onCourseClick = ::showCourseDetails,
            onAddCourse = { day, section -> openNewCourse(day, section) },
            onQuickAddCourse = { week, day, startSection, endSection ->
                openNewCourse(day, startSection, endSection, week)
            }
        )
        binding.weekPager.adapter = weekPagerAdapter
        binding.weekPager.visibility = View.INVISIBLE
        binding.weekPager.offscreenPageLimit = 1
        binding.weekPager.registerOnPageChangeCallback(pageChangeCallback)
        binding.root.viewTreeObserver.addOnPreDrawListener(continuityPreDraw)
        binding.weekPager.setPageTransformer { page, position ->
            // Course changes are drawn on the stationary stage above these pages.
            page.findViewById<CourseTableView>(R.id.courseTableView)?.setPagerOffset(position)
        }

        binding.btnPreviousWeek.setOnClickListener {
            selectWeek(currentWeek - 1, smoothScroll = true)
        }

        binding.btnNextWeek.setOnClickListener {
            selectWeek(currentWeek + 1, smoothScroll = true)
        }

        installWeekProgressScrubbing()

        binding.weekInfo.setOnClickListener { showWeekPicker() }
        binding.weekInfo.installPressScale(0.97f)
        binding.btnPreviousWeek.installPressScale(0.97f)
        binding.btnNextWeek.installPressScale(0.97f)

        binding.bottomNavigation.stabilizeActiveIndicatorSize()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (suppressBottomNavigationMotion) return@setOnItemSelectedListener true
            val itemView = binding.bottomNavigation.findViewById<View>(item.itemId)
            when (item.itemId) {
                R.id.nav_home -> {
                    itemView.playNavigationMotion()
                    true
                }
                R.id.nav_study -> {
                    openTab(Intent(this, StudyTasksActivity::class.java).putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true))
                    true
                }
                R.id.nav_import -> {
                    openTab(Intent(this, ImportActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    openTab(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            binding.bottomNavigation.findViewById<View>(item.itemId)?.playNavigationMotion()
        }
    }

    private fun updateCourseContinuity() {
        binding.courseContinuityOverlay.updatePages(
            weekPagerAdapter.holderAt(binding.weekPager, continuityPosition),
            weekPagerAdapter.holderAt(binding.weekPager, continuityPosition + 1),
            continuityProgress
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installWeekProgressScrubbing() {
        binding.weekProgressTouchTarget.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (currentSemester == null) return@setOnTouchListener false
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    weekProgressScrubbedWeek = currentWeek
                    setWeekProgressScrubbing(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalWeeks = currentSemester?.totalWeeks ?: return@setOnTouchListener false
                    val targetWeek = weekAtProgressPosition(event.x, view.width, totalWeeks)
                    if (targetWeek != weekProgressScrubbedWeek) {
                        weekProgressScrubbedWeek = targetWeek
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        selectWeek(targetWeek, smoothScroll = false)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    setWeekProgressScrubbing(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setWeekProgressScrubbing(scrubbing: Boolean) {
        if (weekProgressScrubbing == scrubbing) return
        weekProgressScrubbing = scrubbing
        if (scrubbing) {
            pendingPagerMotionPosition = -1
            positionWeekProgressThumb()
        }

        binding.weekProgress.animate().cancel()
        binding.weekProgress.animate()
            .scaleY(if (scrubbing) 2.5f else 1f)
            .setDuration(if (scrubbing) 120L else 180L)
            .setInterpolator(headerInterpolator)
            .start()

        binding.weekProgressThumb.animate().cancel()
        if (scrubbing) {
            binding.weekProgressThumb.visibility = View.VISIBLE
            binding.weekProgressThumb.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .setInterpolator(headerInterpolator)
                .start()
        } else {
            binding.weekProgressThumb.animate()
                .alpha(0f)
                .scaleX(0.65f)
                .scaleY(0.65f)
                .setDuration(160L)
                .setInterpolator(headerInterpolator)
                .withEndAction {
                    if (!weekProgressScrubbing) {
                        binding.weekProgressThumb.visibility = View.INVISIBLE
                    }
                }
                .start()
        }
    }

    private fun positionWeekProgressThumb() {
        val totalWeeks = currentSemester?.totalWeeks ?: return
        binding.weekProgress.doOnLayout { progress ->
            binding.weekProgressThumb.translationX =
                progress.width * currentWeek / totalWeeks.toFloat() -
                binding.weekProgressThumb.width / 2f
        }
    }

    private fun animateWeekHeader(forward: Boolean) {
        val offset = dp(if (forward) 18f else -18f)
        listOf(binding.tvCurrentWeek, binding.tvWeekContext).forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0.25f
            view.translationX = offset
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(index * 28L)
                .setDuration(290L)
                .setInterpolator(headerInterpolator)
                .start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val source = intent.getStringExtra(EXTRA_SCHEDULE_RETURN_SOURCE)
        intent.removeExtra(EXTRA_SCHEDULE_RETURN_SOURCE)
        if (!ScheduleReturnMotion.accepts(source)) return
        cancelScheduleReturnEntrance()
        // Bind resumed data and measure before the first visible frame.
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!semesterDataLoaded || !coursesDataLoaded) {
                false
            } else {
                val holder = weekPagerAdapter.currentHolder(binding.weekPager)
                if (holder == null && weekPagerAdapter.itemCount > 0) {
                    false
                } else {
                    returnPreDraw?.let { binding.root.viewTreeObserver.removeOnPreDrawListener(it) }
                    returnPreDraw = null
                    holder?.let {
                        returnTable = it.binding.courseTableView
                        returnTable?.playReturnEntrance()
                    }
                    true
                }
            }
        }
        returnPreDraw = listener
        binding.root.viewTreeObserver.addOnPreDrawListener(listener)
        binding.root.invalidate()
    }

    private fun cancelScheduleReturnEntrance() {
        returnPreDraw?.let { binding.root.viewTreeObserver.removeOnPreDrawListener(it) }
        returnPreDraw = null
        returnTable?.cancelReturnEntrance()
        returnTable = null
        binding.dateHeader.alpha = 1f
        binding.weekInfo.alpha = 1f
    }

    override fun onPause() {
        courseToolsPopup?.dismiss()
        cancelScheduleReturnEntrance()
        continuityProgress = 0f
        binding.courseContinuityOverlay.clear()
        super.onPause()
    }

    private fun openTab(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun playSelectedPageMotion(position: Int, forward: Boolean) {
        if (lastAnimatedPagerPosition == position) return
        binding.weekPager.post {
            if (binding.weekPager.currentItem != position) return@post
            if (weekPagerAdapter.playSelectionMotion(binding.weekPager, position, forward)) {
                lastAnimatedPagerPosition = position
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun observeData() {
        viewModel.currentSemester.observe(this) { semester ->
            semesterDataLoaded = true
            if (currentSemester?.id != semester?.id) {
                lastAnimatedPagerPosition = -1
                pendingPagerMotionPosition = -1
                pagerMotionReady = false
            }
            currentSemester = semester
            semester?.let {
                refreshWeekPager()
                syncPagerToCurrentWeek(smoothScroll = false)
                updateWeekDisplay()
                binding.weekPager.post {
                    if (currentSemester?.id == it.id) {
                        lastPagerPosition = binding.weekPager.currentItem
                        pagerMotionReady = true
                    }
                }
            }
            showWeekPagerWhenReady()
        }

        viewModel.currentWeek.observe(this) { week ->
            currentWeek = week
            syncPagerToCurrentWeek(smoothScroll = false)
            updateWeekDisplay()
        }

        viewModel.semesterWeekStatus.observe(this) { status ->
            semesterWeekStatus = status
            refreshWeekPager()
            updateWeekDisplay()
        }

        viewModel.allCourses.observe(this) { courses ->
            coursesDataLoaded = true
            currentCourses = courses
            refreshWeekPager()
            updateWeekDisplay()
            showWeekPagerWhenReady()
        }
    }

    private fun showWeekPagerWhenReady() {
        if (semesterDataLoaded && coursesDataLoaded) {
            binding.weekPager.visibility = View.VISIBLE
            weekPagerAdapter.selectPage(binding.weekPager, binding.weekPager.currentItem)
            if (pendingCourseFocusState != null) binding.weekPager.post {
                val state = pendingCourseFocusState ?: return@post
                if (isFinishing || isDestroyed) return@post
                pendingCourseFocusState = null
                val original = Gson().fromJson(state.getString("original"), Course::class.java)
                val semester = Gson().fromJson(state.getString("semester"), Semester::class.java)
                showCourseFocus(original, binding.weekPager, RectF(), semester, state)
            }
        }
    }

    private fun refreshWeekPager() {
        if (!::weekPagerAdapter.isInitialized) return
        weekPagerAdapter.submitData(
            semester = currentSemester,
            courses = currentCourses,
            status = semesterWeekStatus,
            settings = pageSettings
        )
    }

    private fun syncPagerToCurrentWeek(smoothScroll: Boolean) {
        val semester = currentSemester ?: return
        if (binding.weekPager.adapter?.itemCount == 0) return
        val target = (currentWeek - 1).coerceIn(0, semester.totalWeeks - 1)
        if (binding.weekPager.currentItem != target) {
            binding.weekPager.setCurrentItem(target, smoothScroll)
        }
    }

    private fun selectWeek(week: Int, smoothScroll: Boolean) {
        val semester = currentSemester ?: return
        val targetWeek = week.coerceIn(1, semester.totalWeeks)
        if (targetWeek == currentWeek) {
            updateWeekDisplay()
            return
        }
        if (binding.weekPager.adapter?.itemCount == semester.totalWeeks) {
            binding.weekPager.setCurrentItem(targetWeek - 1, smoothScroll && ValueAnimator.areAnimatorsEnabled())
        } else {
            currentWeek = targetWeek
            viewModel.setCurrentWeek(targetWeek)
        }
    }

    private fun showCourseDetails(course: Course, sourceView: View, sourceBounds: RectF) {
        val semester = currentSemester ?: return
        showCourseFocus(course, sourceView, sourceBounds, semester)
    }

    private fun showCourseFocus(course: Course, sourceView: View, sourceBounds: RectF,
        semester: Semester, restored: Bundle? = null) {
        val detail = DialogCourseFocusBinding.inflate(layoutInflater)
        val dialog = CourseFocusDialog(this, detail.root, course, sourceView, sourceBounds, restored != null)
        courseFocusDialog?.dismissImmediately()
        val editor = CourseFocusEditor(this, detail, course, semester, dialog, restored)
        courseFocusDialog = dialog
        courseFocusEditor = editor
        dialog.setOnDismissListener {
            editor.dispose()
            if (courseFocusDialog === dialog) {
                courseFocusDialog = null
                courseFocusEditor = null
            }
        }
        dialog.show()
        editor.start()
    }

    private fun openNewCourse(
        dayOfWeek: Int? = null,
        startSection: Int? = null,
        endSection: Int? = null,
        week: Int? = null
    ) {
        val intent = Intent(this, AddCourseActivity::class.java).apply {
            dayOfWeek?.let { putExtra(AddCourseActivity.EXTRA_DAY_OF_WEEK, it) }
            startSection?.let { putExtra(AddCourseActivity.EXTRA_SECTION, it) }
            endSection?.let { putExtra(AddCourseActivity.EXTRA_END_SECTION, it) }
            week?.let { putExtra(AddCourseActivity.EXTRA_WEEK, it) }
        }
        startActivity(intent)
    }

    /**
     * 更新周次显示
     */
    private fun updateWeekDisplay() {
        val semester = currentSemester ?: return
        val status = semesterWeekStatus
        val displayDate = Calendar.getInstance().apply {
            if (status?.phase != SemesterPhase.ACTIVE || status.week != currentWeek) {
                timeInMillis = semester.startDate
                add(Calendar.DAY_OF_MONTH, (currentWeek - 1) * 7)
            }
        }
        val dateTitle = SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(displayDate.time)
        val weekday = resources.getStringArray(R.array.weekdays).getOrElse(
            when (displayDate.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> 0
            }
        ) { "" }
        val previousHeaderWeek = dateHeaderWeek
        binding.dateHeader.setDate(
            date = dateTitle,
            summary = getString(R.string.toolbar_week_summary, currentWeek, weekday),
            animate = pagerMotionReady && !weekProgressScrubbing &&
                dateHeaderSemesterId == semester.id &&
                previousHeaderWeek != null && previousHeaderWeek != currentWeek,
            forward = currentWeek >= (previousHeaderWeek ?: currentWeek)
        )
        dateHeaderWeek = currentWeek
        dateHeaderSemesterId = semester.id
        binding.tvCurrentWeek.text = getString(R.string.week_format, currentWeek)
        val isOutsideSemester = status?.phase == SemesterPhase.BEFORE ||
            status?.phase == SemesterPhase.AFTER
        binding.tvCurrentWeek.setTextColor(
            ContextCompat.getColor(
                this,
                if (isOutsideSemester) R.color.secondary_variant else R.color.text_primary
            )
        )

        val weekCourses = currentCourses.filter { ScheduleRules.isCourseInWeek(it, currentWeek) }
        val visibleCourses = if (pageSettings.showWeekend) {
            weekCourses
        } else {
            weekCourses.filter { it.dayOfWeek <= 5 }
        }
        binding.tvWeekContext.text = buildWeekContext(status, visibleCourses.size)
        binding.weekProgress.max = semester.totalWeeks
        binding.weekProgress.progress = currentWeek
        val indicatorColor = ContextCompat.getColor(
            this,
            if (isOutsideSemester) R.color.secondary else R.color.primary
        )
        binding.weekProgress.setIndicatorColor(indicatorColor)
        (binding.weekProgressThumb.background.mutate() as? GradientDrawable)
            ?.setColor(indicatorColor)
        positionWeekProgressThumb()
        binding.btnPreviousWeek.isEnabled = currentWeek > 1
        binding.btnNextWeek.isEnabled = currentWeek < semester.totalWeeks
        binding.btnPreviousWeek.alpha = if (binding.btnPreviousWeek.isEnabled) 1f else 0.35f
        binding.btnNextWeek.alpha = if (binding.btnNextWeek.isEnabled) 1f else 0.35f
    }

    private fun buildWeekContext(status: SemesterWeekStatus?, courseCount: Int): String {
        val semester = currentSemester ?: return ""
        val calendar = Calendar.getInstance().apply {
            timeInMillis = semester.startDate
            add(Calendar.DAY_OF_MONTH, (currentWeek - 1) * 7)
        }
        val format = SimpleDateFormat("MM.dd", Locale.CHINA)
        val start = format.format(Date(calendar.timeInMillis))
        calendar.add(Calendar.DAY_OF_MONTH, 6)
        val end = format.format(Date(calendar.timeInMillis))
        val phase = when (status?.phase) {
            SemesterPhase.BEFORE -> getString(R.string.semester_not_started)
            SemesterPhase.AFTER -> getString(R.string.vacation)
            SemesterPhase.ACTIVE -> if (currentWeek == status.week) {
                getString(R.string.current_week)
            } else {
                getString(R.string.semester_in_progress)
            }
            null -> getString(R.string.semester_in_progress)
        }
        return getString(R.string.week_context_courses_format, phase, start, end, courseCount)
    }

    /**
     * 显示周次选择器
     */
    private fun showWeekPicker() {
        val semester = currentSemester ?: return
        val weeks = Array(semester.totalWeeks) { getString(R.string.week_format, it + 1) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_week)
            .setSingleChoiceItems(weeks, currentWeek - 1) { dialog, which ->
                dialog.dismiss()
                selectWeek(which + 1, smoothScroll = true)
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_add_course)?.actionView?.setOnClickListener { openNewCourse() }
        menu.findItem(R.id.action_today)?.actionView?.setOnClickListener {
            goToCurrentWeek()
        }
        menu.findItem(R.id.action_course_assistant)?.actionView?.setOnClickListener(::showCourseTools)
        return true
    }

    private fun showCourseTools(anchor: View) {
        if (courseToolsPopup?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.popup_course_tools, binding.toolbar, false)
        content.clipToOutline = true
        content.installPressScale(0.985f)
        val popup = PopupWindow(content,
            minOf(dp(280f).roundToInt(), binding.root.width - dp(32f).roundToInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_course_tools_popup))
            elevation = dp(3f)
            isOutsideTouchable = true
            setOnDismissListener { courseToolsPopup = null }
        }
        content.setOnClickListener {
            popup.dismiss()
            openTab(Intent(this, CourseAssistantActivity::class.java)
                .putExtra(CourseAssistantActivity.EXTRA_DISPLAYED_WEEK, currentWeek))
        }
        courseToolsPopup = popup
        popup.showAsDropDown(anchor, -dp(8f).roundToInt(), dp(4f).roundToInt(), Gravity.END)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_course_assistant -> {
                binding.toolbar.menu.findItem(item.itemId)?.actionView?.let(::showCourseTools)
                true
            }
            R.id.action_add_course -> {
                openNewCourse()
                true
            }
            R.id.action_today -> {
                goToCurrentWeek()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun goToCurrentWeek() {
        viewModel.refreshSemesterStatus()
        semesterWeekStatus?.week?.let { selectWeek(it, smoothScroll = true) }
    }

    override fun onResume() {
        super.onResume()
        restoreCourseReminders()
        applyDisplaySettings()
        val returningToHome = hasResumedOnce &&
            binding.bottomNavigation.selectedItemId != R.id.nav_home
        if (binding.bottomNavigation.selectedItemId != R.id.nav_home) {
            suppressBottomNavigationMotion = true
            binding.bottomNavigation.selectItemWithoutAnimation(R.id.nav_home)
            suppressBottomNavigationMotion = false
        }
        if (returningToHome) {
            binding.bottomNavigation.post {
                binding.bottomNavigation.findViewById<View>(R.id.nav_home)?.playNavigationMotion()
            }
        }
        hasResumedOnce = true
        refreshWeekPager()
        updateWeekDisplay()
    }

    private fun applyDisplaySettings() {
        val prefs = SchedulePreferences(this)
        pageSettings = WeekPageSettings(
            showWeekend = prefs.showWeekend,
            showTimes = prefs.showTime,
            showInactiveCourses = prefs.showInactiveCourses,
            sectionHeightDp = prefs.sectionHeightDp,
            sectionTimes = prefs.sectionTimes,
            sectionEndTimes = prefs.sectionEndTimes,
            weekMotionStyle = prefs.weekMotionStyle
        )
        binding.courseContinuityOverlay.motionStyle = prefs.weekMotionStyle
    }

    override fun onSaveInstanceState(outState: Bundle) {
        courseFocusEditor?.let { outState.putBundle("courseFocusState", it.snapshot()) }
            ?: pendingCourseFocusState?.let { outState.putBundle("courseFocusState", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        courseFocusDialog?.dismissImmediately()
        courseFocusDialog = null
        binding.root.viewTreeObserver.removeOnPreDrawListener(continuityPreDraw)
        binding.courseContinuityOverlay.clear()
        binding.weekPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    /**
     * 请求通知权限（Android 13+ / API 33+）
     * 提醒功能依赖通知权限，未授予则提醒通知不会显示
     */
    private fun restoreCourseReminders() {
        lifecycleScope.launch {
            try {
                ReminderManager(applicationContext).restoreReminders()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("MainActivity", "Unable to restore reminders", error)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
