package com.courseschedule.ui.assistant

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.courseschedule.databinding.AssistantHistoryHeaderBinding
import com.courseschedule.databinding.AssistantPendingFooterBinding
import android.content.Intent
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.R
import com.courseschedule.databinding.ActivityCourseAssistantBinding
import com.courseschedule.databinding.DialogAssistantApiConfigBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CourseAssistantActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCourseAssistantBinding
    private lateinit var viewModel: CourseAssistantViewModel
    private lateinit var header: AssistantHistoryHeaderBinding
    private lateinit var footer: AssistantPendingFooterBinding
    private val messageAdapter = AssistantMessageAdapter()
    private var restoringDraft = false
    private var loadingOlder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.conversationList.layoutManager = LinearLayoutManager(this)
        header = AssistantHistoryHeaderBinding.inflate(layoutInflater, binding.conversationList, false)
        footer = AssistantPendingFooterBinding.inflate(layoutInflater, binding.conversationList, false)
        binding.conversationList.itemAnimator = null
        binding.conversationList.adapter = ConcatAdapter(ConcatAdapter.Config.Builder()
            .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build(),
            AssistantPanelAdapter(header.root), messageAdapter, AssistantPanelAdapter(footer.root))
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.assistant_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        viewModel = ViewModelProvider(this)[CourseAssistantViewModel::class.java]
        binding.btnConfigureApi.setOnClickListener { showConfig() }
        binding.btnSend.setOnClickListener { send() }
        header.btnExampleSimple.setOnClickListener { fillExample(R.string.assistant_example_simple) }
        header.btnExampleDetails.setOnClickListener { fillExample(R.string.assistant_example_details) }
        header.btnExampleQuery.setOnClickListener { fillExample(R.string.assistant_example_query) }
        header.btnExampleDelete.setOnClickListener { fillExample(R.string.assistant_example_delete) }
        footer.btnConfirmPending.setOnClickListener { viewModel.confirmPending() }
        footer.btnCancelPending.setOnClickListener { viewModel.cancelPending() }
        binding.btnStopRequest.setOnClickListener { viewModel.stopRequest() }
        binding.btnRetryRequest.setOnClickListener {
            if (viewModel.configured.value != true) showConfig() else try {
                viewModel.retry()
            } catch (error: IllegalArgumentException) { binding.inputMessage.error = error.message }
        }
        header.btnOlderMessages.setOnClickListener { loadingOlder = true; viewModel.loadOlderMessages() }
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
            header.btnOlderMessages.visibility = if (it) View.VISIBLE else View.GONE
        }
        binding.etMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        viewModel.messages.observe(this) { messages ->
            val manager = binding.conversationList.layoutManager as LinearLayoutManager
            val first = manager.findFirstVisibleItemPosition().coerceAtLeast(1)
            val anchor = messageAdapter.currentList.getOrNull(first - 1)?.id
            val offset = manager.findViewByPosition(first)?.top ?: 0
            val atBottom = manager.findLastVisibleItemPosition() >= messageAdapter.itemCount
            val older = loadingOlder
            val newUserMessage = messages.lastOrNull()?.role == "user" && messages.lastOrNull()?.id != messageAdapter.currentList.lastOrNull()?.id
            header.starterContent.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            messageAdapter.pending = viewModel.pendingChanges.value != null
            messageAdapter.submitList(messages) {
                if (older && anchor != null) {
                    val index = messages.indexOfFirst { it.id == anchor }
                    if (index >= 0) manager.scrollToPositionWithOffset(index + 1, offset)
                } else if (!older && messages.isNotEmpty() && (atBottom || newUserMessage)) scrollToLatest()
            }
            loadingOlder = false
        }
        viewModel.configured.observe(this) { configured ->
            binding.tvApiStatus.setText(if (configured) R.string.assistant_api_ready else R.string.assistant_api_needed)
            binding.tvApiModel.text = viewModel.config.model
            binding.apiStatusIcon.setImageResource(if (configured) R.drawable.ic_check else R.drawable.ic_settings)
        }
        viewModel.pendingChanges.observe(this) { pending ->
            footer.pendingCard.visibility = if (pending == null) View.GONE else View.VISIBLE
            if (pending != null) {
                footer.tvPendingSummary.text = viewModel.pendingSummary(pending)
                footer.btnConfirmPending.setText(if (pending.courses.isEmpty() && pending.updates.isEmpty())
                    R.string.assistant_confirm_delete else R.string.assistant_confirm_changes)
                binding.root.requestFocus()
                WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
                scrollToLatest()
            }
            updateControls()
        }
        viewModel.requestStage.observe(this) { binding.tvRequestStage.text = it }
        viewModel.reminderNotice.observe(this) { updateNotice() }
        viewModel.undoUnavailableReason.observe(this) { updateNotice() }
        binding.tvAssistantNotice.setOnClickListener {
            if (!viewModel.reminderNotice.value.isNullOrBlank()) startActivity(Intent(this, SettingsActivity::class.java))
        }
        viewModel.busy.observe(this) { updateControls() }
        viewModel.canUndo.observe(this) { invalidateOptionsMenu() }
        viewModel.canRetry.observe(this) { updateControls() }
        viewModel.canStop.observe(this) { updateControls() }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSemester()
        viewModel.refreshReminderNotice()
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshSemester()
    }

    private fun updateControls() {
        val busy = viewModel.busy.value == true
        val canChat = !busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSend.isEnabled = canChat
        binding.btnSend.setText(if (viewModel.pendingChanges.value != null) R.string.assistant_refine_plan else R.string.assistant_send)
        binding.etMessage.hint = getString(if (viewModel.pendingChanges.value != null) R.string.assistant_refine_hint else R.string.assistant_message_hint)
        binding.etMessage.isEnabled = canChat
        header.btnExampleSimple.isEnabled = canChat
        header.btnExampleDetails.isEnabled = canChat
        header.btnExampleQuery.isEnabled = canChat
        header.btnExampleDelete.isEnabled = canChat
        binding.btnConfigureApi.isEnabled = !busy
        footer.btnConfirmPending.isEnabled = !busy
        footer.btnCancelPending.isEnabled = !busy
        header.btnOlderMessages.isEnabled = !busy
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
        if (viewModel.configured.value != true && (viewModel.pendingChanges.value != null || AssistantCourseQuery.quick(text) == null)) { showConfig(); return }
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
        form.checkJsonMode.isChecked = old.jsonMode
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
                        newKey.ifBlank { old.apiKey }, form.checkJsonMode.isChecked), form.checkRememberKey.isChecked)
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

    private fun scrollToLatest() {
        binding.conversationList.post {
            val count = binding.conversationList.adapter?.itemCount ?: 0
            if (count > 0) {
                binding.conversationList.scrollToPosition(count - 1)
                binding.conversationList.doOnLayout { list ->
                    val manager = binding.conversationList.layoutManager as LinearLayoutManager
                    val bottom = manager.findViewByPosition(count - 1)?.bottom ?: return@doOnLayout
                    val hidden = bottom - (list.height - list.paddingBottom)
                    if (hidden > 0) binding.conversationList.scrollBy(0, hidden)
                }
            }
        }
    }

    private fun updateNotice() {
        val text = listOf(viewModel.reminderNotice.value.orEmpty(), viewModel.undoUnavailableReason.value.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
        binding.tvAssistantNotice.text = text
        binding.tvAssistantNotice.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_DISPLAYED_WEEK = "assistant_displayed_week"
        private const val MENU_UNDO = 1
        private const val MENU_HISTORY = 2
        private const val MENU_NEW = 3
        private const val MENU_DELETE = 4
    }
}
