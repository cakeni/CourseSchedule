package com.courseschedule.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivityMainBinding
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.addcourse.AddCourseActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.domain.SemesterPhase
import com.courseschedule.domain.SemesterWeekStatus
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.view.CourseTableView
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 主界面 - 课程表显示
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: CourseViewModel
    private lateinit var weekPagerAdapter: WeekPagerAdapter

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
    private var dateHeaderWeek: Int? = null
    private var dateHeaderSemesterId: Long? = null
    private val headerInterpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val previousPosition = lastPagerPosition
            lastPagerPosition = position
            val week = position + 1
            if (currentWeek != week) {
                currentWeek = week
                viewModel.setCurrentWeek(week)
            }
            updateWeekDisplay()
            if (pagerMotionReady && previousPosition >= 0 && previousPosition != position) {
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
            if (state != ViewPager2.SCROLL_STATE_IDLE || pendingPagerMotionPosition < 0) return
            playSelectedPageMotion(pendingPagerMotionPosition, pendingPagerMotionForward)
            pendingPagerMotionPosition = -1
        }
    }

    // 通知权限请求 launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果忽略：用户拒绝则提醒通知不显示，不影响其他功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.weekPager.setPageTransformer { page, position ->
            val distance = abs(position).coerceIn(0f, 1f)
            val scale = 1f - (distance * 0.02f)
            page.alpha = 1f - (distance * 0.16f)
            page.scaleX = scale
            page.scaleY = scale
            page.translationX = -position * dp(14f) * (1f - distance)
            page.rotationY = 0f
            page.findViewById<CourseTableView>(R.id.courseTableView)?.setPagerOffset(position)
        }

        binding.btnPreviousWeek.setOnClickListener {
            selectWeek(currentWeek - 1, smoothScroll = true)
        }

        binding.btnNextWeek.setOnClickListener {
            selectWeek(currentWeek + 1, smoothScroll = true)
        }

        binding.weekInfo.setOnClickListener { showWeekPicker() }
        binding.weekInfo.installPressScale(0.97f)
        binding.btnPreviousWeek.installPressScale(0.97f)
        binding.btnNextWeek.installPressScale(0.97f)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (suppressBottomNavigationMotion) return@setOnItemSelectedListener true
            val itemView = binding.bottomNavigation.findViewById<View>(item.itemId)
            when (item.itemId) {
                R.id.nav_home -> {
                    itemView.playNavigationMotion()
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

    private fun openTab(intent: Intent) {
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun playSelectedPageMotion(position: Int, forward: Boolean = true) {
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
            binding.weekPager.setCurrentItem(targetWeek - 1, smoothScroll)
        } else {
            currentWeek = targetWeek
            viewModel.setCurrentWeek(targetWeek)
        }
    }

    private fun showCourseDetails(course: Course, sourceView: View, sourceBounds: RectF) {
        val detailView = layoutInflater.inflate(R.layout.dialog_course_details, null)
        detailView.findViewById<TextView>(R.id.tvDetailTitle).text = course.courseName
        val teacherMissing = course.teacher.isBlank()
        detailView.findViewById<TextView>(R.id.tvDetailTeacher).text = if (teacherMissing) {
            getString(R.string.teacher_not_provided)
        } else {
            course.teacher
        }
        detailView.findViewById<TextView>(R.id.tvDetailClassroom).text =
            course.classroom.ifBlank { getString(R.string.not_set) }
        detailView.findViewById<TextView>(R.id.tvDetailTime).text = getString(
            R.string.course_time_detail,
            resources.getStringArray(R.array.weekdays)
                .getOrElse(course.dayOfWeek - 1) { getString(R.string.not_set) },
            course.startSection,
            course.endSection
        )

        val weekRange = if (course.startWeek == course.endWeek) {
            getString(R.string.week_format, course.startWeek)
        } else {
            getString(R.string.week_range_format, course.startWeek, course.endWeek)
        }
        val weekType = when (course.weekType) {
            1 -> getString(R.string.odd_week)
            2 -> getString(R.string.even_week)
            else -> getString(R.string.every_week)
        }
        detailView.findViewById<TextView>(R.id.tvDetailWeeks).text =
            getString(R.string.course_week_detail, weekRange, weekType)
        detailView.findViewById<View>(R.id.tvTeacherHint).visibility =
            if (teacherMissing) View.VISIBLE else View.GONE

        val noteRow = detailView.findViewById<View>(R.id.rowDetailNote)
        if (course.note.isNotBlank()) {
            detailView.findViewById<TextView>(R.id.tvDetailNote).text = course.note
            noteRow.visibility = View.VISIBLE
        }

        val dialog = BottomSheetDialog(this)
        detailView.findViewById<View>(R.id.btnEditCourse).apply {
            installPressScale(0.9f)
            setOnClickListener {
                dialog.dismiss()
                openCourseEditor(course)
            }
        }
        dialog.setContentView(detailView)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                playCourseDetailEntrance(detailView, sourceView, sourceBounds)
            }
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
    }

    private fun playCourseDetailEntrance(
        detailView: View,
        sourceView: View,
        sourceBounds: RectF
    ) {
        detailView.doOnPreDraw {
            val sourceLocation = IntArray(2)
            val detailLocation = IntArray(2)
            sourceView.getLocationOnScreen(sourceLocation)
            detailView.getLocationOnScreen(detailLocation)

            val sourceCenterX = sourceLocation[0] + sourceBounds.centerX()
            val sourceCenterY = sourceLocation[1] + sourceBounds.centerY()
            val detailCenterX = detailLocation[0] + detailView.width / 2f
            val detailCenterY = detailLocation[1] + detailView.height / 2f

            detailView.pivotX = (sourceCenterX - detailLocation[0])
                .coerceIn(0f, detailView.width.toFloat())
            detailView.pivotY = (sourceCenterY - detailLocation[1])
                .coerceIn(0f, detailView.height.toFloat())
            detailView.alpha = 0.72f
            detailView.scaleX = 0.9f
            detailView.scaleY = 0.92f
            detailView.translationX = ((sourceCenterX - detailCenterX) * 0.16f)
                .coerceIn(-dp(44f), dp(44f))
            detailView.translationY = ((sourceCenterY - detailCenterY) * 0.18f)
                .coerceIn(-dp(72f), dp(104f))

            (detailView as? ViewGroup)?.let { content ->
                repeat(content.childCount) { index ->
                    content.getChildAt(index).apply {
                        alpha = 0f
                        translationY = dp(10f + index.coerceAtMost(3) * 2f)
                        animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(90L + index * 36L)
                            .setDuration(330L)
                            .setInterpolator(headerInterpolator)
                            .start()
                    }
                }
            }

            detailView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(480L)
                .setInterpolator(headerInterpolator)
                .start()
        }
    }

    private fun openCourseEditor(course: Course) {
        val intent = Intent(this, AddCourseActivity::class.java).apply {
            putExtra("course_id", course.id)
            putExtra("is_edit", true)
        }
        startActivity(intent)
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
            animate = pagerMotionReady && dateHeaderSemesterId == semester.id &&
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
        binding.weekProgress.setIndicatorColor(
            ContextCompat.getColor(
                this,
                if (isOutsideSemester) R.color.secondary else R.color.primary
            )
        )
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
        menu.findItem(R.id.action_today)?.actionView?.setOnClickListener {
            goToCurrentWeek()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
        applyDisplaySettings()
        val returningToHome = hasResumedOnce &&
            binding.bottomNavigation.selectedItemId != R.id.nav_home
        if (binding.bottomNavigation.selectedItemId != R.id.nav_home) {
            suppressBottomNavigationMotion = true
            binding.bottomNavigation.selectedItemId = R.id.nav_home
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
            sectionEndTimes = prefs.sectionEndTimes
        )
    }

    override fun onDestroy() {
        binding.weekPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    /**
     * 请求通知权限（Android 13+ / API 33+）
     * 提醒功能依赖通知权限，未授予则提醒通知不会显示
     */
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
