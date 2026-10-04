package com.courseschedule.ui.addcourse

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivityAddCourseBinding
import com.courseschedule.ui.installPressScale
import com.courseschedule.utils.SchedulePreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
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
    private lateinit var model: CourseEditorViewModel
    private val feedback by lazy { CourseEditorFeedback(binding.root) }
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val preferences by lazy { SchedulePreferences(this) }
    private val colors by lazy {
        resources.obtainTypedArray(R.array.course_colors).let { array ->
            IntArray(array.length()) { array.getColor(it, 0) }.also { array.recycle() }
        }
    }
    private val colorNames by lazy { resources.getStringArray(R.array.editor_color_names) }
    private val days by lazy { listOf(binding.day1, binding.day2, binding.day3, binding.day4, binding.day5, binding.day6, binding.day7) }
    private val weekTypes by lazy { listOf(binding.weekEvery, binding.weekOdd, binding.weekEven) }
    private var semester: Semester? = null
    private var original: Course? = null
    private var selectedDay = 1
    private var selectedColor = 0
    private var selectedWeekType = 0
    private var reminder = -1
    private var panel = ""
    private var initial = ""
    private var loaded = false
    private var adjusting = false
    private var colorAnimation: ValueAnimator? = null
    private var revealPanel: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddCourseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = resources.getBoolean(R.bool.window_light_system_bars)
            isAppearanceLightNavigationBars = resources.getBoolean(R.bool.window_light_system_bars)
        }
        model = ViewModelProvider(this)[CourseEditorViewModel::class.java]
        binding.btnBack.setOnClickListener { requestClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { requestClose() }
        })
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        model.writing.observe(this) { writing ->
            lockForm(writing)
            binding.saveProgress.visibility = if (writing) View.VISIBLE else View.GONE
            binding.btnSave.text = getString(if (writing) R.string.saving else R.string.editor_save)
        }
        model.result.observe(this) { result ->
            result ?: return@observe
            if (result.saved) {
                if (model.consumeCompletionFeedback()) feedback.completed()
                binding.btnSave.setText(R.string.editor_saved)
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                finish()
            } else showError(result.message)
        }
        lifecycleScope.launch {
            try {
                val current = database.semesterDao().getCurrentSemesterSync()
                    ?: error(getString(R.string.editor_load_error))
                if (savedInstanceState?.containsKey("semesterId") == true && savedInstanceState.getLong("semesterId") != current.id) {
                    error(getString(R.string.editor_changed_semester))
                }
                semester = current
                val id = intent.getLongExtra("course_id", -1)
                val editing = intent.getBooleanExtra("is_edit", false)
                original = if (editing) database.courseDao().getCourseById(id) else null
                if (editing && (original == null || original?.semesterId != current.id)) {
                    error(getString(R.string.editor_changed_course))
                }
                val savedOriginal = savedInstanceState?.getString("original")
                if (savedOriginal != null && Gson().fromJson(savedOriginal, Course::class.java) != original) {
                    error(getString(R.string.editor_changed_course))
                }
                bind(savedInstanceState)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                showError(error.message ?: getString(R.string.editor_load_error))
            }
        }
    }

    private fun bind(saved: Bundle?) {
        val course = original
        selectedDay = (saved?.getInt("day") ?: course?.dayOfWeek ?: intent.getIntExtra(EXTRA_DAY_OF_WEEK, 0)
            .takeIf { it in 1..7 } ?: ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)).coerceIn(1, 7)
        selectedColor = saved?.getInt("color") ?: course?.colorIndex ?: 0
        selectedWeekType = (saved?.getInt("weekType") ?: course?.weekType ?: 0).coerceIn(0, 2)
        reminder = saved?.getInt("reminder") ?: course?.reminderMinutes ?: preferences.defaultReminderMinutes
        binding.etCourseName.setText(saved?.getString("name") ?: course?.courseName.orEmpty())
        binding.etTeacher.setText(saved?.getString("teacher") ?: course?.teacher.orEmpty())
        binding.etClassroom.setText(saved?.getString("location") ?: course?.classroom.orEmpty())
        binding.etNote.setText(saved?.getString("note") ?: course?.note.orEmpty())
        binding.tvEditorTitle.setText(if (course == null) R.string.add_course else R.string.edit_course)
        binding.tvSemester.text = semester?.name
        binding.btnDelete.visibility = if (course == null) View.GONE else View.VISIBLE
        val section = intent.getIntExtra(EXTRA_SECTION, 1).coerceIn(1, 12)
        val endSection = intent.getIntExtra(EXTRA_END_SECTION, if (intent.hasExtra(EXTRA_SECTION)) section else 2).coerceIn(section, 12)
        val maxWeeks = semester!!.totalWeeks.coerceAtLeast(1)
        val week = intent.getIntExtra(EXTRA_WEEK, 0).takeIf { it > 0 }?.coerceAtMost(maxWeeks)
        setupWheel(binding.sectionStart, 12, saved?.getInt("startSection") ?: course?.startSection ?: section, true)
        setupWheel(binding.sectionEnd, 12, saved?.getInt("endSection") ?: course?.endSection ?: endSection, true)
        setupWheel(binding.weekStart, maxWeeks, saved?.getInt("startWeek") ?: course?.startWeek ?: week ?: 1, false)
        setupWheel(binding.weekEnd, maxWeeks, saved?.getInt("endWeek") ?: course?.endWeek ?: week ?: minOf(16, maxWeeks), false)
        bindRange(binding.sectionStart, binding.sectionEnd)
        bindRange(binding.weekStart, binding.weekEnd)
        days.forEachIndexed { index, button ->
            button.contentDescription = weekday(index + 1)
            button.setOnClickListener {
                if (selectedDay != index + 1) {
                    selectedDay = index + 1; labels(true); feedback.selection()
                }
            }
        }
        weekTypes.forEachIndexed { index, button ->
            button.setOnClickListener {
                if (selectedWeekType != index) {
                    selectedWeekType = index; labels(true); feedback.selection()
                }
            }
        }
        listOf(binding.dayBar, binding.weekTypeBar).forEach { bar ->
            bar.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) indicators(false)
            }
        }
        val adapter = ColorAdapter(colors) {
            selectedColor = it; updateColor(true); labels(); showPanel(""); feedback.selection()
        }
        binding.recyclerColors.layoutManager = GridLayoutManager(this, 6)
        binding.recyclerColors.itemAnimator = null
        binding.recyclerColors.adapter = adapter
        adapter.setSelectedIndex(selectedColor)
        binding.rowSections.setOnClickListener { togglePanel("sections") }
        binding.rowWeeks.setOnClickListener { togglePanel("weeks") }
        binding.rowColor.setOnClickListener { togglePanel("color") }
        binding.rowReminder.setOnClickListener { togglePanel("reminder") }
        binding.sectionPanelDone.setOnClickListener { showPanel("") }
        binding.weekPanelDone.setOnClickListener { showPanel("") }
        binding.etCourseName.doAfterTextChanged {
            if (!it.isNullOrBlank()) binding.nameError.visibility = View.GONE
        }
        buildReminderChoices()
        loaded = true
        initial = saved?.getString("initial") ?: stateKey()
        labels(); updateColor(false)
        showPanel(saved?.getString("panel").orEmpty(), false)
        lockForm(model.writing.value == true)
        if (saved == null) CourseEditorMotion.enter(binding.formContent)
    }

    private fun setupWheel(wheel: NumberPicker, maximum: Int, selected: Int, sections: Boolean) {
        wheel.minValue = 1
        wheel.maxValue = maximum
        wheel.displayedValues = Array(maximum) { if (sections) "第${it + 1}节" else "第${it + 1}周" }
        wheel.value = selected.coerceIn(1, maximum)
        wheel.wrapSelectorWheel = false
        wheel.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        // Route value-change ticks through the root so platform wheel ticks don't stack.
        wheel.isHapticFeedbackEnabled = false
        if (Build.VERSION.SDK_INT >= 29) {
            wheel.textColor = color(R.color.editor_text)
            wheel.textSize = 21 * resources.displayMetrics.scaledDensity
            wheel.selectionDividerHeight = 0
        }
        wheel.contentDescription = getString(when (wheel) {
            binding.sectionStart -> R.string.start_section
            binding.sectionEnd -> R.string.end_section
            binding.weekStart -> R.string.start_week
            else -> R.string.end_week
        })
    }

    private fun bindRange(start: NumberPicker, end: NumberPicker) {
        start.setOnValueChangedListener { _, _, value ->
            if (!adjusting) {
                adjusting = true
                if (value > end.value) end.value = value
                adjusting = false; labels()
                if (loaded) feedback.wheelTick()
            }
        }
        end.setOnValueChangedListener { _, _, value ->
            if (!adjusting) {
                adjusting = true
                if (value < start.value) start.value = value
                adjusting = false; labels()
                if (loaded) feedback.wheelTick()
            }
        }
    }

    private fun labels(animate: Boolean = false) {
        val start = binding.sectionStart.value
        val end = binding.sectionEnd.value
        val sectionText = if (start == end) "第${start}节" else "第${start}—${end}节"
        val weekText = if (binding.weekStart.value == binding.weekEnd.value) "第${binding.weekStart.value}周"
            else "第${binding.weekStart.value}—${binding.weekEnd.value}周"
        val type = listOf("每周", "单周", "双周")[selectedWeekType]
        binding.tvSections.text = sectionText
        binding.tvSectionTimes.text = "${preferences.sectionTimes[start - 1]} — ${preferences.sectionEndTimes[end - 1]}"
        binding.tvWeeks.text = "$weekText · $type"
        binding.tvReminder.text = reminderLabel(reminder)
        binding.tvColor.text = colorNames.getOrNull(selectedColor) ?: getString(R.string.editor_original_color)
        binding.tvSaveSummary.text = "${weekday(selectedDay)} · $sectionText"
        binding.tvSaveWeeks.text = "$weekText · $type"
        binding.tvReminderDisabled.visibility = if (!preferences.reminderEnabled && reminder > 0) View.VISIBLE else View.GONE
        days.forEachIndexed { index, button -> selectLabel(button, selectedDay == index + 1) }
        weekTypes.forEachIndexed { index, button -> selectLabel(button, selectedWeekType == index) }
        indicators(animate)
        describeRows()
    }

    private fun selectLabel(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.setTextColor(color(if (selected) R.color.editor_accent else R.color.editor_secondary))
        view.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun indicators(animate: Boolean) {
        if (binding.dayBar.width > 0) CourseEditorMotion.indicator(binding.dayIndicator,
            binding.dayBar.width * (selectedDay - .5f) / 7f - binding.dayIndicator.width / 2f, animate)
        if (binding.weekTypeBar.width > 0) CourseEditorMotion.indicator(binding.weekTypeIndicator,
            binding.weekTypeBar.width * (selectedWeekType + .5f) / 3f - binding.weekTypeIndicator.width / 2f, animate)
    }

    private fun describeRows() {
        listOf(Triple(binding.rowSections, "sections", "节次，${binding.tvSections.text}，${binding.tvSectionTimes.text}"),
            Triple(binding.rowWeeks, "weeks", "周次，${binding.tvWeeks.text}"),
            Triple(binding.rowColor, "color", "课程颜色，${binding.tvColor.text}"),
            Triple(binding.rowReminder, "reminder", "课前提醒，${binding.tvReminder.text}"))
            .forEach { (row, name, description) ->
                row.contentDescription = "$description，${if (panel == name) "收起选项" else "展开选项"}"
                row.isSelected = panel == name
            }
    }

    private fun togglePanel(value: String) {
        if (!loaded || model.writing.value == true) return
        hideKeyboard()
        showPanel(if (panel == value) "" else value)
    }

    private fun showPanel(value: String, animate: Boolean = true) {
        revealPanel?.let { binding.formContent.removeCallbacks(it) }
        if (animate && panel != value) CourseEditorMotion.expand(binding.formContent)
        panel = value
        listOf(binding.sectionPanel to "sections", binding.weekPanel to "weeks",
            binding.recyclerColors to "color", binding.reminderPanel to "reminder").forEach { (view, name) ->
            view.visibility = if (name == value) View.VISIBLE else View.GONE
        }
        listOf(binding.rowSectionsChevron to "sections", binding.rowWeeksChevron to "weeks",
            binding.rowColorChevron to "color", binding.rowReminderChevron to "reminder").forEach { (view, name) ->
            CourseEditorMotion.chevron(view, name == value, animate)
        }
        describeRows()
        if (animate && value.isNotEmpty()) {
            val expanded = when (value) {
                "sections" -> binding.sectionPanel
                "weeks" -> binding.weekPanel
                "color" -> binding.recyclerColors
                else -> binding.reminderPanel
            }
            revealPanel = Runnable {
                if (panel != value || !expanded.isLaidOut) return@Runnable
                val position = IntArray(2)
                expanded.getLocationOnScreen(position)
                val bottom = position[1] + expanded.height
                binding.formScroll.getLocationOnScreen(position)
                val delta = bottom + dp(12) - (position[1] + binding.formScroll.height)
                if (delta > 0) {
                    if (ValueAnimator.areAnimatorsEnabled()) binding.formScroll.smoothScrollBy(0, delta)
                    else binding.formScroll.scrollBy(0, delta)
                }
            }.also { binding.formContent.postDelayed(it, if (ValueAnimator.areAnimatorsEnabled()) 230 else 0) }
        }
    }

    private fun updateColor(animate: Boolean) {
        val newColor = colors[Math.floorMod(selectedColor, colors.size)]
        val previous = (binding.colorPreview.background as? GradientDrawable)?.color?.defaultColor ?: newColor
        colorAnimation?.cancel()
        fun paint(value: Int) {
            binding.courseAccent.setBackgroundColor(value)
            binding.colorPreview.background = GradientDrawable().apply {
                setColor(value); cornerRadius = dp(4).toFloat()
            }
        }
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), previous, newColor).apply {
                duration = 200
                addUpdateListener { paint(it.animatedValue as Int) }; start()
            }
        } else paint(newColor)
    }

    private fun buildReminderChoices() {
        val values = listOf(-1, 5, 10, 15, 30, 60).let { if (reminder !in it) it + reminder else it }
        binding.reminderPanel.removeAllViews()
        val grid = GridLayout(this).apply { columnCount = 2 }
        values.forEachIndexed { index, minutes ->
            val choice = TextView(this).apply {
                text = reminderLabel(minutes) + if (reminder == minutes) "  ✓" else ""
                textSize = 14f; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
                setTextColor(color(if (minutes == reminder) R.color.editor_accent else R.color.editor_secondary))
                isSelected = reminder == minutes
                isFocusable = true; isClickable = true
                background = ContextCompat.getDrawable(this@AddCourseActivity, R.drawable.control_focus)
                installPressScale(.985f)
                contentDescription = "${reminderLabel(minutes)}${if (minutes == reminder) "，已选择" else ""}"
                setOnClickListener {
                    if (reminder != minutes) {
                        reminder = minutes; buildReminderChoices(); labels(); feedback.selection()
                    }
                    showPanel("")
                }
            }
            grid.addView(choice, GridLayout.LayoutParams(
                GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f)).apply { width = 0; height = dp(48) })
        }
        binding.reminderPanel.addView(grid)
    }

    private fun reminderLabel(minutes: Int) = when (minutes) {
        -1, 0 -> "不提醒"
        60 -> "提前1小时"
        else -> "提前${minutes}分钟"
    }

    private fun draft(): Course = (original ?: Course(courseName = "", dayOfWeek = 1, startSection = 1,
        endSection = 2, startWeek = 1, endWeek = 16, semesterId = semester!!.id, createTime = 0)).copy(
        courseName = binding.etCourseName.text.toString(), teacher = binding.etTeacher.text.toString(),
        classroom = binding.etClassroom.text.toString(), note = binding.etNote.text.toString(),
        dayOfWeek = selectedDay, startSection = binding.sectionStart.value, endSection = binding.sectionEnd.value,
        startWeek = binding.weekStart.value, endWeek = binding.weekEnd.value, weekType = selectedWeekType,
        colorIndex = selectedColor, reminderMinutes = reminder
    )
    private fun stateKey() = Gson().toJson(draft())

    private fun save() {
        if (!loaded || model.writing.value == true) return
        binding.formError.visibility = View.GONE
        val value = draft()
        if (value.courseName.isBlank()) {
            binding.nameError.setText(R.string.input_course_name)
            binding.nameError.visibility = View.VISIBLE
            binding.formScroll.smoothScrollTo(0, 0)
            binding.etCourseName.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(binding.etCourseName, InputMethodManager.SHOW_IMPLICIT)
            return
        }
        hideKeyboard()
        model.save(value.copy(courseName = value.courseName.trim(), teacher = value.teacher.trim(),
            classroom = value.classroom.trim(), note = value.note.trim(),
            createTime = original?.createTime ?: System.currentTimeMillis()), original, semester!!)
    }

    private fun lockForm(writing: Boolean) {
        binding.btnSave.isEnabled = loaded && !writing
        binding.btnDelete.isEnabled = loaded && !writing
        listOf(binding.etCourseName, binding.etTeacher, binding.etClassroom, binding.etNote,
            binding.rowSections, binding.rowWeeks, binding.rowColor, binding.rowReminder,
            binding.sectionStart, binding.sectionEnd, binding.weekStart, binding.weekEnd,
            binding.sectionPanelDone, binding.weekPanelDone).forEach { it.isEnabled = !writing && loaded }
        (days + weekTypes).forEach { it.isEnabled = !writing && loaded }
        binding.recyclerColors.isEnabled = !writing && loaded
        binding.recyclerColors.suppressLayout(writing)
        fun enableChildren(view: View) {
            view.isEnabled = !writing && loaded
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) enableChildren(view.getChildAt(index))
        }
        enableChildren(binding.recyclerColors)
        enableChildren(binding.reminderPanel)
    }

    private fun showError(message: String) {
        binding.formError.text = message
        binding.formError.visibility = View.VISIBLE
        binding.formError.post {
            val target = binding.scheduleGroup.top - dp(12)
            if (ValueAnimator.areAnimatorsEnabled()) binding.formScroll.smoothScrollTo(0, target)
            else binding.formScroll.scrollTo(0, target)
        }
    }

    private fun requestClose() {
        if (model.writing.value == true) return
        if (!loaded || initial == stateKey()) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_leave_title)
            .setMessage(R.string.editor_leave_message)
            .setNegativeButton(R.string.editor_keep_editing, null)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }.show()
    }

    private fun confirmDelete() {
        val course = original ?: return
        if (model.writing.value == true) return
        MaterialAlertDialogBuilder(this).setTitle(R.string.delete_course)
            .setMessage(R.string.delete_course_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> hideKeyboard(); model.delete(course, semester!!) }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (loaded) {
            outState.putLong("semesterId", semester!!.id)
            original?.let { outState.putString("original", Gson().toJson(it)) }
            outState.putString("name", binding.etCourseName.text.toString())
            outState.putString("teacher", binding.etTeacher.text.toString())
            outState.putString("location", binding.etClassroom.text.toString())
            outState.putString("note", binding.etNote.text.toString())
            outState.putInt("day", selectedDay); outState.putInt("color", selectedColor)
            outState.putInt("weekType", selectedWeekType); outState.putInt("reminder", reminder)
            outState.putInt("startSection", binding.sectionStart.value); outState.putInt("endSection", binding.sectionEnd.value)
            outState.putInt("startWeek", binding.weekStart.value); outState.putInt("endWeek", binding.weekEnd.value)
            outState.putString("panel", panel); outState.putString("initial", initial)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        colorAnimation?.cancel()
        revealPanel?.let { binding.formContent.removeCallbacks(it) }
        super.onDestroy()
    }
    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.root.requestFocus()
    }
    private fun weekday(day: Int) = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day - 1]
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
