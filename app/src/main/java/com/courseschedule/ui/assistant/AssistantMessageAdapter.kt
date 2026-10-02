package com.courseschedule.ui.assistant

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.courseschedule.R
import com.courseschedule.databinding.ItemAssistantMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AssistantMessageAdapter : ListAdapter<AssistantMessage, AssistantMessageAdapter.Holder>(object : DiffUtil.ItemCallback<AssistantMessage>() {
    override fun areItemsTheSame(old: AssistantMessage, new: AssistantMessage) = old.id == new.id
    override fun areContentsTheSame(old: AssistantMessage, new: AssistantMessage) = old == new
}) {
    var pending = false
    private val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = getItem(position).id.takeIf { it > 0 } ?: -(position + 1L)
    override fun getItemViewType(position: Int) = if (getItem(position).role == "user") 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = ContextThemeWrapper(parent.context, if (viewType == 1)
            R.style.ThemeOverlay_CourseSchedule_AssistantSelection_OnPrimary else R.style.ThemeOverlay_CourseSchedule_AssistantSelection)
        return Holder(ItemAssistantMessageBinding.inflate(LayoutInflater.from(context), parent, false))
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = holder.binding
        val context = row.root.context
        val message = getItem(position)
        val user = message.role == "user"
        row.root.gravity = if (user) Gravity.END else Gravity.START
        val inset = (32 * context.resources.displayMetrics.density).toInt()
        row.root.setPaddingRelative(if (user) inset else 0, 0, if (user) 0 else inset, 0)
        row.messageBubble.setCardBackgroundColor(ContextCompat.getColor(context, if (user) R.color.primary else R.color.surface))
        val kind = when (message.kind) { "result" -> " · 执行结果"; "confirmation" -> " · 待确认"
            "error" -> " · 未完成"; "interrupted" -> " · 已中断"; "cancel" -> " · 已取消"; else -> "" }
        val sender = context.getString(if (user) R.string.assistant_you else R.string.assistant_name)
        row.tvMessageSender.text = context.getString(R.string.assistant_message_metadata, sender, format.format(Date(message.createdAt)), kind)
        row.tvMessageSender.setTextColor(ContextCompat.getColor(context, if (user) R.color.on_primary else R.color.primary_variant))
        row.tvMessageBody.setTextColor(ContextCompat.getColor(context, if (user) R.color.on_primary else R.color.text_primary))
        row.tvMessageBody.maxWidth = context.resources.displayMetrics.widthPixels - (96 * context.resources.displayMetrics.density).toInt()
        row.tvMessageBody.text = if (pending && position == itemCount - 1 && message.kind == "confirmation")
            message.content.substringBefore("\n\n") else message.content
    }
    class Holder(val binding: ItemAssistantMessageBinding) : RecyclerView.ViewHolder(binding.root)
}

internal class AssistantPanelAdapter(private val panel: View) : RecyclerView.Adapter<AssistantPanelAdapter.Holder>() {
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = 0L
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (panel.parent != holder.frame) {
            (panel.parent as? ViewGroup)?.removeView(panel)
            holder.frame.removeAllViews()
            holder.frame.addView(panel)
        }
    }
    class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
}
