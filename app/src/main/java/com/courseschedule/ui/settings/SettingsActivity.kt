package com.courseschedule.ui.settings

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.data.backup.ScheduleBackup
import com.courseschedule.data.backup.SemesterSnapshot
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivitySettingsBinding
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.domain.SemesterPhase
import com.courseschedule.ui.installPressScale
import com.courseschedule.ui.playNavigationMotion
import com.courseschedule.ui.selectItemWithoutAnimation
import com.courseschedule.ui.stabilizeActiveIndicatorSize
import com.courseschedule.ui.importdata.ImportActivity
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.viewmodel.CourseViewModel
import com.courseschedule.viewmodel.SemesterViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var semesterViewModel: SemesterViewModel
    private lateinit var courseViewModel: CourseViewModel
    private lateinit var preferences: SchedulePreferences
    private var semesters: List<Semester> = emptyList()
    private var suppressBottomNavigationMotion = false
    private val motionInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val sectionHeightValues = intArrayOf(56, 64, 72, 84)
    private val reminderValues = intArrayOf(-1, 5, 10, 15, 30, 60)

    private val createExportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(::exportToUri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        semesterViewModel = ViewModelProvider(this)[SemesterViewModel::class.java]
        courseViewModel = ViewModelProvider(this)[CourseViewModel::class.java]
        preferences = SchedulePreferences(this)

        initSettingsControls()
        initActions()
        initBottomNavigation()
        observeData()
        animateSettingsEntrance()
    }

    private fun initSettingsControls() {
        val systemIsDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        binding.switchDarkMode.isChecked = preferences.darkModeOverride ?: systemIsDark
        binding.switchShowWeekend.isChecked = preferences.showWeekend
        binding.switchShowInactiveCourses.isChecked = preferences.showInactiveCourses
        binding.switchShowTime.isChecked = preferences.showTime
        binding.switchReminder.isChecked = preferences.reminderEnabled

        val heightLabels = sectionHeightValues.map { getString(R.string.height_dp_format, it) }
        binding.spinnerSectionHeight.adapter = simpleSpinnerAdapter(heightLabels)
        binding.spinnerSectionHeight.setSelection(
            sectionHeightValues.indexOf(preferences.sectionHeightDp).takeIf { it >= 0 } ?: 1
        )

        val reminderLabels = resources.getStringArray(R.array.reminder_options).toList()
        binding.spinnerDefaultReminder.adapter = simpleSpinnerAdapter(reminderLabels)
        binding.spinnerDefaultReminder.setSelection(
            reminderValues.indexOf(preferences.defaultReminderMinutes).takeIf { it >= 0 } ?: 3
        )
        updateReminderControlState(preferences.reminderEnabled, animate = false)
        updateSectionTimesSummary()

        binding.rowDarkMode.setOnClickListener {
            binding.switchDarkMode.toggle()
        }
        binding.rowShowWeekend.setOnClickListener {
            binding.switchShowWeekend.toggle()
        }
        binding.rowShowInactiveCourses.setOnClickListener {
            binding.switchShowInactiveCourses.toggle()
        }
        binding.rowShowTime.setOnClickListener {
            binding.switchShowTime.toggle()
        }
        binding.rowReminder.setOnClickListener {
            binding.switchReminder.toggle()
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            preferences.darkModeOverride = checked
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        binding.switchShowWeekend.setOnCheckedChangeListener { _, checked ->
            preferences.showWeekend = checked
        }
        binding.switchShowInactiveCourses.setOnCheckedChangeListener { _, checked ->
            preferences.showInactiveCourses = checked
        }
        binding.switchShowTime.setOnCheckedChangeListener { _, checked ->
            preferences.showTime = checked
        }
        binding.switchReminder.setOnCheckedChangeListener { _, checked ->
            preferences.reminderEnabled = checked
            updateReminderControlState(checked, animate = true)
            updateReminderScheduling(checked)
        }
        binding.spinnerSectionHeight.onItemSelectedListener = onItemSelected { position ->
            preferences.sectionHeightDp = sectionHeightValues[position]
        }
        binding.spinnerDefaultReminder.onItemSelectedListener = onItemSelected { position ->
            preferences.defaultReminderMinutes = reminderValues[position]
        }
    }

    private fun simpleSpinnerAdapter(values: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onItemSelected(action: (Int) -> Unit): AdapterView.OnItemSelectedListener {
        return object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                action(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updateReminderControlState(enabled: Boolean, animate: Boolean) {
        binding.spinnerDefaultReminder.isEnabled = enabled
        binding.cardSectionTimes.isEnabled = enabled
        val spinnerAlpha = if (enabled) 1f else 0.45f
        val sectionTimesAlpha = if (enabled) 1f else 0.55f
        if (animate) {
            binding.spinnerDefaultReminder.animate()
                .alpha(spinnerAlpha)
                .setDuration(220L)
                .setInterpolator(motionInterpolator)
                .start()
            binding.cardSectionTimes.animate()
                .alpha(sectionTimesAlpha)
                .setDuration(220L)
                .setInterpolator(motionInterpolator)
                .start()
        } else {
            binding.spinnerDefaultReminder.alpha = spinnerAlpha
            binding.cardSectionTimes.alpha = sectionTimesAlpha
        }
    }

    private fun updateReminderScheduling(enabled: Boolean) {
        lifecycleScope.launch {
            val semester = courseViewModel.currentSemester.value ?: return@launch
            val courses = courseViewModel.getCurrentSemesterCourses()
            val manager = ReminderManager(this@SettingsActivity)
            manager.cancelAllReminders(courses)
            if (enabled) manager.rescheduleReminders(courses, semester)
        }
    }

    private fun initActions() {
        binding.cardOpenSource.setOnClickListener { openProjectRepository() }
        binding.cardOpenSource.setOnLongClickListener {
            copyProjectAddress()
            true
        }
        binding.cardSemester.setOnClickListener { showSemesterManager() }
        binding.cardSectionTimes.setOnClickListener { showSectionTimesDialog() }
        binding.cardExport.setOnClickListener { exportData() }
        binding.cardBackup.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
            overridePendingTransition(0, 0)
        }
        binding.cardAbout.setOnClickListener { showAboutDialog() }

        listOf(
            binding.cardOpenSource,
            binding.rowDarkMode,
            binding.cardSemester,
            binding.rowShowWeekend,
            binding.rowShowInactiveCourses,
            binding.rowShowTime,
            binding.rowReminder,
            binding.cardSectionTimes,
            binding.cardExport,
            binding.cardBackup,
            binding.cardAbout
        ).forEach { it.installPressScale() }
    }

    private fun initBottomNavigation() {
        binding.bottomNavigation.stabilizeActiveIndicatorSize()
        binding.bottomNavigation.selectItemWithoutAnimation(R.id.nav_settings)
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (suppressBottomNavigationMotion) return@setOnItemSelectedListener true
            val itemView = binding.bottomNavigation.findViewById<View>(item.itemId)
            when (item.itemId) {
                R.id.nav_home -> {
                    finish()
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_import -> {
                    startActivity(Intent(this, ImportActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_settings -> {
                    itemView.playNavigationMotion()
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            binding.bottomNavigation.findViewById<View>(item.itemId)?.playNavigationMotion()
        }
        binding.bottomNavigation.post {
            binding.bottomNavigation.findViewById<View>(R.id.nav_settings)?.playNavigationMotion()
        }
    }

    private fun animateSettingsEntrance() {
        val container = binding.settingsContent
        val children = List(container.childCount, container::getChildAt)
        children.forEach { child ->
            child.alpha = 0.18f
            child.translationX = dp(32).toFloat()
        }
        container.doOnPreDraw {
            children.forEachIndexed { index, child ->
                child.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setStartDelay(index * 56L)
                    .setDuration(620L)
                    .setInterpolator(motionInterpolator)
                    .withLayer()
                    .start()
            }
        }
    }

    private fun observeData() {
        semesterViewModel.currentSemester.observe(this) { semester ->
            binding.tvCurrentSemester.text = semester?.name ?: getString(R.string.no_current_semester)
            binding.tvSemesterStatus.text = semester?.let { semesterStatusText(it) }.orEmpty()
        }
        semesterViewModel.allSemesters.observe(this) { semesters = it }
    }

    private fun semesterStatusText(semester: Semester): String {
        val status = ScheduleRules.semesterWeekStatus(semester)
        return when (status.phase) {
            SemesterPhase.ACTIVE -> getString(
                R.string.semester_active_status,
                status.week,
                semester.totalWeeks
            )
            SemesterPhase.BEFORE -> getString(R.string.semester_not_started)
            SemesterPhase.AFTER -> getString(R.string.semester_finished)
        }
    }

    private fun showSemesterManager() {
        val currentId = semesterViewModel.currentSemester.value?.id
        val items = listOf(getString(R.string.create_new_semester)) + semesters.map {
            if (it.id == currentId) getString(R.string.current_semester_item, it.name) else it.name
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.semester_management)
            .setItems(items.toTypedArray()) { _, index ->
                if (index == 0) showSemesterEditor(null) else showSemesterActions(semesters[index - 1])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSemesterActions(semester: Semester) {
        val isCurrent = semester.id == semesterViewModel.currentSemester.value?.id
        val actions = buildList {
            if (!isCurrent) add(getString(R.string.set_as_current))
            add(getString(R.string.edit_semester))
            if (!isCurrent) add(getString(R.string.delete_semester))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(semester.name)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    getString(R.string.set_as_current) -> switchSemester(semester)
                    getString(R.string.edit_semester) -> showSemesterEditor(semester)
                    getString(R.string.delete_semester) -> confirmDeleteSemester(semester)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun switchSemester(semester: Semester) {
        lifecycleScope.launch {
            val oldSemester = semesterViewModel.currentSemester.value
            val manager = ReminderManager(this@SettingsActivity)
            oldSemester?.let {
                manager.cancelAllReminders(courseViewModel.getSemesterCourses(it.id))
            }
            semesterViewModel.switchSemesterNow(semester.id)
            if (preferences.reminderEnabled) {
                manager.rescheduleReminders(courseViewModel.getSemesterCourses(semester.id), semester)
            }
            Toast.makeText(this@SettingsActivity, R.string.semester_switched, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSemesterEditor(existing: Semester?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_semester, null)
        val nameLayout = dialogView.findViewById<TextInputLayout>(R.id.tilSemesterName)
        val dateLayout = dialogView.findViewById<TextInputLayout>(R.id.tilSemesterStartDate)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.etSemesterName)
        val dateInput = dialogView.findViewById<TextInputEditText>(R.id.etSemesterStartDate)
        val weeksDropdown = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdownTotalWeeks)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        var selectedStartDate = existing?.startDate ?: mondayStart(System.currentTimeMillis())

        nameInput.setText(existing?.name.orEmpty())
        dateInput.setText(dateFormat.format(Date(selectedStartDate)))
        val weekOptions = (12..30).map { getString(R.string.week_count_format, it) }
        weeksDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, weekOptions))
        weeksDropdown.setText(
            getString(R.string.week_count_format, existing?.totalWeeks?.coerceIn(12, 30) ?: 20),
            false
        )
        val showDatePicker = View.OnClickListener {
            val calendar = Calendar.getInstance().apply { timeInMillis = selectedStartDate }
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    calendar.set(year, month, day, 0, 0, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    selectedStartDate = mondayStart(calendar.timeInMillis)
                    dateInput.setText(dateFormat.format(Date(selectedStartDate)))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        dateInput.setOnClickListener(showDatePicker)
        dateLayout.setEndIconOnClickListener(showDatePicker)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_semester else R.string.edit_semester)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    nameLayout.error = getString(R.string.input_semester_name)
                    return@setOnClickListener
                }
                val totalWeeks = Regex("\\d+").find(weeksDropdown.text.toString())
                    ?.value?.toIntOrNull() ?: 20
                dialog.dismiss()
                val displayedStartDate = runCatching {
                    dateFormat.parse(dateInput.text?.toString().orEmpty())?.time
                }.getOrNull() ?: selectedStartDate
                saveSemester(existing, name, mondayStart(displayedStartDate), totalWeeks)
            }
        }
        dialog.show()
    }

    private fun saveSemester(existing: Semester?, name: String, startDate: Long, totalWeeks: Int) {
        lifecycleScope.launch {
            val manager = ReminderManager(this@SettingsActivity)
            if (existing == null) {
                semesterViewModel.currentSemester.value?.let { current ->
                    manager.cancelAllReminders(courseViewModel.getSemesterCourses(current.id))
                }
                val id = semesterViewModel.insertSemesterNow(
                    Semester(name = name, startDate = startDate, totalWeeks = totalWeeks)
                )
                semesterViewModel.switchSemesterNow(id)
            } else {
                val updated = existing.copy(name = name, startDate = startDate, totalWeeks = totalWeeks)
                val courses = courseViewModel.getSemesterCourses(existing.id)
                manager.cancelAllReminders(courses)
                semesterViewModel.updateSemesterNow(updated)
                semesters = semesters.map { if (it.id == updated.id) updated else it }
                if (existing.isCurrent && preferences.reminderEnabled) {
                    manager.rescheduleReminders(courses, updated)
                }
            }
            Toast.makeText(this@SettingsActivity, R.string.semester_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSemester(semester: Semester) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_semester)
            .setMessage(R.string.delete_semester_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val courses = courseViewModel.getSemesterCourses(semester.id)
                    ReminderManager(this@SettingsActivity).cancelAllReminders(courses)
                    courseViewModel.deleteSemesterCourses(semester.id)
                    semesterViewModel.deleteSemesterNow(semester)
                    Toast.makeText(this@SettingsActivity, R.string.semester_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSectionTimesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_section_times, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.sectionTimesContainer)
        fun addTimeInput(
            row: LinearLayout,
            hintText: String,
            value: String,
            marginStart: Int = 0
        ): TextInputEditText {
            val layout = TextInputLayout(this).apply {
                hint = hintText
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { this.marginStart = dp(marginStart) }
            }
            val input = TextInputEditText(layout.context).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_DATETIME
                maxLines = 1
            }
            layout.addView(input)
            row.addView(layout)
            return input
        }

        val inputs = preferences.sectionTimes.zip(preferences.sectionEndTimes)
            .mapIndexed { index, (start, end) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { if (index > 0) topMargin = dp(8) }
                }
                container.addView(row)
                addTimeInput(
                    row,
                    getString(R.string.section_start_time, index + 1),
                    start
                ) to addTimeInput(
                    row,
                    getString(R.string.section_end_time, index + 1),
                    end,
                    marginStart = 8
                )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.section_time_settings)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.restore_defaults, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                inputs.forEachIndexed { index, (startInput, endInput) ->
                    startInput.setText(SchedulePreferences.DEFAULT_SECTION_TIMES[index])
                    endInput.setText(SchedulePreferences.DEFAULT_SECTION_END_TIMES[index])
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val startTimes = inputs.map { it.first.text?.toString()?.trim().orEmpty() }
                val endTimes = inputs.map { it.second.text?.toString()?.trim().orEmpty() }
                if (!SchedulePreferences.areValidSectionTimes(startTimes, endTimes)) {
                    Toast.makeText(this, R.string.invalid_section_times, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                preferences.setSectionTimes(startTimes, endTimes)
                updateSectionTimesSummary()
                updateReminderScheduling(preferences.reminderEnabled)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateSectionTimesSummary() {
        val startTimes = preferences.sectionTimes
        val endTimes = preferences.sectionEndTimes
        binding.tvSectionTimesSummary.text = getString(
            R.string.section_times_summary,
            startTimes.first(),
            endTimes.first(),
            startTimes.last(),
            endTimes.last()
        )
    }

    private fun exportData() {
        val fileDate = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        createExportDocument.launch("课程表完整备份_$fileDate.json")
    }

    private fun exportToUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val semester = semesterViewModel.currentSemester.value
                    ?: error(getString(R.string.no_current_semester))
                val backup = ScheduleBackup(
                    semester = SemesterSnapshot.from(semester),
                    settings = preferences.snapshot(),
                    courses = courseViewModel.getCurrentSemesterCourses()
                )
                val json = GsonBuilder().setPrettyPrinting().create().toJson(backup)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: error("无法创建文件")
                }
                Toast.makeText(this@SettingsActivity, R.string.export_success, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAboutDialog() {
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_course_schedule)
            .setMessage(getString(R.string.about_message, versionName))
            .setNeutralButton(R.string.open_source_view_project) { _, _ -> openProjectRepository() }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openProjectRepository() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_repository_url)))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // A device without a browser can still share/open the public address elsewhere.
            copyProjectAddress()
        }
    }

    private fun copyProjectAddress() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.app_name), getString(R.string.project_repository_url))
        )
        Toast.makeText(this, R.string.open_source_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun mondayStart(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val offset = when (get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> -6
                else -> Calendar.MONDAY - get(Calendar.DAY_OF_WEEK)
            }
            add(Calendar.DAY_OF_MONTH, offset)
        }.timeInMillis
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (binding.bottomNavigation.selectedItemId != R.id.nav_settings) {
            suppressBottomNavigationMotion = true
            binding.bottomNavigation.selectItemWithoutAnimation(R.id.nav_settings)
            suppressBottomNavigationMotion = false
        }
    }
}
