package com.courseschedule.ui.assistant

import android.animation.AnimatorSet
import android.animation.Animator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.StudyTaskStore
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivityStudyTasksBinding
import com.courseschedule.databinding.ItemStudyTaskBinding
import com.courseschedule.domain.StudyTaskRules
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.ui.installPressScale
import com.courseschedule.ui.playNavigationMotion
import com.courseschedule.ui.returnToSchedule
import com.courseschedule.ui.ScheduleReturnSource
import com.courseschedule.ui.selectItemWithoutAnimation
import com.courseschedule.ui.stabilizeActiveIndicatorSize
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.utils.AlarmReceiver
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

class StudyTasksActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudyTasksBinding
    private val database by lazy { AppDatabase.getDatabase(this) }
    private var semester: Semester? = null
    private var source: LiveData<List<StudyTask>>? = null
    private var tasks = emptyList<StudyTask>()
    private var writing = false
    private var contentReady = false
    private var pageEntrance: AnimatorSet? = null
    private var rowCompletion: Animator? = null
    private var selectedFilter = R.id.btnTasksPending

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudyTasksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.study_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.study_background)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = resources.getBoolean(R.bool.window_light_system_bars)
            isAppearanceLightNavigationBars = resources.getBoolean(R.bool.window_light_system_bars)
        }
        val primaryPage = intent.getBooleanExtra(EXTRA_PRIMARY_PAGE, false)
        binding.btnStudyBack.visibility = if (primaryPage) View.GONE else View.VISIBLE
        binding.btnStudyBack.setOnClickListener { finish() }
        binding.btnStudyReminders.setOnClickListener { openReminderSettings() }
        binding.btnAddTask.installPressScale(0.98f)
        initNavigation()
        binding.btnAddTask.setOnClickListener { edit(null) }
        selectedFilter = savedInstanceState?.getInt("filter", R.id.btnTasksPending) ?: R.id.btnTasksPending
        listOf(binding.btnTasksToday, binding.btnTasksPending, binding.btnTasksUpcoming, binding.btnTasksCompleted).forEach { button ->
            button.installPressScale(0.985f)
            button.setOnClickListener {
                val next = if (button.id == R.id.btnTasksCompleted && selectedFilter == button.id) R.id.btnTasksPending else button.id
                val changed = next != selectedFilter
                selectedFilter = next; render()
                if (changed) StudyMotion.appear(if (binding.taskEmpty.visibility == View.VISIBLE) binding.taskEmpty else binding.taskRows)
            }
        }
        binding.etTaskSearch.doAfterTextChanged { render() }
        binding.tvTaskReminderStatus.setOnClickListener { openReminderSettings() }
        // Keep the previous tab visible until counts and rows are ready for the first frame.
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!contentReady) return false
                binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                if (savedInstanceState == null) {
                    pageEntrance = StudyMotion.enterPage(binding)
                    binding.bottomNavigation.findViewById<View>(R.id.nav_study)?.playNavigationMotion()
                }
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val current = database.semesterDao().getCurrentSemesterSync()
            if (current == null) {
                render()
                contentReady = true
                return@launch
            }
            if (semester != current || source == null) {
                source?.removeObservers(this@StudyTasksActivity)
                semester = current
                binding.tvTaskSemester.text = current.name
                source = database.studyTaskDao().observe(current.id).also { live -> live.observe(this@StudyTasksActivity) {
                    tasks = it; if (!writing) render()
                    contentReady = true
                    if (intent.hasExtra("study_task_id")) {
                        val id = intent.getLongExtra("study_task_id", 0)
                        intent.removeExtra("study_task_id")
                        tasks.find { row -> row.id == id }?.let { row -> edit(row) }
                    }
                } }
            } else render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("filter", selectedFilter)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        finishEntrance()
        rowCompletion?.end()
        rowCompletion = null
        super.onPause()
    }

    private fun finishEntrance() {
        pageEntrance?.cancel()
        pageEntrance = null
    }

    private fun render() {
        if (!::binding.isInitialized) return
        finishEntrance()
        val now = System.currentTimeMillis()
        val pending = tasks.filter { it.completedAt == null }
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = today.plusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayCount = pending.count { it.dueAt < tomorrow }
        val upcomingCount = pending.count { it.dueAt >= now && it.dueAt < end }
        val completedCount = tasks.count { it.completedAt != null }
        tile(binding.btnTasksToday, todayCount, getString(R.string.study_today))
        tile(binding.btnTasksUpcoming, upcomingCount, getString(R.string.study_upcoming))
        tile(binding.btnTasksPending, pending.size, getString(R.string.study_all))
        binding.btnTasksCompleted.text = getString(R.string.study_completed_count, completedCount)
        listOf(binding.btnTasksToday, binding.btnTasksPending, binding.btnTasksUpcoming, binding.btnTasksCompleted).forEach { it.isChecked = it.id == selectedFilter }
        styleTile(binding.btnTasksToday, R.color.study_today, R.color.study_today_surface, R.color.study_today_selected)
        styleTile(binding.btnTasksUpcoming, R.color.study_upcoming, R.color.study_upcoming_surface, R.color.study_upcoming_selected)
        styleTile(binding.btnTasksPending, R.color.reference_blue_accent, R.color.study_all_surface, R.color.study_all_selected)
        binding.tvTaskListTitle.text = when (selectedFilter) {
            R.id.btnTasksToday -> "今天与逾期"
            R.id.btnTasksUpcoming -> "未来7天"
            R.id.btnTasksCompleted -> "已完成事项"
            else -> "全部待办"
        }
        val reminders = SchedulePreferences(this).reminderEnabled
        binding.tvTaskReminderStatus.text = when {
            !reminders -> "总提醒已关闭 · 点此打开提醒设置"
            !AlarmReceiver.notificationsAvailable(this) -> "通知尚未开启 · 点此设置"
            !ReminderManager(this).canScheduleExactAlarms() -> "提醒可能延迟 · 点此设置精确提醒"
            else -> ""
        }
        binding.tvTaskReminderStatus.visibility = if (binding.tvTaskReminderStatus.text.isBlank()) View.GONE else View.VISIBLE
        val keyword = binding.etTaskSearch.text?.toString().orEmpty().trim()
        val shown = tasks.filter { task ->
            (keyword.isEmpty() || task.title.contains(keyword, true) || task.courseName.contains(keyword, true)) &&
                when (selectedFilter) {
                    R.id.btnTasksCompleted -> task.completedAt != null
                    R.id.btnTasksToday -> task.completedAt == null && task.dueAt < tomorrow
                    R.id.btnTasksUpcoming -> task.completedAt == null && task.dueAt >= now && task.dueAt < end
                    else -> task.completedAt == null
                }
        }.let { rows -> if (selectedFilter == R.id.btnTasksCompleted) rows.sortedByDescending { it.completedAt } else rows }
        binding.taskEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.tvTaskEmptyTitle.text = if (keyword.isNotBlank()) "没有匹配的待办" else when (selectedFilter) {
            R.id.btnTasksCompleted -> "还没有已完成事项"
            R.id.btnTasksToday -> "今天暂时没有截止事项"
            R.id.btnTasksUpcoming -> "未来7天暂无截止事项"
            else -> getString(R.string.study_empty_title)
        }
        binding.tvTaskEmpty.text = if (keyword.isNotBlank()) "换个关键词试试，也可以搜索课程。" else when (selectedFilter) {
            R.id.btnTasksCompleted -> "完成的事项会留在这里，随时可以重新打开。"
            R.id.btnTasksToday -> "给自己留一点从容。"
            R.id.btnTasksUpcoming -> "逾期事项可在「全部」中查看。"
            else -> getString(R.string.study_empty)
        }
        binding.taskRows.removeAllViews()
        fun section(task: StudyTask): String {
            if (task.completedAt != null) return "已完成"
            val day = Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
            return when {
                task.dueAt < now -> "已逾期"
                day == today -> "今天"
                day == today.plusDays(1) -> "明天"
                day < today.plusDays(7) -> "未来7天"
                else -> "稍后"
            }
        }
        val counts = shown.take(200).groupingBy(::section).eachCount()
        var previousSection = ""
        var group: LinearLayout? = null
        var lastRow: ItemStudyTaskBinding? = null
        shown.take(200).forEach { task ->
            val label = section(task)
            if (label != previousSection) {
                binding.taskRows.addView(TextView(this).apply {
                    text = "$label  ·  ${counts[label]}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@StudyTasksActivity, R.color.study_text_secondary))
                    setPadding(dp(4), dp(16), 0, dp(8))
                })
                lastRow?.taskDivider?.visibility = View.GONE
                group = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ContextCompat.getDrawable(this@StudyTasksActivity, R.drawable.bg_study_list_group)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }.also { binding.taskRows.addView(it) }
                previousSection = label
            }
            val row = ItemStudyTaskBinding.inflate(layoutInflater, group, false)
            row.root.tag = task.id
            row.tvTaskKind.text = StudyTaskRules.kinds[task.kind]
            row.tvTaskTitle.text = task.title
            val deadline = Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault())
            val due = deadline.format(DateTimeFormatter.ofPattern(when {
                deadline.toLocalDate() == today -> "HH:mm"
                deadline.year != today.year -> "yyyy/M/d HH:mm"
                else -> "M月d日 HH:mm"
            }))
            row.tvTaskDue.text = listOfNotNull(task.courseName.takeIf { it.isNotBlank() }, if (task.kind == "reminder") "$due 提醒" else "$due 截止").joinToString(" · ")
            row.tvTaskDue.setTextColor(ContextCompat.getColor(this, if (task.completedAt == null && task.dueAt < now) R.color.study_today else R.color.study_text_secondary))
            row.root.contentDescription = StudyTaskRules.describe(task)
            row.tvTaskTitle.alpha = if (task.completedAt == null) 1f else 0.55f
            val (kindSurface, kindInk) = when (task.kind) {
                "exam" -> R.color.reference_warm_surface to R.color.reference_warm_accent
                "report" -> R.color.reference_teal_surface to R.color.reference_teal_accent
                "reminder" -> R.color.reference_rose_surface to R.color.reference_rose_accent
                else -> R.color.reference_blue_surface to R.color.reference_blue_accent
            }
            row.tvTaskKind.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, kindSurface))
            row.tvTaskKind.setTextColor(ContextCompat.getColor(this, kindInk))
            row.tvTaskTitle.paintFlags = if (task.completedAt == null) row.tvTaskTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                else row.tvTaskTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            row.checkTaskDone.isChecked = task.completedAt != null
            row.checkTaskDone.contentDescription = "${if (task.completedAt == null) "完成" else "重新打开"} ${task.title}"
            row.checkTaskDone.isEnabled = !writing
            row.checkTaskDone.setOnCheckedChangeListener { _, checked ->
                if (writing) return@setOnCheckedChangeListener
                write(row.root, checked) {
                    StudyTaskStore(this).save(task.copy(completedAt = if (checked) System.currentTimeMillis() else null, updatedAt = System.currentTimeMillis()), task)
                }
            }
            row.root.setOnClickListener { if (!writing) edit(task) }
            row.root.installPressScale(0.992f)
            group!!.addView(row.root)
            lastRow = row
        }
        lastRow?.taskDivider?.visibility = View.GONE
        if (shown.size > 200) binding.taskRows.addView(TextView(this).apply { text = "显示前200项，请搜索课程或标题缩小范围。" })
        binding.btnAddTask.isEnabled = !writing && semester != null
        listOf(binding.btnTasksToday, binding.btnTasksUpcoming, binding.btnTasksPending, binding.btnTasksCompleted).forEach { it.isEnabled = !writing }
        binding.etTaskSearch.isEnabled = !writing
    }

    private fun write(row: View, completed: Boolean, block: suspend () -> Unit) {
        writing = true
        binding.btnAddTask.isEnabled = false
        listOf(binding.btnTasksToday, binding.btnTasksUpcoming, binding.btnTasksPending, binding.btnTasksCompleted).forEach { it.isEnabled = false }
        binding.etTaskSearch.isEnabled = false
        for (index in 0 until binding.taskRows.childCount) {
            val group = binding.taskRows.getChildAt(index) as? android.view.ViewGroup ?: continue
            for (child in 0 until group.childCount) group.getChildAt(child).findViewById<View>(R.id.checkTaskDone)?.isEnabled = false
        }
        lifecycleScope.launch {
            try {
                block()
                if (completed) StudyFeedback.completed(binding.root)
                rowCompletion = StudyMotion.finishRow(row, completed) { rowCompletion = null; writing = false; render() }
            } catch (error: Exception) {
                writing = false; render()
                Toast.makeText(this@StudyTasksActivity, error.message ?: "保存失败，请重试。", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun edit(original: StudyTask?) {
        if (!writing && semester != null) startActivity(Intent(this, StudyTaskEditorActivity::class.java).apply {
            original?.let { putExtra("study_task_id", it.id) }
        })
    }

    private fun tile(button: com.google.android.material.button.MaterialButton, count: Int, label: String) {
        button.text = "$label  $count"
        button.contentDescription = "$label，$count 项"
    }

    private fun styleTile(button: com.google.android.material.button.MaterialButton, text: Int, surface: Int, selected: Int) {
        button.setTextColor(ContextCompat.getColor(this, if (button.isChecked) R.color.study_on_accent else text))
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
            if (button.isChecked) selected else surface))
        button.strokeWidth = 0
    }

    private fun openReminderSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_FOCUS_REMINDERS, true))
        overridePendingTransition(0, 0)
        finish(); overridePendingTransition(0, 0)
    }

    private fun initNavigation() {
        binding.bottomNavigation.stabilizeActiveIndicatorSize()
        binding.bottomNavigation.selectItemWithoutAnimation(R.id.nav_study)
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_study) return@setOnItemSelectedListener true
            if (item.itemId == R.id.nav_home) {
                returnToSchedule(ScheduleReturnSource.TODO)
                return@setOnItemSelectedListener true
            }
            val destination = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_import -> ImportActivity::class.java
                R.id.nav_settings -> SettingsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            startActivity(Intent(this, destination).apply {
                if (item.itemId == R.id.nav_home) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            overridePendingTransition(0, 0)
            finish(); overridePendingTransition(0, 0)
            true
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            binding.bottomNavigation.findViewById<View>(item.itemId)?.playNavigationMotion()
            binding.taskScroll.smoothScrollTo(0, 0)
        }
    }

    companion object { const val EXTRA_PRIMARY_PAGE = "study_primary_page" }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
