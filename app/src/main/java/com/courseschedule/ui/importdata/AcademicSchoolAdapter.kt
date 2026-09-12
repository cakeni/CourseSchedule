package com.courseschedule.ui.importdata

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.courseschedule.R
import com.courseschedule.databinding.ItemAcademicSchoolBinding

internal class AcademicSchoolAdapter(
    private val onClick: (AcademicSchoolDirectoryEntry) -> Unit
) : ListAdapter<AcademicSchoolDirectoryEntry, AcademicSchoolAdapter.SchoolViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SchoolViewHolder = SchoolViewHolder(
        ItemAcademicSchoolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: SchoolViewHolder, position: Int) = holder.bind(getItem(position))

    inner class SchoolViewHolder(
        private val binding: ItemAcademicSchoolBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: AcademicSchoolDirectoryEntry) = with(binding) {
            tvSchoolName.text = entry.name
            val profileLabel = entry.profile?.label
                ?: root.context.getString(R.string.academic_adapter_required_label)
            val categoryLabel = root.context.getString(when (entry.category) {
                AcademicDirectoryCategory.UNDERGRADUATE -> R.string.academic_category_undergraduate
                AcademicDirectoryCategory.GRADUATE -> R.string.academic_category_graduate
                AcademicDirectoryCategory.COMMON -> R.string.academic_category_common
            })
            val hostLabel = entry.host.ifBlank {
                root.context.getString(
                    if (entry.needsUserUrl) R.string.academic_enter_school_url
                    else R.string.academic_no_independent_address
                )
            }
            tvSchoolMeta.text = root.context.getString(
                if (entry.allowCleartext) R.string.academic_school_meta_cleartext else R.string.academic_school_meta,
                "$categoryLabel · $profileLabel",
                hostLabel
            )
            tvSchoolStatus.setText(
                if (entry.needsUserUrl) R.string.academic_status_url_required else when (entry.support) {
                    AcademicDirectorySupport.VERIFIED -> R.string.academic_status_verified
                    AcademicDirectorySupport.COMPATIBLE -> R.string.academic_status_compatible
                    AcademicDirectorySupport.EXPERIMENTAL -> R.string.academic_status_experimental
                    AcademicDirectorySupport.ADAPTER_REQUIRED -> R.string.academic_status_adapter_required
                }
            )
            tvSchoolStatus.setTextColor(ContextCompat.getColor(
                root.context,
                if (entry.needsUserUrl) R.color.secondary_variant else when (entry.support) {
                    AcademicDirectorySupport.VERIFIED,
                    AcademicDirectorySupport.COMPATIBLE -> R.color.primary
                    AcademicDirectorySupport.EXPERIMENTAL -> R.color.secondary_variant
                    AcademicDirectorySupport.ADAPTER_REQUIRED -> R.color.error
                }
            ))
            root.alpha = if (entry.canImport || entry.needsUserUrl) 1f else 0.72f
            root.contentDescription = root.context.getString(
                R.string.academic_school_accessibility,
                entry.name,
                profileLabel,
                tvSchoolStatus.text
            )
            root.setOnClickListener { onClick(entry) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AcademicSchoolDirectoryEntry>() {
            override fun areItemsTheSame(oldItem: AcademicSchoolDirectoryEntry, newItem: AcademicSchoolDirectoryEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: AcademicSchoolDirectoryEntry, newItem: AcademicSchoolDirectoryEntry) =
                oldItem == newItem
        }
    }
}
