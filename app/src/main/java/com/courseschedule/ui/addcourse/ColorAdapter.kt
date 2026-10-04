package com.courseschedule.ui.addcourse

import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.courseschedule.R

/**
 * 颜色选择器适配器
 */
class ColorAdapter(
    private val colors: IntArray,
    private val onColorSelected: (Int) -> Unit
) : RecyclerView.Adapter<ColorAdapter.ColorViewHolder>() {

    private var selectedIndex = 0

    class ColorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val colorView: View = itemView.findViewById(R.id.colorView)
        val checkIcon: View = itemView.findViewById(R.id.checkIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color, parent, false)
        return ColorViewHolder(view)
    }

    override fun onBindViewHolder(holder: ColorViewHolder, position: Int) {
        val color = colors[position]

        val background = holder.colorView.background as? GradientDrawable
            ?: GradientDrawable().also {
                it.cornerRadius = 7 * holder.itemView.resources.displayMetrics.density
                holder.colorView.background = it
            }
        background.setColor(color)
        holder.checkIcon.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE
        (holder.checkIcon as ImageView).imageTintList = ColorStateList.valueOf(
            if (ColorUtils.calculateContrast(Color.WHITE, color) >= 3) Color.WHITE else Color.rgb(32, 42, 53)
        )
        holder.itemView.isSelected = position == selectedIndex
        val name = holder.itemView.resources.getStringArray(R.array.editor_color_names)[position]
        holder.itemView.contentDescription = name + if (position == selectedIndex) "，已选择" else ""
        holder.itemView.setOnClickListener {
            val selectedPosition = holder.bindingAdapterPosition
            if (selectedPosition == RecyclerView.NO_POSITION || selectedPosition == selectedIndex) return@setOnClickListener
            val oldIndex = selectedIndex
            selectedIndex = selectedPosition
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
            onColorSelected(selectedPosition)
        }
    }

    override fun getItemCount() = colors.size

    fun setSelectedIndex(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index.takeIf { it in colors.indices } ?: -1
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
    }
}
