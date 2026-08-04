package com.courseschedule.ui.addcourse

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        // 设置颜色
        val background = holder.colorView.background as? GradientDrawable
            ?: GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                holder.colorView.background = it
            }
        background.setColor(color)

        // 显示/隐藏选中标记
        holder.checkIcon.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE

        // 点击事件
        holder.itemView.setOnClickListener {
            val selectedPosition = holder.bindingAdapterPosition
            if (selectedPosition == RecyclerView.NO_POSITION) return@setOnClickListener
            val oldIndex = selectedIndex
            selectedIndex = selectedPosition
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
            onColorSelected(selectedPosition)
        }
    }

    override fun getItemCount() = colors.size

    fun setSelectedIndex(index: Int) {
        if (index !in colors.indices) return
        val oldIndex = selectedIndex
        selectedIndex = index
        notifyItemChanged(oldIndex)
        notifyItemChanged(selectedIndex)
    }
}
