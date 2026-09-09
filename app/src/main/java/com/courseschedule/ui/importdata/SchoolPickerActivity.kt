package com.courseschedule.ui.importdata

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.courseschedule.R
import com.courseschedule.databinding.ActivitySchoolPickerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SchoolPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIRECTORY_ID = "academic_directory_id"
    }

    private lateinit var binding: ActivitySchoolPickerBinding
    private var allEntries: List<AcademicSchoolDirectoryEntry> = emptyList()
    private val adapter = AcademicSchoolAdapter(::selectSchool)
    private var selectedCategory: AcademicDirectoryCategory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySchoolPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.schoolList.layoutManager = LinearLayoutManager(this)
        binding.schoolList.itemAnimator = null
        binding.schoolList.adapter = adapter
        binding.etSchoolSearch.doAfterTextChanged { updateResults(it?.toString().orEmpty()) }
        binding.schoolCategoryGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategory = when (checkedIds.firstOrNull()) {
                R.id.chipUndergraduate -> AcademicDirectoryCategory.UNDERGRADUATE
                R.id.chipGraduate -> AcademicDirectoryCategory.GRADUATE
                R.id.chipCommonSystem -> AcademicDirectoryCategory.COMMON
                else -> null
            }
            updateResults(binding.etSchoolSearch.text?.toString().orEmpty())
        }
        binding.btnRequestSchool.setOnClickListener { openSchoolRequest() }
        binding.tvSchoolCount.setText(R.string.academic_school_loading)
        lifecycleScope.launch {
            allEntries = withContext(Dispatchers.Default) {
                AcademicSchoolDirectory.entries(applicationContext)
            }
            updateResults(binding.etSchoolSearch.text?.toString().orEmpty())
        }
    }

    private fun updateResults(query: String) {
        val categoryEntries = selectedCategory?.let { category ->
            allEntries.filter { it.category == category }
        } ?: allEntries
        val results = AcademicSchoolDirectory.search(categoryEntries, query)
        adapter.submitList(results)
        binding.tvSchoolCount.text = getString(
            if (query.isBlank() && selectedCategory == null) R.string.academic_school_count
            else R.string.academic_school_search_count,
            results.size
        )
        binding.tvNoSchool.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun selectSchool(entry: AcademicSchoolDirectoryEntry) {
        if (!entry.canImport) {
            showAdapterRequired(entry)
            return
        }
        if (entry.allowCleartext) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.academic_cleartext_title)
                .setMessage(getString(R.string.academic_cleartext_detail, entry.name, entry.host))
                .setPositiveButton(R.string.academic_cleartext_continue) { _, _ -> finishSelection(entry) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        finishSelection(entry)
    }

    private fun showAdapterRequired(entry: AcademicSchoolDirectoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (entry.isCloudOnly) R.string.academic_cloud_only_title
                else R.string.academic_adapter_required_title
            )
            .setMessage(
                getString(
                    if (entry.isCloudOnly) R.string.academic_cloud_only_detail
                    else R.string.academic_adapter_required_detail,
                    entry.name
                )
            )
            .setPositiveButton(R.string.academic_request_adapter) { _, _ -> openSchoolRequest() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun finishSelection(entry: AcademicSchoolDirectoryEntry) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DIRECTORY_ID, entry.id))
        finish()
    }

    private fun openSchoolRequest() {
        val uri = Uri.parse(getString(R.string.academic_school_request_url))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, R.string.academic_open_project_failed, Toast.LENGTH_SHORT).show() }
    }
}
