package com.courseschedule.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.DialogCourseFocusBinding
import com.courseschedule.ui.addcourse.ColorAdapter
import com.courseschedule.ui.addcourse.CourseEditorFeedback
import com.courseschedule.ui.addcourse.CourseEditorMotion
import com.courseschedule.ui.addcourse.CourseEditorViewModel
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson

internal class CourseFocusEditor(
    private val activity: AppCompatActivity,
    private val binding: DialogCourseFocusBinding,
    private val original: Course,
    private val semester: Semester,
    private val dialog: CourseFocusDialog,
    private val restored: Bundle? = null
) {
    private val model = ViewModelProvider(activity)["courseFocusEditor", CourseEditorViewModel::class.java]
    private val feedback = CourseEditorFeedback(binding.root)
    private val fields = listOf(binding.tvDetailTitle, binding.tvDetailClassroom, binding.tvDetailTeacher, binding.tvDetailNote)
    private val days = listOf(binding.focusDay1, binding.focusDay2, binding.focusDay3, binding.focusDay4,
        binding.focusDay5, binding.focusDay6, binding.focusDay7)
    private val weekTypes = listOf(binding.focusEveryWeek, binding.focusOddWeek, binding.focusEvenWeek)
    private val courseColors = activity.resources.obtainTypedArray(R.array.course_colors).let { array ->
        IntArray(array.length()) { array.getColor(it, 0) }.also { array.recycle() }
    }
    private val savedDraft = restored?.getString("draft")?.let { Gson().fromJson(it, Course::class.java) } ?: original
    private var selectedDay = savedDraft.dayOfWeek
    private var weekType = savedDraft.weekType
    private var selectedColor = savedDraft.colorIndex
    private var reminder = savedDraft.reminderMinutes
    private var panel = ""
    private var adjusting = false
    private var initialized = false
    private var allowClose = false
    private var confirmDelete = false
    private var scrollRequest: Runnable? = null
    private val writingObserver = Observer<Boolean> { writing ->
        lockInputs(writing)
        binding.btnEditCourse.setText(if (writing) R.string.saving else R.string.course_focus_save)
        refreshSaveButton()
    }
    private val resultObserver = Observer<CourseEditorViewModel.Result?> { result ->
        if (result != null) {
            if (result.saved) {
                if (model.consumeCompletionFeedback()) feedback.completed()
                Toast.makeText(activity, result.message, Toast.LENGTH_SHORT).show()
                allowClose = true
                hideKeyboard()
                dialog.dismiss()
            } else showError(result.message)
        }
    }

    init {
        if (restored == null) model.beginSession()
        fields.zip(listOf(savedDraft.courseName, savedDraft.classroom, savedDraft.teacher, savedDraft.note))
            .forEach { (field, value) ->
                field.filters = arrayOf(InputFilter.LengthFilter(maxOf(if (field === binding.tvDetailNote) 2000 else 120, value.length)))
            }
        binding.tvDetailTitle.setText(savedDraft.courseName)
        binding.tvDetailClassroom.setText(savedDraft.classroom)
        binding.tvDetailTeacher.setText(savedDraft.teacher)
        binding.tvDetailNote.setText(savedDraft.note)
        setupWheel(binding.focusSectionStart, maxOf(12, original.endSection), savedDraft.startSection, true)
        setupWheel(binding.focusSectionEnd, maxOf(12, original.endSection), savedDraft.endSection, true)
        setupWheel(binding.focusWeekStart, maxOf(semester.totalWeeks, original.endWeek), savedDraft.startWeek, false)
        setupWheel(binding.focusWeekEnd, maxOf(semester.totalWeeks, original.endWeek), savedDraft.endWeek, false)
        bindRange(binding.focusSectionStart, binding.focusSectionEnd)
        bindRange(binding.focusWeekStart, binding.focusWeekEnd)
        days.forEachIndexed { index, button ->
            button.contentDescription = activity.resources.getStringArray(R.array.weekdays)[index]
            button.setOnClickListener { selectedDay = index + 1; labels(); feedback.selection() }
        }
        weekTypes.forEachIndexed { index, button ->
            button.setOnClickListener { weekType = index; labels(); feedback.selection() }
        }
        binding.rowFocusTime.setOnClickListener { togglePanel("time") }
        binding.rowFocusWeeks.setOnClickListener { togglePanel("weeks") }
        binding.btnFocusTimeDone.setOnClickListener { labels(); showPanel("") }
        binding.btnFocusWeeksDone.setOnClickListener { labels(); showPanel("") }
        binding.btnFocusMore.setOnClickListener { togglePanel("more") }
        binding.focusColorChoices.layoutManager = GridLayoutManager(activity, 6)
        binding.focusColorChoices.itemAnimator = null
        val colorAdapter = ColorAdapter(courseColors) { selectedColor = it; labels(); feedback.selection() }
        colorAdapter.setSelectedIndex(selectedColor)
        binding.focusColorChoices.adapter = colorAdapter
        buildReminderChoices()
        fields.forEach { field ->
            field.doAfterTextChanged {
                if (initialized) {
                    binding.focusError.visibility = View.GONE
                    binding.tvTeacherHint.visibility = if (binding.tvDetailTeacher.text.isNullOrBlank()) View.VISIBLE else View.GONE
                    refreshSaveButton()
                }
            }
            field.setOnFocusChangeListener { _, focused ->
                if (focused) showPanel("", false)
                refreshSaveButton()
            }
        }
        binding.btnEditCourse.installPressScale(.97f)
        binding.btnEditCourse.setOnClickListener { save() }
        binding.btnFocusCancel.setOnClickListener { dialog.cancel() }
        binding.btnFocusDelete.setOnClickListener { hideKeyboard(); showConfirmation(true) }
        binding.btnFocusKeep.setOnClickListener { hideConfirmation() }
        binding.btnFocusDiscard.setOnClickListener {
            if (confirmDelete) {
                hideConfirmation()
                model.delete(original, semester)
            } else {
                allowClose = true
                dialog.cancel()
            }
        }
        dialog.onCloseRequest = {
            when {
                allowClose -> true
                model.writing.value == true -> false
                draft() == original -> { hideKeyboard(); true }
                else -> { hideKeyboard(); showConfirmation(false); false }
            }
        }
        initialized = true
        labels()
        showPanel(restored?.getString("panel").orEmpty(), false)
        binding.root.requestFocus()
    }

    fun start() {
        model.writing.observe(activity, writingObserver)
        model.result.observe(activity, resultObserver)
        if (restored != null && model.result.value?.saved != true) binding.root.post {
            binding.courseDetailScroll.scrollTo(0, restored.getInt("scroll"))
            val focused = restored.getInt("focused")
            fields.firstOrNull { it.id == focused }?.let { field ->
                field.requestFocus()
                field.setSelection(restored.getInt("selection", field.text?.length ?: 0)
                    .coerceIn(0, field.text?.length ?: 0))
                keyboard().showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun snapshot() = Bundle().apply {
        putString("original", Gson().toJson(original))
        putString("semester", Gson().toJson(semester))
        putString("draft", Gson().toJson(draft()))
        putString("panel", panel)
        putInt("scroll", binding.courseDetailScroll.scrollY)
        fields.firstOrNull { it.hasFocus() }?.let {
            putInt("focused", it.id)
            putInt("selection", it.selectionStart)
        }
    }

    fun dispose() {
        model.writing.removeObserver(writingObserver)
        model.result.removeObserver(resultObserver)
        scrollRequest?.let { binding.detailForm.removeCallbacks(it) }
        dialog.onCloseRequest = null
    }

    private fun setupWheel(wheel: NumberPicker, maximum: Int, value: Int, section: Boolean) {
        wheel.minValue = 1
        wheel.maxValue = maximum.coerceAtLeast(1)
        wheel.wrapSelectorWheel = false
        wheel.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        wheel.setFormatter { if (section) "第${it}节" else "第${it}周" }
        wheel.value = value.coerceIn(1, wheel.maxValue)
    }

    private fun bindRange(start: NumberPicker, end: NumberPicker) {
        start.setOnValueChangedListener { _, _, value ->
            if (!adjusting) {
                adjusting = true
                if (value > end.value) end.value = value
                adjusting = false
                labels(); feedback.wheelTick()
            }
        }
        end.setOnValueChangedListener { _, _, value ->
            if (!adjusting) {
                adjusting = true
                if (value < start.value) start.value = value
                adjusting = false
                labels(); feedback.wheelTick()
            }
        }
    }

    private fun labels() {
        val weekdays = activity.resources.getStringArray(R.array.weekdays)
        binding.tvDetailTime.text = activity.getString(R.string.course_time_detail,
            weekdays[selectedDay - 1], binding.focusSectionStart.value, binding.focusSectionEnd.value)
        val start = binding.focusWeekStart.value
        val end = binding.focusWeekEnd.value
        val range = activity.getString(if (start == end) R.string.week_format else R.string.week_range_format, start, end)
        val type = activity.getString(when (weekType) { 1 -> R.string.odd_week; 2 -> R.string.even_week; else -> R.string.every_week })
        binding.tvDetailWeeks.text = activity.getString(R.string.course_week_detail, range, type)
        days.forEachIndexed { index, view -> select(view, selectedDay == index + 1) }
        weekTypes.forEachIndexed { index, view -> select(view, weekType == index) }
        dialog.setCourseColor(courseColors[Math.floorMod(selectedColor, courseColors.size)])
        binding.detailCourseColor.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(courseColors[Math.floorMod(selectedColor, courseColors.size)])
            setStroke(dp(1), color(R.color.course_focus_edge))
        }
        binding.tvTeacherHint.visibility = if (binding.tvDetailTeacher.text.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.rowFocusTime.contentDescription = "上课时间，${binding.tvDetailTime.text}，点按修改"
        binding.rowFocusWeeks.contentDescription = "上课周次，${binding.tvDetailWeeks.text}，点按修改"
        refreshSaveButton()
    }

    private fun select(view: MaterialButton, selected: Boolean) {
        view.isSelected = selected
        view.setTextColor(color(if (selected) R.color.course_focus_text else R.color.course_focus_secondary))
        view.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        view.backgroundTintList = ColorStateList.valueOf(if (selected) color(R.color.course_focus_divider) else Color.TRANSPARENT)
    }

    private fun draft() = original.copy(
        courseName = binding.tvDetailTitle.text.toString(),
        classroom = binding.tvDetailClassroom.text.toString(),
        teacher = binding.tvDetailTeacher.text.toString(),
        note = binding.tvDetailNote.text.toString(),
        dayOfWeek = selectedDay,
        startSection = binding.focusSectionStart.value,
        endSection = binding.focusSectionEnd.value,
        startWeek = binding.focusWeekStart.value,
        endWeek = binding.focusWeekEnd.value,
        weekType = weekType,
        colorIndex = selectedColor,
        reminderMinutes = reminder
    )

    private fun save() {
        if (model.writing.value == true) return
        val value = draft()
        binding.focusError.visibility = View.GONE
        if (value.courseName.isBlank()) {
            showError(activity.getString(R.string.input_course_name))
            binding.courseDetailScroll.smoothScrollTo(0, 0)
            binding.tvDetailTitle.requestFocus()
            keyboard().showSoftInput(binding.tvDetailTitle, InputMethodManager.SHOW_IMPLICIT)
            return
        }
        hideKeyboard()
        hideConfirmation()
        model.save(value.copy(courseName = value.courseName.trim(), classroom = value.classroom.trim(),
            teacher = value.teacher.trim(), note = value.note.trim()), original, semester)
    }

    private fun refreshSaveButton() {
        binding.btnEditCourse.isEnabled = initialized && model.writing.value != true && draft() != original
        binding.btnEditCourse.alpha = 1f
        binding.focusActions.visibility = if (binding.focusConfirmation.visibility != View.VISIBLE &&
            (binding.btnEditCourse.isEnabled || model.writing.value == true || fields.any { it.hasFocus() }))
            View.VISIBLE else View.GONE
    }

    private fun togglePanel(value: String) {
        if (model.writing.value == true) return
        hideKeyboard()
        hideConfirmation()
        showPanel(if (panel == value) "" else value)
    }

    private fun showPanel(value: String, animate: Boolean = true) {
        scrollRequest?.let { binding.detailForm.removeCallbacks(it) }
        if (animate && panel != value) CourseEditorMotion.expand(binding.detailForm)
        panel = value
        binding.rowDetailNote.visibility = if (!binding.tvDetailNote.text.isNullOrBlank() ||
            value == "more" || binding.tvDetailNote.hasFocus()) View.VISIBLE else View.GONE
        listOf(binding.focusTimePanel to "time", binding.focusWeeksPanel to "weeks", binding.focusMorePanel to "more")
            .forEach { (view, name) -> view.visibility = if (value == name) View.VISIBLE else View.GONE }
        CourseEditorMotion.chevron(binding.focusTimeChevron, value == "time", animate)
        CourseEditorMotion.chevron(binding.focusWeeksChevron, value == "weeks", animate)
        if (animate && value.isNotEmpty()) {
            val expanded = when (value) { "time" -> binding.focusTimePanel; "weeks" -> binding.focusWeeksPanel; else -> binding.focusMorePanel }
            scrollRequest = Runnable {
                if (panel != value || !expanded.isLaidOut) return@Runnable
                val bounds = Rect()
                expanded.getDrawingRect(bounds)
                binding.detailForm.offsetDescendantRectToMyCoords(expanded, bounds)
                val target = (bounds.bottom + dp(12) - binding.courseDetailScroll.height).coerceAtLeast(0)
                if (target > binding.courseDetailScroll.scrollY) binding.courseDetailScroll.smoothScrollTo(0, target)
            }.also { binding.detailForm.postDelayed(it, if (android.animation.ValueAnimator.areAnimatorsEnabled()) 240 else 0) }
        }
    }

    private fun showConfirmation(delete: Boolean) {
        confirmDelete = delete
        binding.focusConfirmationText.setText(if (delete) R.string.course_focus_delete_prompt else R.string.course_focus_unsaved)
        binding.btnFocusKeep.setText(if (delete) R.string.cancel else R.string.course_focus_keep)
        binding.btnFocusDiscard.setText(if (delete) R.string.delete else R.string.course_focus_discard)
        binding.focusConfirmation.visibility = View.VISIBLE
        binding.focusActions.visibility = View.GONE
    }

    private fun hideConfirmation() {
        binding.focusConfirmation.visibility = View.GONE
        refreshSaveButton()
    }

    private fun buildReminderChoices() {
        binding.focusReminderChoices.removeAllViews()
        val values = listOf(-1, 5, 10, 15, 30, 60).let { if (reminder !in it) it + reminder else it }
        val grid = GridLayout(activity).apply { columnCount = 2 }
        values.forEachIndexed { index, minutes ->
            val text = when (minutes) { -1, 0 -> "不提醒"; 60 -> "提前1小时"; else -> "提前${minutes}分钟" }
            val choice = TextView(activity).apply {
                this.text = text + if (minutes == reminder) "  ✓" else ""
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(color(if (minutes == reminder) R.color.course_focus_text else R.color.course_focus_secondary))
                setPadding(dp(6), 0, dp(4), 0)
                background = ContextCompat.getDrawable(activity, R.drawable.control_focus)
                isClickable = true; isFocusable = true; isSelected = minutes == reminder
                contentDescription = text + if (minutes == reminder) "，已选择" else ""
                installPressScale(.985f)
                setOnClickListener { reminder = minutes; buildReminderChoices(); labels(); feedback.selection() }
            }
            grid.addView(choice, GridLayout.LayoutParams(GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f))
                .apply { width = 0; height = dp(48) })
        }
        binding.focusReminderChoices.addView(grid)
    }

    private fun lockInputs(writing: Boolean) {
        fun enable(view: View) {
            view.isEnabled = !writing
            if (view is ViewGroup) for (index in 0 until view.childCount) enable(view.getChildAt(index))
        }
        enable(binding.detailForm)
        binding.btnFocusCancel.isEnabled = !writing
        binding.btnCloseCourse.isEnabled = !writing
        binding.btnFocusKeep.isEnabled = !writing
        binding.btnFocusDiscard.isEnabled = !writing
    }

    private fun showError(message: String) {
        binding.focusError.text = message
        binding.focusError.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        keyboard().hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.root.requestFocus()
    }
    private fun keyboard() = activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
