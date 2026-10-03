package com.courseschedule.ui.assistant

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.databinding.ActivityStudyTaskEditorBinding
import com.courseschedule.domain.StudyTaskRules
import com.courseschedule.ui.installPressScale
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class StudyTaskEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudyTaskEditorBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var semester: Semester? = null
    private var original: StudyTask? = null
    private var courses = emptyMap<String, List<Course>>()
    private var kind = "homework"
    private var courseName = ""
    private var reminder = -1
    private var date: LocalDate? = null
    private var time: LocalTime? = null
    private var month = YearMonth.now()
    private var panel = ""
    private var initial = ""
    private var writing = false
    private var loaded = false
    private var confirmingDelete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudyTaskEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = color(R.color.study_background)
        window.navigationBarColor = color(R.color.study_background)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = resources.getBoolean(R.bool.window_light_system_bars)
            isAppearanceLightNavigationBars = resources.getBoolean(R.bool.window_light_system_bars)
        }
        binding.btnTaskCancel.setOnClickListener { requestClose() }
        binding.btnTaskSave.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { requestClose() }
        })
        lifecycleScope.launch {
            val current = database.semesterDao().getCurrentSemesterSync()
            if (current == null) { finish(); return@launch }
            if (savedInstanceState?.containsKey("semesterId") == true && savedInstanceState.getLong("semesterId") != current.id) {
                finish(); return@launch
            }
            semester = current
            courses = database.courseDao().getCoursesBySemesterSync(current.id).groupBy { it.courseName }
            val id = intent.getLongExtra("study_task_id", 0)
            original = savedInstanceState?.getString("original")?.let { Gson().fromJson(it, StudyTask::class.java) }
                ?: if (id > 0) database.studyTaskDao().find(id) else null
            if (id > 0 && (original == null || original?.semesterId != current.id)) { finish(); return@launch }
            bind(savedInstanceState)
        }
    }

    private fun bind(saved: Bundle?) {
        val local = original?.let { Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        kind = saved?.getString("kind") ?: original?.kind ?: "homework"
        courseName = saved?.getString("courseName") ?: original?.courseName.orEmpty()
        reminder = saved?.getInt("reminder") ?: original?.reminderMinutes ?: -1
        date = saved?.getString("date")?.let(LocalDate::parse) ?: local?.toLocalDate()
        time = saved?.getString("time")?.let(LocalTime::parse) ?: local?.toLocalTime()
        month = saved?.getString("month")?.let(YearMonth::parse) ?: date?.let(YearMonth::from) ?: YearMonth.now()
        binding.etTaskTitle.setText(saved?.getString("title") ?: original?.title.orEmpty())
        binding.etTaskNote.setText(saved?.getString("note") ?: original?.note.orEmpty())
        binding.tvEditorTitle.text = if (original == null) "新建待办" else "编辑待办"
        binding.tvEditorSemester.text = semester?.name
        binding.btnTaskDelete.visibility = if (original == null) View.GONE else View.VISIBLE
        initial = saved?.getString("initial") ?: stateKey()
        listOf(binding.btnKindHomework to "homework", binding.btnKindExam to "exam", binding.btnKindReport to "report").forEach { (button, value) ->
            button.setOnClickListener {
                kind = value; labels()
            }
        }
        binding.btnTaskDate.setOnClickListener { togglePanel("date") }
        binding.btnTaskTime.setOnClickListener { togglePanel("time") }
        binding.btnTaskCourse.setOnClickListener { togglePanel("course") }
        binding.btnTaskReminder.setOnClickListener { togglePanel("reminder") }
        binding.btnPreviousMonth.setOnClickListener { month = month.minusMonths(1); calendar() }
        binding.btnNextMonth.setOnClickListener { month = month.plusMonths(1); calendar() }
        binding.btnDateToday.setOnClickListener { selectDate(LocalDate.now()) }
        binding.btnDateTomorrow.setOnClickListener { selectDate(LocalDate.now().plusDays(1)) }
        setupWheel(binding.taskHour, 23, saved?.getInt("hour") ?: time?.hour ?: 20)
        setupWheel(binding.taskMinute, 59, saved?.getInt("minute") ?: time?.minute ?: 0)
        binding.btnTimeDone.setOnClickListener {
            selectTime(LocalTime.of(binding.taskHour.value, binding.taskMinute.value))
        }
        binding.btnTimeEvening.setOnClickListener { selectTime(LocalTime.of(20, 0)) }
        binding.btnTimeEndOfDay.setOnClickListener { selectTime(LocalTime.of(23, 59)) }
        binding.etCourseSearch.doAfterTextChanged { courseChoices() }
        binding.btnTaskDelete.setOnClickListener { confirmAction(true) }
        binding.btnKeepEditing.setOnClickListener { binding.confirmationPanel.visibility = View.GONE }
        binding.btnConfirmAction.setOnClickListener { if (confirmingDelete) delete() else finish() }
        loaded = true
        binding.btnTaskSave.isEnabled = true
        calendar(); labels(); courseChoices(); reminderChoices()
        showPanel(saved?.getString("panel").orEmpty())
    }

    private fun labels() {
        binding.btnKindHomework.isChecked = kind == "homework"
        binding.btnKindExam.isChecked = kind == "exam"
        binding.btnKindReport.isChecked = kind == "report"
        binding.tvTaskDateValue.text = date?.format(DateTimeFormatter.ofPattern(
            if (date?.year == LocalDate.now().year) "M月d日 EEE" else "yyyy/M/d EEE", Locale.CHINA)) ?: "选择日期"
        binding.tvTaskTimeValue.text = time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "选择时间"
        binding.tvTaskCourseValue.text = courseName.ifBlank { "不关联课程" }
        binding.tvTaskReminderValue.text = StudyTaskRules.reminders[reminder] ?: "提前${reminder}分钟"
        listOf(binding.tvTaskDateValue to (date != null), binding.tvTaskTimeValue to (time != null),
            binding.tvTaskCourseValue to courseName.isNotBlank(), binding.tvTaskReminderValue to (reminder >= 0)).forEach { (view, chosen) ->
            view.setTextColor(color(if (chosen) R.color.study_accent else R.color.study_text_secondary))
        }
        describeRows()
    }

    private fun selectDate(value: LocalDate) {
        date = value; month = YearMonth.from(value)
        calendar(); labels(); showPanel("")
    }

    private fun selectTime(value: LocalTime) {
        time = value
        binding.taskHour.value = value.hour; binding.taskMinute.value = value.minute
        labels(); showPanel("")
    }

    private fun togglePanel(name: String) {
        hideKeyboard()
        binding.confirmationPanel.visibility = View.GONE
        showPanel(if (panel == name) "" else name)
        if (panel.isNotEmpty()) {
            val anchor = when (name) {
                "date" -> binding.btnTaskDate
                "time" -> binding.btnTaskTime
                "course" -> binding.btnTaskCourse
                else -> binding.btnTaskReminder
            }
            val expanded = when (name) {
                "date" -> binding.datePanel
                "time" -> binding.timePanel
                "course" -> binding.coursePanel
                else -> binding.reminderPanel
            }
            reveal(anchor, expanded)
        }
    }

    private fun showPanel(name: String) {
        if (name != panel) StudyMotion.panel(binding.editorContent)
        panel = name
        binding.datePanel.visibility = if (name == "date") View.VISIBLE else View.GONE
        binding.timePanel.visibility = if (name == "time") View.VISIBLE else View.GONE
        binding.coursePanel.visibility = if (name == "course") View.VISIBLE else View.GONE
        binding.reminderPanel.visibility = if (name == "reminder") View.VISIBLE else View.GONE
        describeRows()
    }

    private fun describeRows() {
        listOf(Triple(binding.btnTaskDate, binding.tvTaskDateValue, "date"), Triple(binding.btnTaskTime, binding.tvTaskTimeValue, "time"),
            Triple(binding.btnTaskCourse, binding.tvTaskCourseValue, "course"), Triple(binding.btnTaskReminder, binding.tvTaskReminderValue, "reminder")).forEach { (row, value, name) ->
            row.contentDescription = "${value.text}，${if (panel == name) "收起选择" else "展开选择"}"
        }
        listOf(binding.iconTaskDate to "date", binding.iconTaskTime to "time", binding.iconTaskCourse to "course", binding.iconTaskReminder to "reminder").forEach { (icon, name) ->
            icon.rotation = if (panel == name) 90f else 0f
        }
    }

    private fun calendar() {
        binding.tvCalendarMonth.text = "${month.year}年${month.monthValue}月"
        binding.btnPreviousMonth.isEnabled = month > YearMonth.of(2000, 1)
        binding.btnNextMonth.isEnabled = month < YearMonth.of(2099, 12)
        val grid = binding.calendarDays
        grid.removeAllViews()
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day -> grid.addView(dayCell(day, false).apply {
            setTextColor(color(R.color.study_text_secondary)); textSize = 11f
        }) }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
        repeat(cells) { index ->
            val day = index - offset + 1
            val valid = day in 1..month.lengthOfMonth()
            val cell = dayCell(if (valid) day.toString() else "", valid)
            if (valid) {
                val value = month.atDay(day)
                cell.contentDescription = "$value"
                if (value == date) {
                    cell.background = dayHighlight(true)
                    cell.setTextColor(color(R.color.study_on_accent))
                } else if (value == LocalDate.now()) {
                    cell.background = dayHighlight(false)
                }
                cell.setOnClickListener { selectDate(value) }
            } else cell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            grid.addView(cell)
        }
    }

    private fun dayHighlight(selected: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (selected) setColor(color(R.color.study_accent)) else setStroke(dp(1), color(R.color.study_text_secondary))
    })).apply {
        setLayerSize(0, dp(36), dp(36)); setLayerGravity(0, Gravity.CENTER)
    }

    private fun dayCell(value: String, selectable: Boolean) = TextView(this).apply {
        text = value; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(color(R.color.study_text))
        isClickable = selectable; isFocusable = selectable
        if (selectable) {
            installPressScale(0.94f)
            foreground = ContextCompat.getDrawable(this@StudyTaskEditorActivity, R.drawable.control_focus)
        }
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0; height = dp(44); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun setupWheel(wheel: NumberPicker, max: Int, selected: Int) {
        wheel.minValue = 0; wheel.maxValue = max
        wheel.setFormatter { "%02d".format(it) }
        wheel.value = selected; wheel.wrapSelectorWheel = true
        wheel.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) {
            wheel.textColor = color(R.color.study_text)
            wheel.textSize = 24 * resources.displayMetrics.scaledDensity
            wheel.selectionDividerHeight = 0
        }
        wheel.setOnValueChangedListener { view, _, _ ->
            time = LocalTime.of(binding.taskHour.value, binding.taskMinute.value)
            labels(); StudyFeedback.clockTick(view, wheel === binding.taskMinute)
        }
    }

    private fun courseChoices() {
        val names = listOf("") + courses.keys.sorted() + if (courseName.isNotBlank() && courseName !in courses) listOf(courseName) else emptyList()
        val keyword = binding.etCourseSearch.text.toString().trim()
        binding.courseChoices.removeAllViews()
        names.filter { it.isEmpty() || it.contains(keyword, true) }.forEach { name ->
            binding.courseChoices.addView(choice(name.ifBlank { "不关联课程" }, name == courseName) {
                courseName = name; labels(); courseChoices(); showPanel(""); hideKeyboard()
            })
        }
    }

    private fun reminderChoices() {
        binding.reminderPanel.removeAllViews()
        val options = LinkedHashMap(StudyTaskRules.reminders)
        if (reminder !in options) options[reminder] = "提前${reminder}分钟"
        options.forEach { (value, label) -> binding.reminderPanel.addView(choice(label, value == reminder) {
            reminder = value; labels(); reminderChoices(); showPanel("")
        }) }
    }

    private fun choice(label: String, selected: Boolean, action: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label; textSize = 14f; typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(color(if (selected) R.color.study_accent else R.color.study_text_secondary))
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        insetTop = 0; insetBottom = 0; setPadding(dp(16), dp(12), dp(16), dp(12))
        minHeight = dp(48); isAllCaps = false
        installPressScale(0.985f)
        rippleColor = ContextCompat.getColorStateList(this@StudyTaskEditorActivity, R.color.control_focus_overlay)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (selected) {
            icon = ContextCompat.getDrawable(this@StudyTaskEditorActivity, R.drawable.ic_check)
            iconGravity = MaterialButton.ICON_GRAVITY_END; iconSize = dp(18)
            iconTint = android.content.res.ColorStateList.valueOf(color(R.color.study_accent))
        }
        contentDescription = "$label${if (selected) "，已选择" else ""}"
        setOnClickListener { action() }
    }

    private fun stateKey() = listOf(binding.etTaskTitle.text.toString().trim(), binding.etTaskNote.text.toString().trim(), kind, courseName, reminder, date, time).joinToString("\u0000")

    private fun requestClose() {
        if (writing) return
        if (!loaded || stateKey() == initial) finish() else confirmAction(false)
    }

    private fun confirmAction(delete: Boolean) {
        if (writing) return
        hideKeyboard(); showPanel("")
        confirmingDelete = delete
        binding.tvConfirmation.text = if (delete) "删除后将取消这件事项的提醒。" else "还有未保存的修改，要放弃吗？"
        binding.btnConfirmAction.text = if (delete) "确认删除" else "放弃修改"
        binding.confirmationPanel.visibility = View.VISIBLE
        reveal(binding.confirmationPanel)
    }

    private fun save() {
        val current = semester ?: return
        if (writing || !loaded) return
        hideKeyboard()
        try {
            require(date != null && time != null) { "请选择明确的截止日期和时间。" }
            val now = System.currentTimeMillis()
            val task = StudyTask(id = original?.id ?: 0, semesterId = current.id,
                courseId = if (courseName == original?.courseName) original?.courseId else courses[courseName]?.singleOrNull()?.id,
                courseName = courseName, title = binding.etTaskTitle.text.toString().trim(), kind = kind,
                dueAt = StudyTaskRules.deadline(date.toString(), time!!.format(DateTimeFormatter.ofPattern("HH:mm"))),
                reminderMinutes = reminder, note = binding.etTaskNote.text.toString().trim(), completedAt = original?.completedAt,
                createdAt = original?.createdAt ?: now, updatedAt = now)
            StudyTaskRules.validate(task)
            persist { StudyTaskStore(this).save(task, original) }
        } catch (error: Exception) { showError(error) }
    }

    private fun delete() {
        val task = original ?: return
        persist { StudyTaskStore(this).delete(task) }
    }

    private fun persist(block: suspend () -> Unit) {
        if (writing) return
        writing = true
        setEnabled(binding.editorContent, false)
        binding.btnTaskSave.isEnabled = false
        binding.btnTaskCancel.isEnabled = false
        binding.btnConfirmAction.isEnabled = false
        lifecycleScope.launch {
            try {
                require(database.semesterDao().getCurrentSemesterSync()?.id == semester?.id) { "学期已切换，请重新打开。" }
                block(); finish()
            } catch (exception: Exception) { showError(exception) }
            finally {
                writing = false; setEnabled(binding.editorContent, true); calendar()
                binding.btnTaskSave.isEnabled = true; binding.btnTaskCancel.isEnabled = true; binding.btnConfirmAction.isEnabled = true
            }
        }
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) setEnabled(view.getChildAt(index), enabled)
    }

    private fun showError(exception: Exception) {
        binding.tvTaskError.text = exception.message ?: "保存失败，请重试。"
        binding.tvTaskError.visibility = View.VISIBLE
        reveal(binding.tvTaskError)
    }

    private fun reveal(view: View, end: View? = null) = binding.taskEditorScroll.post {
        val rect = Rect(); view.getDrawingRect(rect)
        binding.editorContent.offsetDescendantRectToMyCoords(view, rect)
        end?.let {
            val bottom = Rect(); it.getDrawingRect(bottom)
            binding.editorContent.offsetDescendantRectToMyCoords(it, bottom)
            rect.union(bottom)
        }
        binding.taskEditorScroll.requestChildRectangleOnScreen(binding.editorContent, rect, true)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.root.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (loaded) {
            outState.putLong("semesterId", semester!!.id)
            outState.putString("original", original?.let { Gson().toJson(it) })
            outState.putString("title", binding.etTaskTitle.text.toString()); outState.putString("note", binding.etTaskNote.text.toString())
            outState.putString("kind", kind); outState.putString("courseName", courseName); outState.putInt("reminder", reminder)
            outState.putString("date", date?.toString()); outState.putString("time", time?.toString()); outState.putString("initial", initial)
            outState.putString("month", month.toString()); outState.putString("panel", panel)
            outState.putInt("hour", binding.taskHour.value); outState.putInt("minute", binding.taskMinute.value)
        }
        super.onSaveInstanceState(outState)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
