package com.courseschedule.ui.importdata

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ActivityImportBinding
import com.courseschedule.domain.ScheduleRules
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

class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var courseViewModel: CourseViewModel
    private lateinit var semesterViewModel: SemesterViewModel

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFromFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        courseViewModel = ViewModelProvider(this)[CourseViewModel::class.java]
        semesterViewModel = ViewModelProvider(this)[SemesterViewModel::class.java]

        binding.cardImportJson.setOnClickListener { openFilePicker() }
        binding.cardImportText.setOnClickListener { showTextImportDialog() }
        courseViewModel.currentSemester.observe(this) { semester ->
            binding.tvImportTarget.text = getString(
                R.string.import_target_format,
                semester?.name ?: getString(R.string.current_semester)
            )
        }
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
                val parsed = withContext(Dispatchers.IO) { parser(currentSemester.totalWeeks) }
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
                    analysis.duplicates.isEmpty()
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
        summary.text = "$importSummary\n$metadataSummary"
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
        includeConflicts.visibility = if (analysis.conflicts.isEmpty()) View.GONE else View.VISIBLE
        restoreMetadata.visibility = if (parsed.semester != null || parsed.settings != null) {
            View.VISIBLE
        } else View.GONE
        restoreMetadata.isChecked = restoreMetadata.visibility == View.VISIBLE
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
                if (selected.isEmpty()) {
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
                    replaceExisting.isChecked
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
        replaceExisting: Boolean
    ) {
        setLoading(true)
        val preferences = SchedulePreferences(this)
        val oldSettings = preferences.snapshot()
        lifecycleScope.launch {
            try {
                val targetSemester = if (restoreMetadata && parsed.semester != null) {
                    oldSemester.copy(
                        name = parsed.semester.name,
                        startDate = parsed.semester.startDate,
                        totalWeeks = parsed.semester.totalWeeks.coerceIn(1, 52)
                    ).also { semesterViewModel.updateSemesterNow(it) }
                } else oldSemester
                if (restoreMetadata) parsed.settings?.let(preferences::applySnapshot)

                val ready = selected
                    .filter { ScheduleRules.isValidCourse(it, targetSemester.totalWeeks) }
                    .map { it.copy(semesterId = targetSemester.id) }
                val manager = ReminderManager(this@ImportActivity)
                if (replaceExisting) manager.cancelAllReminders(existing)
                val ids = if (replaceExisting) {
                    courseViewModel.replaceCurrentSemesterCourses(ready)
                } else {
                    courseViewModel.insertCoursesNow(ready)
                }
                val saved = ready.zip(ids).map { (course, id) -> course.copy(id = id) }
                manager.rescheduleReminders(saved, targetSemester)
                setLoading(false)
                showImportComplete(
                    saved,
                    oldSemester,
                    oldSettings,
                    existing,
                    restoreMetadata,
                    replaceExisting
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
        replacedExisting: Boolean
    ) {
        Snackbar.make(
            binding.root,
            getString(R.string.import_count_success, saved.size),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.undo) {
            lifecycleScope.launch {
                val manager = ReminderManager(this@ImportActivity)
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
                manager.rescheduleReminders(existing, oldSemester)
                Toast.makeText(this@ImportActivity, R.string.import_undone, Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun dayName(day: Int): String = resources.getStringArray(R.array.weekdays)
        .getOrElse(day - 1) { "" }

    private fun setLoading(loading: Boolean) {
        binding.progressImport.visibility = if (loading) View.VISIBLE else View.GONE
        binding.cardImportJson.isEnabled = !loading
        binding.cardImportText.isEnabled = !loading
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
