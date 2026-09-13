package com.courseschedule.ui.addcourse

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.databinding.ActivityAddCourseBinding
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.viewmodel.CourseViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Calendar

class AddCourseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DAY_OF_WEEK = "day_of_week"
        const val EXTRA_SECTION = "section"
        const val EXTRA_END_SECTION = "end_section"
        const val EXTRA_WEEK = "week"
    }

    private lateinit var binding: ActivityAddCourseBinding
    private lateinit var viewModel: CourseViewModel

    private var isEditMode = false
    private var courseId = -1L
    private var selectedDay = 1
    private var selectedColorIndex = 0
    private var selectedWeekType = 0
    private var originalCourse: Course? = null

    private val sectionOptions by lazy {
        Array(12) { getString(R.string.section_format, it + 1) }
    }
    private var weekOptions: Array<String> = emptyArray()
    private var courseLoaded = false
    private val reminderOptions = arrayOf("不提醒", "5分钟前", "10分钟前", "15分钟前", "30分钟前", "1小时前")
    private val reminderValues = intArrayOf(-1, 5, 10, 15, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddCourseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        viewModel = ViewModelProvider(this)[CourseViewModel::class.java]

        isEditMode = intent.getBooleanExtra("is_edit", false)
        courseId = intent.getLongExtra("course_id", -1)

        initViews()
        viewModel.currentSemester.observe(this) { semester ->
            semester ?: return@observe
            configureWeekDropdowns(semester.totalWeeks)
            if (isEditMode && !courseLoaded) loadCourseData()
        }
        if (isEditMode) {
            supportActionBar?.title = getString(R.string.edit_course)
            binding.btnDelete.visibility = View.VISIBLE
        } else {
            supportActionBar?.title = getString(R.string.add_course)
            binding.btnDelete.visibility = View.GONE
        }
    }

    private fun initViews() {
        initDayChips()
        initDropdowns()
        initWeekTypeChips()
        initColorPicker()
        initReminderDropdown()
        binding.btnSave.setOnClickListener { saveCourse() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmDialog() }
    }

    private fun initDayChips() {
        val chips = listOf(
            binding.chipMon, binding.chipTue, binding.chipWed,
            binding.chipThu, binding.chipFri, binding.chipSat, binding.chipSun
        )
        selectedDay = intent.getIntExtra(EXTRA_DAY_OF_WEEK, 0).takeIf { it in 1..7 } ?: when (
            Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        ) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        chips[selectedDay - 1].isChecked = true

        binding.chipGroupDay.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedDay = when (checkedIds.firstOrNull()) {
                R.id.chipMon -> 1
                R.id.chipTue -> 2
                R.id.chipWed -> 3
                R.id.chipThu -> 4
                R.id.chipFri -> 5
                R.id.chipSat -> 6
                R.id.chipSun -> 7
                else -> selectedDay
            }
        }
    }

    private fun initDropdowns() {
        val startSection = intent.getIntExtra(EXTRA_SECTION, 0).takeIf { it in 1..12 }
        val endSection = intent.getIntExtra(EXTRA_END_SECTION, 0).takeIf { it in 1..12 }
            ?: startSection
        setupDropdown(binding.spinnerStartSection, sectionOptions, (startSection ?: 1) - 1)
        setupDropdown(binding.spinnerEndSection, sectionOptions, (endSection ?: 2) - 1)
    }

    private fun configureWeekDropdowns(totalWeeks: Int) {
        val previousStart = selectedNumber(binding.spinnerStartWeek)
        val previousEnd = selectedNumber(binding.spinnerEndWeek)
        val requestedWeek = intent.getIntExtra(EXTRA_WEEK, 0)
            .takeIf { it > 0 }
            ?.coerceAtMost(totalWeeks)
        weekOptions = Array(totalWeeks.coerceAtLeast(1)) { getString(R.string.week_format, it + 1) }
        setupDropdown(binding.spinnerStartWeek, weekOptions, (previousStart ?: requestedWeek ?: 1) - 1)
        setupDropdown(
            binding.spinnerEndWeek,
            weekOptions,
            ((previousEnd ?: requestedWeek ?: minOf(16, totalWeeks)) - 1).coerceAtLeast(0)
        )
    }

    private fun setupDropdown(
        view: AutoCompleteTextView,
        options: Array<String>,
        defaultIndex: Int
    ) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, options))
        view.setText(options[defaultIndex.coerceIn(options.indices)], false)
    }

    private fun initWeekTypeChips() {
        binding.chipGroupWeekType.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedWeekType = when (checkedIds.firstOrNull()) {
                R.id.chipOddWeek -> 1
                R.id.chipEvenWeek -> 2
                else -> 0
            }
        }
    }

    private fun initColorPicker() {
        val colorsArray = resources.obtainTypedArray(R.array.course_colors)
        val colors = IntArray(colorsArray.length()) { colorsArray.getColor(it, 0) }
        colorsArray.recycle()

        val adapter = ColorAdapter(colors) { selectedColorIndex = it }
        binding.recyclerColors.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.recyclerColors.adapter = adapter
        adapter.setSelectedIndex(0)
    }

    private fun initReminderDropdown() {
        binding.spinnerReminder.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, reminderOptions)
        )
        val preferences = SchedulePreferences(this)
        val defaultMinutes = if (preferences.reminderEnabled) {
            preferences.defaultReminderMinutes
        } else {
            -1
        }
        val index = reminderValues.indexOf(defaultMinutes).takeIf { it >= 0 } ?: 0
        binding.spinnerReminder.setText(reminderOptions[index], false)
    }

    private fun loadCourseData() {
        lifecycleScope.launch {
            viewModel.getCourseById(courseId)?.let {
                courseLoaded = true
                originalCourse = it
                populateFields(it)
            }
        }
    }

    private fun populateFields(course: Course) {
        binding.etCourseName.setText(course.courseName)
        binding.etTeacher.setText(course.teacher)
        binding.etClassroom.setText(course.classroom)
        binding.etNote.setText(course.note)

        selectedDay = course.dayOfWeek
        listOf(
            binding.chipMon, binding.chipTue, binding.chipWed,
            binding.chipThu, binding.chipFri, binding.chipSat, binding.chipSun
        ).getOrNull(course.dayOfWeek - 1)?.isChecked = true

        binding.spinnerStartSection.setText(sectionOptions[course.startSection - 1], false)
        binding.spinnerEndSection.setText(sectionOptions[course.endSection - 1], false)
        binding.spinnerStartWeek.setText(
            weekOptions[(course.startWeek - 1).coerceIn(weekOptions.indices)],
            false
        )
        binding.spinnerEndWeek.setText(
            weekOptions[(course.endWeek - 1).coerceIn(weekOptions.indices)],
            false
        )

        selectedWeekType = course.weekType
        when (course.weekType) {
            1 -> binding.chipOddWeek.isChecked = true
            2 -> binding.chipEvenWeek.isChecked = true
            else -> binding.chipEveryWeek.isChecked = true
        }

        selectedColorIndex = course.colorIndex
        (binding.recyclerColors.adapter as? ColorAdapter)?.setSelectedIndex(selectedColorIndex)
        binding.recyclerColors.scrollToPosition(selectedColorIndex)

        val reminderIndex = reminderValues.indexOf(course.reminderMinutes).takeIf { it >= 0 } ?: 0
        binding.spinnerReminder.setText(reminderOptions[reminderIndex], false)
    }

    private fun saveCourse() {
        clearValidationErrors()
        val courseName = binding.etCourseName.text?.toString()?.trim().orEmpty()
        if (courseName.isBlank()) {
            binding.tilCourseName.error = getString(R.string.input_course_name)
            binding.etCourseName.requestFocus()
            binding.formScroll.smoothScrollTo(0, 0)
            return
        }

        val startSection = selectedIndex(binding.spinnerStartSection, sectionOptions) + 1
        val endSection = selectedIndex(binding.spinnerEndSection, sectionOptions) + 1
        if (startSection > endSection) {
            binding.tilEndSection.error = getString(R.string.end_before_start)
            return
        }

        val startWeek = selectedIndex(binding.spinnerStartWeek, weekOptions) + 1
        val endWeek = selectedIndex(binding.spinnerEndWeek, weekOptions) + 1
        if (startWeek > endWeek) {
            binding.tilEndWeek.error = getString(R.string.end_week_before_start)
            return
        }

        val semesterId = viewModel.currentSemester.value?.id
        if (semesterId == null || weekOptions.isEmpty()) {
            Toast.makeText(this, R.string.semester_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val reminderIndex = reminderOptions.indexOf(binding.spinnerReminder.text.toString())
        val course = Course(
            id = if (isEditMode) courseId else 0,
            courseName = courseName,
            teacher = binding.etTeacher.text?.toString()?.trim().orEmpty(),
            classroom = binding.etClassroom.text?.toString()?.trim().orEmpty(),
            dayOfWeek = selectedDay,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = selectedWeekType,
            semesterId = semesterId,
            colorIndex = selectedColorIndex,
            note = binding.etNote.text?.toString()?.trim().orEmpty(),
            reminderMinutes = reminderValues.getOrElse(reminderIndex) { -1 },
            createTime = originalCourse?.createTime ?: System.currentTimeMillis()
        )

        binding.btnSave.isEnabled = false
        binding.btnSave.setText(R.string.saving)
        lifecycleScope.launch {
            val hasConflict = viewModel.checkConflict(
                selectedDay,
                startSection,
                endSection,
                startWeek,
                endWeek,
                selectedWeekType,
                if (isEditMode) courseId else 0
            )
            if (hasConflict) {
                binding.btnSave.isEnabled = true
                binding.btnSave.setText(R.string.save_course)
                MaterialAlertDialogBuilder(this@AddCourseActivity)
                    .setTitle(R.string.course_conflict_title)
                    .setMessage(R.string.course_conflict)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }

            val reminderManager = ReminderManager(this@AddCourseActivity)
            val savedCourse = if (isEditMode) {
                viewModel.updateCourseNow(course)
                reminderManager.cancelReminder(course.id)
                course
            } else {
                course.copy(id = viewModel.insertCourseAndGetId(course))
            }
            if (savedCourse.reminderMinutes > 0) {
                viewModel.currentSemester.value?.let { reminderManager.setReminder(savedCourse, it) }
            }
            Toast.makeText(this@AddCourseActivity, R.string.save_success, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun clearValidationErrors() {
        binding.tilCourseName.error = null
        binding.tilStartSection.error = null
        binding.tilEndSection.error = null
        binding.tilStartWeek.error = null
        binding.tilEndWeek.error = null
    }

    private fun selectedIndex(view: AutoCompleteTextView, options: Array<String>): Int {
        return options.indexOf(view.text.toString()).coerceAtLeast(0)
    }

    private fun selectedNumber(view: AutoCompleteTextView): Int? {
        return Regex("\\d+").find(view.text?.toString().orEmpty())?.value?.toIntOrNull()
    }

    private fun showDeleteConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_course)
            .setMessage(R.string.delete_course_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    ReminderManager(this@AddCourseActivity).cancelReminder(courseId)
                    viewModel.deleteCourseNow(courseId)
                    Toast.makeText(this@AddCourseActivity, R.string.delete_success, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
