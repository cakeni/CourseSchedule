package com.courseschedule.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val week = position + 1
            if (currentWeek != week) {
                currentWeek = week
                viewModel.setCurrentWeek(week)
            }
            updateWeekDisplay()
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
            onAddCourse = { startActivity(Intent(this, AddCourseActivity::class.java)) }
        )
        binding.weekPager.adapter = weekPagerAdapter
        binding.weekPager.offscreenPageLimit = 1
        binding.weekPager.registerOnPageChangeCallback(pageChangeCallback)

        binding.btnPreviousWeek.setOnClickListener {
            selectWeek(currentWeek - 1, smoothScroll = true)
        }

        binding.btnNextWeek.setOnClickListener {
            selectWeek(currentWeek + 1, smoothScroll = true)
        }

        binding.weekInfo.setOnClickListener { showWeekPicker() }

        binding.fabAddCourse.setOnClickListener {
            startActivity(Intent(this, AddCourseActivity::class.java))
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_import -> {
                    startActivity(Intent(this, ImportActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun observeData() {
        viewModel.currentSemester.observe(this) { semester ->
            currentSemester = semester
            semester?.let {
                binding.toolbar.subtitle = it.name
                refreshWeekPager()
                syncPagerToCurrentWeek(smoothScroll = false)
                updateWeekDisplay()
            }
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
            currentCourses = courses
            refreshWeekPager()
            updateWeekDisplay()
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

    private fun showCourseDetails(course: Course) {
        val detailView = layoutInflater.inflate(R.layout.dialog_course_details, null)
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

        MaterialAlertDialogBuilder(this)
            .setTitle(course.courseName)
            .setView(detailView)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.edit) { _, _ -> openCourseEditor(course) }
            .show()
    }

    private fun openCourseEditor(course: Course) {
        val intent = Intent(this, AddCourseActivity::class.java).apply {
            putExtra("course_id", course.id)
            putExtra("is_edit", true)
        }
        startActivity(intent)
    }

    /**
     * 更新周次显示
     */
    private fun updateWeekDisplay() {
        val semester = currentSemester ?: return
        val status = semesterWeekStatus
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
        binding.fabAddCourse.visibility = if (visibleCourses.isEmpty()) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }

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

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_week)
            .setSingleChoiceItems(weeks, currentWeek - 1) { dialog, which ->
                dialog.dismiss()
                selectWeek(which + 1, smoothScroll = true)
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_today -> {
                viewModel.refreshSemesterStatus()
                semesterWeekStatus?.week?.let { selectWeek(it, smoothScroll = true) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        applyDisplaySettings()
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        refreshWeekPager()
        updateWeekDisplay()
    }

    private fun applyDisplaySettings() {
        val prefs = SchedulePreferences(this)
        pageSettings = WeekPageSettings(
            showWeekend = prefs.showWeekend,
            showTimes = prefs.showTime,
            sectionHeightDp = prefs.sectionHeightDp,
            sectionTimes = prefs.sectionTimes
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
