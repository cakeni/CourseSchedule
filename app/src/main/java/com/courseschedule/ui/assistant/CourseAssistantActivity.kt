package com.courseschedule.ui.assistant

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.databinding.ActivityCourseAssistantBinding
import com.courseschedule.databinding.DialogAssistantApiConfigBinding
import com.courseschedule.databinding.ItemAssistantMessageBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CourseAssistantActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCourseAssistantBinding
    private lateinit var viewModel: CourseAssistantViewModel
    private var restoringDraft = false
    private var loadingOlder = false
    private var initialContentDrawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.assistant_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        viewModel = ViewModelProvider(this)[CourseAssistantViewModel::class.java]
        // Keep the schedule visible until the restored conversation is ready for its first frame.
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!viewModel.initialConversationLoaded) return false
                binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                if (viewModel.messages.value.orEmpty().isNotEmpty()) {
                    val smoothScrolling = binding.conversationScroll.isSmoothScrollingEnabled
                    binding.conversationScroll.isSmoothScrollingEnabled = false
                    binding.conversationScroll.fullScroll(View.FOCUS_DOWN)
                    binding.conversationScroll.isSmoothScrollingEnabled = smoothScrolling
                }
                initialContentDrawn = true
                return true
            }
        })
        binding.btnConfigureApi.setOnClickListener { showConfig() }
        binding.btnSend.setOnClickListener { send() }
        binding.btnExampleSimple.setOnClickListener { fillExample(R.string.assistant_example_simple) }
        binding.btnExampleDetails.setOnClickListener { fillExample(R.string.assistant_example_details) }
        binding.btnExampleQuery.setOnClickListener { fillExample(R.string.assistant_example_query) }
        binding.btnExampleDelete.setOnClickListener { fillExample(R.string.assistant_example_delete) }
        binding.btnConfirmPending.setOnClickListener { viewModel.confirmPending() }
        binding.btnCancelPending.setOnClickListener { viewModel.cancelPending() }
        binding.btnStopRequest.setOnClickListener { viewModel.stopRequest() }
        binding.btnRetryRequest.setOnClickListener {
            if (viewModel.configured.value != true) showConfig() else try {
                viewModel.retry()
            } catch (error: IllegalArgumentException) { binding.inputMessage.error = error.message }
        }
        binding.btnOlderMessages.setOnClickListener { loadingOlder = true; viewModel.loadOlderMessages() }
        binding.etMessage.doAfterTextChanged { text ->
            if (!restoringDraft) viewModel.updateDraft(text?.toString().orEmpty())
        }
        viewModel.draft.observe(this) { text ->
            if (binding.etMessage.text?.toString() != text) {
                restoringDraft = true
                binding.etMessage.setText(text)
                binding.etMessage.setSelection(text.length)
                restoringDraft = false
            }
        }
        viewModel.sessionTitle.observe(this) { binding.toolbar.subtitle = it }
        viewModel.hasOlderMessages.observe(this) {
            binding.btnOlderMessages.visibility = if (it) View.VISIBLE else View.GONE
        }
        binding.etMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        viewModel.messages.observe(this) { messages ->
            binding.starterContent.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            binding.messagesContainer.removeAllViews()
            messages.forEach { message ->
                val row = ItemAssistantMessageBinding.inflate(layoutInflater, binding.messagesContainer, false)
                val user = message.role == "user"
                row.root.gravity = if (user) Gravity.END else Gravity.START
                val inset = (32 * resources.displayMetrics.density).toInt()
                row.root.setPaddingRelative(if (user) inset else 0, 0, if (user) 0 else inset, 0)
                row.messageBubble.setCardBackgroundColor(ContextCompat.getColor(this,
                    if (user) R.color.primary else R.color.surface))
                val sender = getString(if (user) R.string.assistant_you else R.string.assistant_name)
                val kind = when (message.kind) {
                    "result" -> " · 执行结果"
                    "confirmation" -> " · 待确认"
                    "error" -> " · 未完成"
                    "interrupted" -> " · 已中断"
                    "cancel" -> " · 已取消"
                    else -> ""
                }
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(message.createdAt))
                row.tvMessageSender.text = getString(R.string.assistant_message_metadata, sender, time, kind)
                row.tvMessageSender.setTextColor(ContextCompat.getColor(this,
                    if (user) R.color.on_primary else R.color.primary_variant))
                row.tvMessageBody.setTextColor(ContextCompat.getColor(this,
                    if (user) R.color.on_primary else R.color.text_primary))
                row.tvMessageBody.maxWidth = resources.displayMetrics.widthPixels -
                    (96 * resources.displayMetrics.density).toInt()
                row.tvMessageBody.text = if (message.kind == "confirmation" && message == messages.lastOrNull() &&
                    viewModel.pendingChanges.value != null) message.content.substringBefore("\n\n") else message.content
                binding.messagesContainer.addView(row.root)
            }
            if (initialContentDrawn && messages.isNotEmpty() && !loadingOlder) {
                binding.conversationScroll.post { binding.conversationScroll.fullScroll(View.FOCUS_DOWN) }
            } else if (loadingOlder) {
                binding.conversationScroll.post { binding.conversationScroll.fullScroll(View.FOCUS_UP) }
            }
            loadingOlder = false
        }
        viewModel.configured.observe(this) { configured ->
            binding.tvApiStatus.setText(if (configured) R.string.assistant_api_ready else R.string.assistant_api_needed)
            binding.tvApiModel.text = if (configured) viewModel.config.model
                else getString(R.string.assistant_unconfigured)
            binding.apiStatusIcon.setImageResource(if (configured) R.drawable.ic_check else R.drawable.ic_settings)
        }
        viewModel.pendingChanges.observe(this) { pending ->
            binding.pendingCard.visibility = if (pending == null) View.GONE else View.VISIBLE
            if (pending != null) {
                binding.tvPendingSummary.text = viewModel.pendingSummary(pending)
                binding.btnConfirmPending.setText(if (pending.courses.isEmpty() && pending.updates.isEmpty())
                    R.string.assistant_confirm_delete else R.string.assistant_confirm_changes)
                binding.etMessage.clearFocus()
                WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
                if (initialContentDrawn) {
                    binding.conversationScroll.post { binding.conversationScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            updateControls()
        }
        viewModel.busy.observe(this) { updateControls() }
        viewModel.canUndo.observe(this) { invalidateOptionsMenu() }
        viewModel.canRetry.observe(this) { updateControls() }
        viewModel.canStop.observe(this) { updateControls() }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshSemester()
    }

    private fun updateControls() {
        val busy = viewModel.busy.value == true
        val canChat = !busy && viewModel.pendingChanges.value == null
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSend.isEnabled = canChat
        binding.etMessage.isEnabled = canChat
        binding.btnExampleSimple.isEnabled = canChat
        binding.btnExampleDetails.isEnabled = canChat
        binding.btnExampleQuery.isEnabled = canChat
        binding.btnExampleDelete.isEnabled = canChat
        binding.btnConfigureApi.isEnabled = !busy
        binding.btnConfirmPending.isEnabled = !busy
        binding.btnCancelPending.isEnabled = !busy
        binding.btnOlderMessages.isEnabled = !busy
        binding.btnRetryRequest.visibility = if (viewModel.canRetry.value == true && !busy) View.VISIBLE else View.GONE
        binding.btnStopRequest.visibility = if (viewModel.canStop.value == true) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun fillExample(textResource: Int) {
        binding.etMessage.setText(textResource)
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
        binding.inputMessage.error = null
    }

    private fun send() {
        val text = binding.etMessage.text?.toString().orEmpty().trim()
        if (text.isBlank() || viewModel.busy.value == true) return
        if (viewModel.configured.value != true) { showConfig(); return }
        try {
            binding.inputMessage.error = null
            viewModel.send(text, intent.getIntExtra(EXTRA_DISPLAYED_WEEK, 1))
        } catch (error: IllegalArgumentException) {
            binding.inputMessage.error = error.message
        }
    }

    private fun showConfig() {
        if (viewModel.busy.value == true) return
        val form = DialogAssistantApiConfigBinding.inflate(layoutInflater)
        val old = viewModel.config
        form.etApiUrl.setText(old.baseUrl)
        form.etApiModel.setText(old.model)
        form.inputApiKey.hint = getString(if (old.apiKey.isBlank()) R.string.assistant_key_hint
            else R.string.assistant_key_saved_hint)
        form.checkRememberKey.isChecked = viewModel.remembersKey
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.assistant_api_config)
            .setView(form.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.assistant_clear_config) { _, _ ->
                viewModel.clearConfig()
                Toast.makeText(this, R.string.assistant_config_cleared, Toast.LENGTH_SHORT).show()
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val url = form.etApiUrl.text?.toString().orEmpty().trim()
                val newKey = form.etApiKey.text?.toString().orEmpty().trim()
                try {
                    if (newKey.isBlank() && old.apiKey.isNotBlank()) {
                        require(AssistantApiConfig(baseUrl = url).endpoint() == old.endpoint()) {
                            "更换 API 地址后请重新填写该服务的密钥。"
                        }
                    }
                    viewModel.configure(AssistantApiConfig(url,
                        form.etApiModel.text?.toString().orEmpty().trim(),
                        newKey.ifBlank { old.apiKey }), form.checkRememberKey.isChecked)
                    form.etApiKey.text?.clear()
                    dialog.dismiss()
                } catch (error: IllegalArgumentException) {
                    form.tvConfigError.text = error.message
                } catch (_: Exception) {
                    form.tvConfigError.setText(R.string.assistant_config_save_failed)
                }
            }
        }
        dialog.setOnDismissListener { form.etApiKey.text?.clear() }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_UNDO, Menu.NONE, R.string.assistant_undo_action)
            .setContentDescription(getString(R.string.assistant_undo))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_HISTORY, Menu.NONE, R.string.assistant_history)
            .setContentDescription(getString(R.string.assistant_history))
            .setIcon(R.drawable.ic_assistant_history).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_NEW, Menu.NONE, R.string.assistant_new_conversation)
            .setContentDescription(getString(R.string.assistant_new_conversation))
            .setIcon(R.drawable.ic_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.assistant_delete_conversation)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_UNDO)?.apply {
            isVisible = viewModel.canUndo.value == true
            isEnabled = viewModel.busy.value != true && viewModel.pendingChanges.value == null
        }
        listOf(MENU_HISTORY, MENU_NEW, MENU_DELETE).forEach { menu.findItem(it)?.isEnabled = viewModel.busy.value != true }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_UNDO) { viewModel.undo(); return true }
        if (item.itemId == MENU_NEW) { viewModel.newConversation(); return true }
        if (item.itemId == MENU_HISTORY) { showHistory(); return true }
        if (item.itemId == MENU_DELETE) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.assistant_delete_conversation)
                .setMessage(R.string.assistant_delete_conversation_hint)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteConversation() }.show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showHistory() {
        lifecycleScope.launch {
            try {
                val history = viewModel.history()
                val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val labels = history.map { "${it.title}\n${format.format(Date(it.updatedAt))}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@CourseAssistantActivity).setTitle(R.string.assistant_history)
                    .setItems(labels) { _, index -> viewModel.openHistory(history[index].id) }
                    .setPositiveButton(R.string.assistant_new_conversation) { _, _ -> viewModel.newConversation() }
                    .setNegativeButton(R.string.cancel, null).show()
            } catch (_: Exception) {
                Toast.makeText(this@CourseAssistantActivity, "历史记录加载失败，请重试。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_DISPLAYED_WEEK = "assistant_displayed_week"
        private const val MENU_UNDO = 1
        private const val MENU_HISTORY = 2
        private const val MENU_NEW = 3
        private const val MENU_DELETE = 4
    }
}
