package com.courseschedule.ui.importdata

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.addCallback
import com.courseschedule.ui.returnToSchedule
import com.courseschedule.ui.ScheduleReturnSource
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.courseschedule.ui.assistant.StudyTasksActivity
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivityImportBinding
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.assistant.CourseAssistantActivity
import com.courseschedule.ui.installPressScale
import com.courseschedule.ui.playNavigationMotion
import com.courseschedule.ui.selectItemWithoutAnimation
import com.courseschedule.ui.stabilizeActiveIndicatorSize
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import com.courseschedule.viewmodel.CourseViewModel
import com.courseschedule.viewmodel.SemesterViewModel
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.StudyTask

class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var courseViewModel: CourseViewModel
    private lateinit var semesterViewModel: SemesterViewModel
    private val motionInterpolator = PathInterpolator(0.2f, 0.85f, 0.25f, 1f)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFromFile)
    }

    private val schoolImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val school = result.data?.let(AcademicWebImportActivity::schoolFromIntent)
            ?: return@registerForActivityResult
        val json = result.data?.getStringExtra(AcademicWebImportActivity.EXTRA_SCHEDULE_JSON)
            ?.takeIf(String::isNotBlank)
            ?: return@registerForActivityResult
        val aiResult = result.data?.getBooleanExtra(
            AcademicWebImportActivity.EXTRA_AI_RESULT,
            false
        ) == true
        val aiSourceLabel = getString(R.string.academic_ai_source)
        importParsed { totalWeeks ->
            if (aiResult) {
                ImportParser(totalWeeks).parseJson(json).copy(sourceLabel = aiSourceLabel)
            } else {
                AcademicSchools.parse(school, json, totalWeeks)
            }
        }
    }

    private val schoolPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val entry = AcademicSchoolDirectory.find(
            this,
            data.getStringExtra(SchoolPickerActivity.EXTRA_DIRECTORY_ID)
        ) ?: return@registerForActivityResult
        val totalWeeks = courseViewModel.currentSemester.value?.totalWeeks ?: run {
            Toast.makeText(this, R.string.semester_loading, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val school = AcademicWebImportActivity.schoolFromIntent(data) ?: entry.toAcademicSchool() ?: run {
            Toast.makeText(this, R.string.academic_directory_entry_invalid, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        launchAcademicImport(school, totalWeeks, entry.importHint)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { returnToSchedule(ScheduleReturnSource.IMPORT) }
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        courseViewModel = ViewModelProvider(this)[CourseViewModel::class.java]
        semesterViewModel = ViewModelProvider(this)[SemesterViewModel::class.java]

        binding.cardImportSchool.setOnClickListener {
            val totalWeeks = courseViewModel.currentSemester.value?.totalWeeks
            if (totalWeeks == null) {
                Toast.makeText(this, R.string.semester_loading, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            schoolPickerLauncher.launch(Intent(this, SchoolPickerActivity::class.java))
        }
        binding.cardImportJson.setOnClickListener { openFilePicker() }
        binding.cardImportText.setOnClickListener { showTextImportDialog() }
        binding.cardImportAssistant.setOnClickListener {
            startActivity(Intent(this, CourseAssistantActivity::class.java)
                .putExtra(CourseAssistantActivity.EXTRA_DISPLAYED_WEEK, courseViewModel.currentWeek.value ?: 1))
        }
        binding.cardImportSchool.installPressScale()
        binding.cardImportJson.installPressScale()
        binding.cardImportText.installPressScale()
        binding.cardImportAssistant.installPressScale()
        initBottomNavigation()
        animateImportEntrance()
        courseViewModel.currentSemester.observe(this) { semester ->
            binding.tvImportTarget.text = getString(
                R.string.import_target_format,
                semester?.name ?: getString(R.string.current_semester)
            )
        }
    }

    private fun initBottomNavigation() {
        binding.bottomNavigation.stabilizeActiveIndicatorSize()
        binding.bottomNavigation.selectItemWithoutAnimation(R.id.nav_import)
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val itemView = binding.bottomNavigation.findViewById<View>(item.itemId)
            when (item.itemId) {
                R.id.nav_home -> {
                    returnToSchedule(ScheduleReturnSource.IMPORT)
                    true
                }
                R.id.nav_study -> {
                    startActivity(Intent(this, StudyTasksActivity::class.java).putExtra(StudyTasksActivity.EXTRA_PRIMARY_PAGE, true))
                    overridePendingTransition(0, 0)
                    finish()
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_import -> {
                    itemView.playNavigationMotion()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            binding.bottomNavigation.findViewById<View>(item.itemId)?.playNavigationMotion()
        }
        binding.bottomNavigation.post {
            binding.bottomNavigation.findViewById<View>(R.id.nav_import)?.playNavigationMotion()
        }
    }

    private fun animateImportEntrance() {
        val container = binding.importContent
        val children = List(container.childCount, container::getChildAt)
        children.forEach { child ->
            child.alpha = 0.16f
            child.translationY = dp(30f)
            if (child === binding.cardImportSchool) {
                child.scaleX = 0.9f
                child.scaleY = 0.9f
            }
        }
        binding.schoolIconContainer.apply {
            scaleX = 0.2f
            scaleY = 0.2f
            rotation = -24f
        }
        container.doOnPreDraw {
            children.forEachIndexed { index, child ->
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(index * 50L)
                    .setDuration(540L)
                    .setInterpolator(motionInterpolator)
                    .withLayer()
                    .start()
            }
            binding.schoolIconContainer.animate()
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setStartDelay(150L)
                .setDuration(720L)
                .setInterpolator(OvershootInterpolator(1.55f))
                .withLayer()
                .start()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun launchAcademicImport(school: AcademicSchool, totalWeeks: Int, hint: String? = null) {
        schoolImportLauncher.launch(AcademicWebImportActivity.schoolIntent(this, school)
            .putExtra(AcademicWebImportActivity.EXTRA_TOTAL_WEEKS, totalWeeks)
            .putExtra(AcademicWebImportActivity.EXTRA_GENERIC_HINT, hint.orEmpty()))
    }

    private fun openFilePicker() {
        // Some Android file providers report CSV/HTML as application/octet-stream.
        // The parser validates the extension and content after selection.
        filePicker.launch(arrayOf("*/*"))
    }

    private fun showTextImportDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_text_import, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.etImportText)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.takeIf(String::isNotBlank)?.let(editText::setText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_from_text)
            .setView(dialogView)
            .setPositiveButton(R.string.preview_import, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = editText.text?.toString().orEmpty()
                if (text.isBlank()) {
                    editText.error = getString(R.string.input_schedule_text)
                } else {
                    dialog.dismiss()
                    importParsed { totalWeeks -> TextImporter(totalWeeks).parse(text) }
                }
            }
        }
        dialog.show()
    }

    private fun importFromFile(uri: Uri) {
        importParsed { totalWeeks -> FileImporter(this, totalWeeks).importFromUri(uri) }
    }

    private fun importParsed(parser: suspend (Int) -> ParsedImport) {
        val currentSemester = courseViewModel.currentSemester.value
        if (currentSemester == null) {
            Toast.makeText(this, R.string.semester_loading, Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { parser(currentSemester.totalWeeks) }.let { source ->
                    val minutes = SchedulePreferences(this@ImportActivity).defaultReminderMinutes
                    source.copy(courses = source.courses.map { source.withDefaultReminder(it, minutes) })
                }
                val existing = courseViewModel.getCurrentSemesterCourses()
                val analysisWeeks = parsed.semester?.totalWeeks ?: currentSemester.totalWeeks
                val analysis = ImportAnalyzer.analyze(
                    parsed.courses,
                    existing,
                    currentSemester.id,
                    analysisWeeks
                )
                setLoading(false)
                if (
                    analysis.accepted.isEmpty() &&
                    analysis.conflicts.isEmpty() &&
                    analysis.duplicates.isEmpty() && parsed.studyTasks.isNullOrEmpty()
                ) {
                    showNothingToImport(analysis)
                } else {
                    showImportPreview(parsed, analysis, currentSemester, existing)
                }
            } catch (error: Exception) {
                setLoading(false)
                MaterialAlertDialogBuilder(this@ImportActivity)
                    .setTitle(R.string.import_failed)
                    .setMessage(error.message ?: getString(R.string.import_failed_detail))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showNothingToImport(analysis: ImportAnalysis) {
        val message = getString(
            R.string.import_nothing_detail,
            analysis.duplicates.size,
            analysis.invalid.size
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.no_courses_parsed)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showImportPreview(
        parsed: ParsedImport,
        analysis: ImportAnalysis,
        currentSemester: Semester,
        existing: List<Course>
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_import_preview, null)
        val summary = view.findViewById<TextView>(R.id.tvImportSummary)
        val courseList = view.findViewById<TextView>(R.id.tvImportCourses)
        val includeConflicts = view.findViewById<MaterialCheckBox>(R.id.checkIncludeConflicts)
        val restoreMetadata = view.findViewById<MaterialCheckBox>(R.id.checkRestoreMetadata)
        val replaceExisting = view.findViewById<MaterialCheckBox>(R.id.checkReplaceExisting)
        val restoreTasks = view.findViewById<MaterialCheckBox>(R.id.checkRestoreStudyTasks)
        restoreTasks.visibility = if (parsed.studyTasks.isNullOrEmpty()) View.GONE else View.VISIBLE
        restoreTasks.isChecked = !parsed.studyTasks.isNullOrEmpty()

        val importSummary = getString(
            R.string.import_preview_summary,
            analysis.accepted.size,
            analysis.conflicts.size,
            analysis.duplicates.size,
            analysis.invalid.size
        )
        val metadataSummary = getString(
            R.string.import_metadata_summary,
            parsed.courses.count { it.classroom.isNotBlank() },
            parsed.courses.size,
            parsed.courses.count { it.teacher.isNotBlank() }
        )
        summary.text = "$importSummary\n$metadataSummary" + if (!parsed.studyTasks.isNullOrEmpty()) "\n备份含${parsed.studyTasks.size}项学习事项，已完成状态和提醒提前量一并保留。" else ""
        val allPreviewCourses = analysis.accepted + analysis.conflicts + analysis.duplicates
        val previewCourses = allPreviewCourses.take(30)
        courseList.text = previewCourses.joinToString("\n") { course ->
            val marker = when (course) {
                in analysis.conflicts -> getString(R.string.conflict_marker)
                in analysis.duplicates -> getString(R.string.duplicate_marker)
                else -> "•"
            }
            "$marker ${course.courseName} · ${dayName(course.dayOfWeek)} · ${course.startSection}-${course.endSection}节"
        } + if (allPreviewCourses.size > 30) {
            getString(R.string.import_more_courses)
        } else ""
        if (!parsed.studyTasks.isNullOrEmpty()) courseList.append("\n\n学习事项预览：\n" + parsed.studyTasks.take(15).joinToString("\n\n") {
            com.courseschedule.domain.StudyTaskRules.describe(it)
        } + if (parsed.studyTasks.size > 15) "\n还有${parsed.studyTasks.size - 15}项。" else "")
        includeConflicts.visibility = if (analysis.conflicts.isEmpty()) View.GONE else View.VISIBLE
        restoreMetadata.visibility = if (parsed.semester != null || parsed.settings != null) {
            View.VISIBLE
        } else View.GONE
        // Importing courses must not silently undo a start date edited by the user.
        restoreMetadata.isChecked = false
        replaceExisting.visibility = View.VISIBLE

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.import_preview_title, parsed.sourceLabel))
            .setView(view)
            .setPositiveButton(R.string.confirm_import, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val targetWeeks = if (restoreMetadata.isChecked) {
                    parsed.semester?.totalWeeks ?: currentSemester.totalWeeks
                } else currentSemester.totalWeeks
                val effectiveAnalysis = ImportAnalyzer.analyze(
                    parsed.courses,
                    if (replaceExisting.isChecked) emptyList() else existing,
                    currentSemester.id,
                    targetWeeks
                )
                val selected = effectiveAnalysis.accepted + if (includeConflicts.isChecked) {
                    effectiveAnalysis.conflicts
                } else emptyList()
                if (selected.isEmpty() && (!restoreTasks.isChecked || parsed.studyTasks.isNullOrEmpty())) {
                    Toast.makeText(this, R.string.no_courses_selected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                commitImport(
                    selected,
                    parsed,
                    currentSemester,
                    existing,
                    restoreMetadata.isChecked,
                    replaceExisting.isChecked,
                    restoreTasks.isChecked
                )
            }
        }
        dialog.show()
    }

    private fun commitImport(
        selected: List<Course>,
        parsed: ParsedImport,
        oldSemester: Semester,
        existing: List<Course>,
        restoreMetadata: Boolean,
        replaceExisting: Boolean,
        restoreTasks: Boolean
    ) {
        setLoading(true)
        val preferences = SchedulePreferences(this)
        val oldSettings = preferences.snapshot()
        lifecycleScope.launch {
            try {
                val database = AppDatabase.getDatabase(this@ImportActivity)
                val manager = ReminderManager(this@ImportActivity)
                val importedTasks = mutableListOf<StudyTask>()
                val saved = database.withTransaction {
                    require(database.semesterDao().getCurrentSemesterSync() == oldSemester) { "学期已变化，请重新导入。" }
                    val targetSemester = if (restoreMetadata && parsed.semester != null) {
                        oldSemester.copy(
                            name = parsed.semester.name,
                            startDate = parsed.semester.startDate,
                            totalWeeks = parsed.semester.totalWeeks.coerceIn(1, 52)
                        ).also { semesterViewModel.updateSemesterNow(it) }
                    } else oldSemester
                    val ready = selected
                        .filter { ScheduleRules.isValidCourse(it, targetSemester.totalWeeks) }
                        .map { it.copy(semesterId = targetSemester.id) }
                    if (replaceExisting) manager.cancelAllReminders(existing)
                    val ids = if (replaceExisting) {
                        courseViewModel.replaceCurrentSemesterCourses(ready)
                    } else {
                        courseViewModel.insertCoursesNow(ready)
                    }
                    val saved = ready.zip(ids).map { (course, id) -> course.copy(id = id) }
                    if (restoreTasks) {
                        val existingTasks = database.studyTaskDao().forSemester(targetSemester.id).toMutableList()
                        val currentCourses = database.courseDao().getCoursesBySemesterSync(targetSemester.id)
                        parsed.studyTasks.orEmpty().forEach { task ->
                            if (existingTasks.none { it.title == task.title && it.courseName == task.courseName && it.kind == task.kind && it.dueAt == task.dueAt }) {
                                val readyTask = task.copy(id = 0, semesterId = targetSemester.id,
                                    courseId = currentCourses.filter { it.courseName == task.courseName }.singleOrNull()?.id,
                                    updatedAt = System.currentTimeMillis())
                                val row = readyTask.copy(id = database.studyTaskDao().insert(readyTask))
                                importedTasks += row; existingTasks += row
                            }
                        }
                    }
                    saved
                }
                if (restoreMetadata) parsed.settings?.let(preferences::applySnapshot)
                // 追加导入也可能恢复学期日期/节次配置，旧课程需一并重新排程。
                manager.restoreReminders()
                setLoading(false)
                showImportComplete(
                    saved,
                    oldSemester,
                    oldSettings,
                    existing,
                    restoreMetadata,
                    replaceExisting,
                    importedTasks
                )
            } catch (error: Exception) {
                setLoading(false)
                Toast.makeText(this@ImportActivity, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImportComplete(
        saved: List<Course>,
        oldSemester: Semester,
        oldSettings: com.courseschedule.data.backup.SettingsSnapshot,
        existing: List<Course>,
        restoredMetadata: Boolean,
        replacedExisting: Boolean,
        importedTasks: List<StudyTask>
    ) {
        Snackbar.make(
            binding.root,
            getString(R.string.import_count_success, saved.size) + if (importedTasks.isNotEmpty()) "，${importedTasks.size}项学习事项" else "",
            Snackbar.LENGTH_LONG
        ).setAction(R.string.undo) {
            lifecycleScope.launch {
                val database = AppDatabase.getDatabase(this@ImportActivity)
                if (importedTasks.any { database.studyTaskDao().find(it.id) != it }) {
                    Toast.makeText(this@ImportActivity, "导入后的学习事项已变化，未撤销，以免覆盖修改。", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val manager = ReminderManager(this@ImportActivity)
                importedTasks.forEach { database.studyTaskDao().delete(it.id); manager.cancelStudyReminder(it.id) }
                manager.cancelAllReminders(saved)
                if (replacedExisting) {
                    courseViewModel.replaceCurrentSemesterCourses(existing)
                } else {
                    courseViewModel.deleteCoursesNow(saved.map { it.id })
                }
                if (restoredMetadata) {
                    semesterViewModel.updateSemesterNow(oldSemester)
                    SchedulePreferences(this@ImportActivity).applySnapshot(oldSettings)
                }
                manager.restoreReminders()
                Toast.makeText(this@ImportActivity, R.string.import_undone, Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun dayName(day: Int): String = resources.getStringArray(R.array.weekdays)
        .getOrElse(day - 1) { "" }

    private fun setLoading(loading: Boolean) {
        binding.progressImport.visibility = if (loading) View.VISIBLE else View.GONE
        binding.cardImportSchool.isEnabled = !loading
        binding.cardImportJson.isEnabled = !loading
        binding.cardImportText.isEnabled = !loading
    }

}
