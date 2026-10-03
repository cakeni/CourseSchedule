package com.courseschedule.ui.assistant

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.activity.addCallback
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.ui.MainActivity
import com.courseschedule.ui.addcourse.AddCourseActivity
import com.courseschedule.data.entity.Course
import com.courseschedule.databinding.ActivityCourseAssistantBinding
import com.courseschedule.databinding.DialogAssistantApiConfigBinding
import com.courseschedule.databinding.ItemAssistantMessageBinding
import com.courseschedule.databinding.ItemAssistantHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CourseAssistantActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCourseAssistantBinding
    private lateinit var viewModel: CourseAssistantViewModel
    private var restoringDraft = false
    private var loadingOlder = false
    private var initialContentDrawn = false
    private var historySearchJob: Job? = null
    private var historySearchGeneration = 0
    private var historySearching = false
    private var imeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.assistant_title)
        viewModel = ViewModelProvider(this)[CourseAssistantViewModel::class.java]
        window.statusBarColor = ContextCompat.getColor(this, R.color.assistant_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.assistant_background)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = resources.getBoolean(R.bool.window_light_system_bars)
            isAppearanceLightNavigationBars = resources.getBoolean(R.bool.window_light_system_bars)
        }
        binding.toolbar.navigationContentDescription = getString(R.string.assistant_history)
        binding.toolbar.setNavigationOnClickListener { showHistory() }
        setupHistoryPanel()
        // Keep the schedule visible until the restored conversation is ready for its first frame.
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!viewModel.initialConversationLoaded) return false
                binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                if (viewModel.historyLocation.value != null) {
                    scrollToHistoryMessage()
                } else if (viewModel.messages.value.orEmpty().isNotEmpty()) {
                    val smoothScrolling = binding.conversationScroll.isSmoothScrollingEnabled
                    binding.conversationScroll.isSmoothScrollingEnabled = false
                    binding.conversationScroll.fullScroll(View.FOCUS_DOWN)
                    binding.conversationScroll.isSmoothScrollingEnabled = smoothScrolling
                }
                initialContentDrawn = true
                return true
            }
        })
        binding.historyPanel.btnConfigureApi.setOnClickListener { closeHistory(); showConfig() }
        binding.btnConnectService.setOnClickListener { showConfig() }
        binding.btnSend.setOnClickListener { if (viewModel.canStop.value == true) viewModel.stopRequest() else send() }
        binding.btnQuickPrompts.setOnClickListener { showQuickPrompts() }
        binding.btnUndoLastAction.setOnClickListener { viewModel.undo() }
        binding.btnExampleSimple.setOnClickListener { fillExample(R.string.assistant_example_simple) }
        binding.btnExampleDetails.setOnClickListener { fillExample(R.string.assistant_example_details) }
        binding.btnExampleQuery.setOnClickListener { fillExample(R.string.assistant_example_query) }
        binding.btnExampleDelete.setOnClickListener { fillExample(R.string.assistant_example_delete) }
        binding.btnConfirmPending.setOnClickListener { viewModel.confirmPending() }
        binding.btnCancelPending.setOnClickListener { viewModel.cancelPending() }
        binding.btnCancelTargetChoice.setOnClickListener { viewModel.cancelPending() }
        binding.btnChooseTarget.setOnClickListener {
            val choice = viewModel.targetChoice.value ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle(R.string.assistant_choose_target_title)
                .setItems(choice.candidates.map(viewModel::targetLabel).toTypedArray()) { _, index ->
                    if (viewModel.configured.value != true) showConfig() else try {
                        viewModel.chooseTarget(choice.candidates[index])
                    } catch (error: IllegalArgumentException) { binding.inputMessage.error = error.message }
                }.setNegativeButton(R.string.cancel, null).show()
        }
        binding.btnRetryRequest.setOnClickListener {
            if (viewModel.configured.value != true) showConfig() else try {
                viewModel.retry()
            } catch (error: IllegalArgumentException) { binding.inputMessage.error = error.message }
        }
        binding.btnOlderMessages.setOnClickListener { loadingOlder = true; viewModel.loadOlderMessages() }
        binding.btnLatestMessages.setOnClickListener { viewModel.showLatestMessages() }
        binding.etMessage.doAfterTextChanged { text ->
            if (!restoringDraft) viewModel.updateDraft(text?.toString().orEmpty())
            updateControls()
        }
        viewModel.draft.observe(this) { text ->
            if (binding.etMessage.text?.toString() != text) {
                restoringDraft = true
                binding.etMessage.setText(text)
                binding.etMessage.setSelection(text.length)
                restoringDraft = false
            }
        }
        viewModel.conversationTitle.observe(this) {
            binding.toolbar.subtitle = if (it == getString(R.string.assistant_new_conversation)) null else it
            if (binding.root.isDrawerOpen(GravityCompat.START)) refreshHistory()
        }
        viewModel.semesterName.observe(this) { binding.historyPanel.tvHistorySemester.text = it }
        viewModel.hasOlderMessages.observe(this) {
            binding.btnOlderMessages.visibility = if (it) View.VISIBLE else View.GONE
        }
        viewModel.hasNewerMessages.observe(this) {
            binding.btnLatestMessages.visibility = if (it || viewModel.historyLocation.value != null) View.VISIBLE else View.GONE
        }
        viewModel.historyLocation.observe(this) {
            binding.btnLatestMessages.visibility = if (it != null || viewModel.hasNewerMessages.value == true) View.VISIBLE else View.GONE
            updateControls()
        }
        binding.etMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        viewModel.messages.observe(this) { messages ->
            updateStarterVisibility()
            binding.messagesContainer.removeAllViews()
            messages.forEach { message ->
                val row = ItemAssistantMessageBinding.inflate(layoutInflater, binding.messagesContainer, false)
                row.root.tag = message.id
                val user = message.role == "user"
                row.root.gravity = if (user) Gravity.END else Gravity.START
                val inset = (32 * resources.displayMetrics.density).toInt()
                row.root.setPaddingRelative(if (user) inset else 0, 0, if (user) 0 else inset, 0)
                row.messageBubble.setCardBackgroundColor(ContextCompat.getColor(this,
                    if (user) R.color.assistant_soft_surface else R.color.assistant_background))
                val sender = getString(if (user) R.string.assistant_you else R.string.assistant_name)
                val kind = when (message.kind) {
                    "result" -> " · 执行结果"
                    "confirmation" -> " · 待确认"
                    "error" -> " · 未完成"
                    "interrupted" -> " · 已中断"
                    "cancel" -> " · 已取消"
                    else -> ""
                }
                row.tvMessageSender.visibility = if (user) View.GONE else View.VISIBLE
                row.tvMessageSender.text = "$sender$kind"
                row.tvMessageSender.setTextColor(ContextCompat.getColor(this,
                    R.color.assistant_text_secondary))
                row.tvMessageBody.setTextColor(ContextCompat.getColor(this,
                    R.color.assistant_text))
                row.tvMessageBody.maxWidth = resources.displayMetrics.widthPixels -
                    (96 * resources.displayMetrics.density).toInt()
                row.tvMessageBody.text = if (message.kind == "confirmation" && message == messages.lastOrNull() &&
                    viewModel.pendingChanges.value != null && viewModel.hasNewerMessages.value != true)
                    message.content.substringBefore("\n\n") else message.content
                viewModel.historyLocation.value?.takeIf { it.messageId == message.id }?.let {
                    row.tvMessageBody.text = highlighted(row.tvMessageBody.text.toString(), it.keyword)
                    row.messageBubble.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                    row.messageBubble.strokeColor = ContextCompat.getColor(this, R.color.assistant_accent)
                }
                row.btnViewQueriedCourses.visibility = if (!user && message.courseIds.isNotEmpty()) View.VISIBLE else View.GONE
                row.btnViewQueriedCourses.setText(if (message.courseIds.size > 1)
                    R.string.assistant_select_queried_course else R.string.assistant_view_queried_courses)
                row.btnViewQueriedCourses.setOnClickListener { showQueriedCourses(message.courseIds) }
                binding.messagesContainer.addView(row.root)
            }
            if (loadingOlder) {
                scrollConversation(View.FOCUS_UP)
            } else if (viewModel.historyLocation.value != null) {
                scrollToHistoryMessage()
            } else if (initialContentDrawn && messages.isNotEmpty()) {
                scrollConversation(View.FOCUS_DOWN)
            }
            loadingOlder = false
        }
        viewModel.configured.observe(this) { configured ->
            binding.historyPanel.tvApiStatus.setText(if (configured) R.string.assistant_api_ready else R.string.assistant_api_needed)
            binding.historyPanel.tvApiModel.text = if (configured) viewModel.config.model
                else getString(R.string.assistant_service_unconfigured)
            binding.historyPanel.apiStatusIcon.setImageResource(if (configured) R.drawable.ic_check else R.drawable.ic_assistant_service)
            binding.btnConnectService.visibility = if (configured) View.GONE else View.VISIBLE
        }
        viewModel.pendingChanges.observe(this) { pending ->
            binding.pendingCard.visibility = if (pending == null) View.GONE else View.VISIBLE
            if (pending != null) {
                val summary = viewModel.pendingSummary(pending)
                binding.tvPendingSummary.text = SpannableString(summary).apply {
                    Regex("(?m)^变化：.*$").findAll(summary).forEach {
                        setSpan(StyleSpan(Typeface.BOLD), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                binding.btnConfirmPending.setText(if (pending.courses.isEmpty() && pending.updates.isEmpty())
                    R.string.assistant_confirm_delete else R.string.assistant_confirm_changes)
                binding.etMessage.clearFocus()
                WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
                if (initialContentDrawn && viewModel.historyLocation.value == null) {
                    scrollConversation(View.FOCUS_DOWN)
                }
            }
            updateControls()
        }
        viewModel.busy.observe(this) { updateControls() }
        viewModel.targetChoice.observe(this) { choice ->
            binding.targetChoiceCard.visibility = if (choice == null) View.GONE else View.VISIBLE
            if (choice != null && initialContentDrawn && viewModel.historyLocation.value == null) scrollConversation(View.FOCUS_DOWN)
            updateControls()
        }
        viewModel.canUndo.observe(this) { updateControls() }
        viewModel.canRetry.observe(this) { updateControls() }
        viewModel.canStop.observe(this) { updateControls() }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshSemester()
    }

    private fun scrollConversation(direction: Int) {
        binding.conversationScroll.doOnPreDraw {
            val scroll = binding.conversationScroll
            val bottom = (scroll.getChildAt(0).height + scroll.paddingTop + scroll.paddingBottom - scroll.height).coerceAtLeast(0)
            // Replace any previous scroll animation before jumping to the newly measured content.
            scroll.smoothScrollBy(0, 0, 0)
            scroll.scrollTo(0, if (direction == View.FOCUS_DOWN) bottom else 0)
        }
    }

    private fun highlighted(text: String, keyword: String): CharSequence = SpannableString(text).apply {
        val index = if (keyword.isEmpty()) -1 else text.indexOf(keyword, ignoreCase = true)
        if (index >= 0) {
            setSpan(StyleSpan(Typeface.BOLD), index, index + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(ContextCompat.getColor(this@CourseAssistantActivity, R.color.divider)),
                index, index + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ContextCompat.getColor(this@CourseAssistantActivity, R.color.assistant_accent)),
                index, index + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun scrollToHistoryMessage() {
        binding.conversationScroll.doOnPreDraw {
            val location = viewModel.historyLocation.value ?: return@doOnPreDraw
            val row = (0 until binding.messagesContainer.childCount).map(binding.messagesContainer::getChildAt)
                .firstOrNull { it.tag == location.messageId } ?: return@doOnPreDraw
            val body = row.findViewById<TextView>(R.id.tvMessageBody)
            val index = if (location.keyword.isEmpty()) -1 else body.text.toString().indexOf(location.keyword, ignoreCase = true)
            val lineTop = if (index < 0) 0 else body.layout?.let { it.getLineTop(it.getLineForOffset(index)) } ?: 0
            val scroll = binding.conversationScroll
            val rect = if (row.height <= scroll.height) Rect(0, 0, row.width, row.height).also {
                scroll.offsetDescendantRectToMyCoords(row, it)
            } else Rect(0, (lineTop - body.lineHeight).coerceAtLeast(0), body.width, lineTop + body.lineHeight).also {
                scroll.offsetDescendantRectToMyCoords(body, it)
            }
            scroll.smoothScrollBy(0, 0, 0)
            scroll.scrollTo(0, (rect.top - scroll.paddingTop).coerceAtLeast(0))
        }
    }

    private fun updateControls() {
        val busy = viewModel.busy.value == true
        val pending = viewModel.pendingChanges.value != null
        val canChat = !busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        val canStop = viewModel.canStop.value == true
        binding.btnSend.isEnabled = canStop || (canChat && !binding.etMessage.text.isNullOrBlank())
        binding.etMessage.isEnabled = canChat
        binding.btnSend.contentDescription = getString(if (canStop) R.string.assistant_stop_request else
            if (pending) R.string.assistant_revise_send else R.string.assistant_send)
        binding.btnSend.setIconResource(if (canStop) R.drawable.ic_assistant_stop else R.drawable.ic_assistant_send)
        binding.btnQuickPrompts.visibility = if (busy) View.GONE else View.VISIBLE
        binding.btnQuickPrompts.isEnabled = canChat && !pending && viewModel.targetChoice.value == null
        binding.tvComposerMode.visibility = if (busy) View.GONE else View.VISIBLE
        binding.tvComposerMode.text = if (pending) getString(R.string.assistant_revise_send) else ""
        binding.btnUndoLastAction.visibility = if (viewModel.canUndo.value == true && !pending &&
            viewModel.targetChoice.value == null && viewModel.historyLocation.value == null) View.VISIBLE else View.GONE
        binding.btnUndoLastAction.isEnabled = canChat
        binding.btnConnectService.isEnabled = canChat
        binding.etMessage.setHint(if (pending) R.string.assistant_revision_hint else R.string.assistant_message_hint)
        binding.btnExampleSimple.isEnabled = canChat && !pending
        binding.btnExampleDetails.isEnabled = canChat && !pending
        binding.btnExampleQuery.isEnabled = canChat && !pending
        binding.btnExampleDelete.isEnabled = canChat && !pending
        binding.historyPanel.btnConfigureApi.isEnabled = !busy
        binding.historyPanel.btnDrawerNewConversation.isEnabled = !busy
        binding.historyPanel.historyResults.isEnabled = !busy && !historySearching
        binding.btnConfirmPending.isEnabled = !busy
        binding.btnCancelPending.isEnabled = !busy
        binding.btnChooseTarget.isEnabled = !busy
        binding.btnCancelTargetChoice.isEnabled = !busy
        binding.btnOlderMessages.isEnabled = !busy
        binding.btnLatestMessages.isEnabled = !busy
        for (index in 0 until binding.messagesContainer.childCount) {
            binding.messagesContainer.getChildAt(index).findViewById<View>(R.id.btnViewQueriedCourses).isEnabled = !busy
        }
        binding.btnRetryRequest.visibility = if (viewModel.canRetry.value == true && !busy) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun showQueriedCourses(ids: List<Long>) {
        if (viewModel.busy.value == true) return
        lifecycleScope.launch {
            try {
                val courses = viewModel.coursesForDetails(ids)
                if (courses.size == 1) openQueriedCourse(courses.single()) else
                    MaterialAlertDialogBuilder(this@CourseAssistantActivity).setTitle(R.string.assistant_view_queried_courses)
                        .setItems(courses.map(viewModel::targetLabel).toTypedArray()) { _, index ->
                            lifecycleScope.launch {
                                try { openQueriedCourse(viewModel.coursesForDetails(listOf(courses[index].id)).single()) }
                                catch (error: IllegalArgumentException) { Toast.makeText(this@CourseAssistantActivity, error.message, Toast.LENGTH_LONG).show() }
                            }
                        }.setNegativeButton(R.string.cancel, null).show()
            } catch (error: IllegalArgumentException) { Toast.makeText(this@CourseAssistantActivity, error.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun openQueriedCourse(course: Course) {
        startActivity(Intent(this, AddCourseActivity::class.java).putExtra("course_id", course.id).putExtra("is_edit", true))
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
        menu.add(Menu.NONE, MENU_NEW, Menu.NONE, R.string.assistant_new_conversation)
            .setContentDescription(getString(R.string.assistant_new_conversation))
            .setIcon(R.drawable.ic_assistant_new_chat).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.findItem(MENU_NEW).icon?.setTint(ContextCompat.getColor(this, R.color.assistant_text))
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.assistant_delete_conversation)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        listOf(MENU_NEW, MENU_DELETE).forEach { menu.findItem(it)?.isEnabled = viewModel.busy.value != true }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_NEW) { viewModel.newConversation(); return true }
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
        binding.etMessage.clearFocus()
        WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        binding.root.openDrawer(GravityCompat.START)
    }

    private fun closeHistory() {
        historySearchGeneration++
        historySearchJob?.cancel()
        historySearching = false
        binding.historyPanel.etHistorySearch.clearFocus()
        WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        binding.root.closeDrawer(GravityCompat.START)
    }

    private fun updateStarterVisibility() {
        binding.starterContent.visibility = if (viewModel.messages.value.orEmpty().isEmpty() && !imeVisible) View.VISIBLE else View.GONE
    }

    private fun setupHistoryPanel() {
        val form = binding.historyPanel
        form.root.layoutParams = form.root.layoutParams.apply {
            width = minOf((resources.displayMetrics.widthPixels * 0.86f).toInt(), (360 * resources.displayMetrics.density).toInt())
        }
        val drawerBack = onBackPressedDispatcher.addCallback(this, false) { closeHistory() }
        binding.root.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) { drawerBack.isEnabled = slideOffset > 0 }
            override fun onDrawerOpened(drawerView: View) { drawerBack.isEnabled = true; refreshHistory() }
            override fun onDrawerClosed(drawerView: View) { drawerBack.isEnabled = false; historySearchJob?.cancel() }
        })
        form.btnCloseHistory.setOnClickListener { closeHistory() }
        form.btnHistorySearch.setOnClickListener {
            form.etHistorySearch.clearFocus()
            WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
            refreshHistory()
        }
        form.btnHistoryAll.setOnClickListener { form.etHistorySearch.setText(""); refreshHistory() }
        form.etHistorySearch.doAfterTextChanged { if (binding.root.isDrawerVisible(GravityCompat.START)) refreshHistory(debounce = true) }
        form.etHistorySearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { form.btnHistorySearch.performClick(); true } else false
        }
        form.btnDrawerNewConversation.setOnClickListener { closeHistory(); viewModel.newConversation() }
        form.btnDrawerReminders.setOnClickListener {
            closeHistory()
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_FOCUS_REMINDERS, true))
        }
        form.btnReturnSchedule.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            updateKeyboardLayout(insets.isVisible(WindowInsetsCompat.Type.ime()))
            insets
        }
        // With adjustResize, the decor can consume IME insets before DrawerLayout receives them.
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            ViewCompat.getRootWindowInsets(binding.etMessage)?.let {
                updateKeyboardLayout(it.isVisible(WindowInsetsCompat.Type.ime()))
            }
        }
    }

    private fun updateKeyboardLayout(visible: Boolean) {
        if (imeVisible != visible) {
            imeVisible = visible
            updateStarterVisibility()
        }
        val footerVisibility = if (visible && binding.root.isDrawerVisible(GravityCompat.START)) View.GONE else View.VISIBLE
        if (binding.historyPanel.historyFooter.visibility != footerVisibility) binding.historyPanel.historyFooter.visibility = footerVisibility
    }

    private fun refreshHistory(debounce: Boolean = false) {
        historySearchJob?.cancel()
        val generation = ++historySearchGeneration
        val form = binding.historyPanel
        val keyword = form.etHistorySearch.text?.toString().orEmpty().trim()
        form.btnHistoryAll.visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
        historySearching = true
        form.historyResults.isEnabled = false
        form.tvHistoryStatus.setText(R.string.assistant_history_search_loading)
        historySearchJob = lifecycleScope.launch {
            try {
                if (debounce) delay(300)
                val results = if (keyword.isEmpty()) AssistantHistoryResults(viewModel.history().map {
                    AssistantHistoryHit(it.id, it.title, null, it.updatedAt, "")
                }, false) else viewModel.searchHistory(keyword)
                val rows = historyRows(results.hits)
                val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                form.historyResults.adapter = object : BaseAdapter() {
                    override fun getCount() = rows.size
                    override fun getItem(position: Int) = rows[position].hit
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getViewTypeCount() = 2
                    override fun getItemViewType(position: Int) = if (rows[position].hit == null) 0 else 1
                    override fun areAllItemsEnabled() = false
                    override fun isEnabled(position: Int) = rows[position].hit != null
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val item = rows[position]
                        val hit = item.hit
                        if (hit == null) {
                            val header = convertView as? TextView ?: layoutInflater.inflate(R.layout.item_assistant_history_section, parent, false) as TextView
                            header.setText(when (item.period) {
                                AssistantHistoryPeriod.TODAY -> R.string.assistant_history_today
                                AssistantHistoryPeriod.YESTERDAY -> R.string.assistant_history_yesterday
                                AssistantHistoryPeriod.WEEK -> R.string.assistant_history_week
                                AssistantHistoryPeriod.OLDER -> R.string.assistant_history_older
                            })
                            ViewCompat.setAccessibilityHeading(header, true)
                            return header
                        }
                        val row = if (convertView == null) ItemAssistantHistoryBinding.inflate(layoutInflater, parent, false)
                            else ItemAssistantHistoryBinding.bind(convertView)
                        val selected = hit.conversationId == viewModel.conversationId &&
                            (keyword.isEmpty() || hit.messageId == viewModel.historyLocation.value?.messageId)
                        row.root.background = if (selected) ContextCompat.getDrawable(this@CourseAssistantActivity, R.drawable.bg_assistant_history_selected) else null
                        row.tvHistoryTitle.text = highlighted(hit.title, keyword)
                        row.tvHistoryTitle.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                        row.tvHistorySnippet.visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
                        row.tvHistoryTimestamp.visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
                        row.tvHistoryTimestamp.text = format.format(Date(hit.createdAt))
                        row.tvHistorySnippet.text = if (hit.messageId == null) getString(R.string.assistant_history_title_match) else highlighted(hit.text, keyword)
                        return row.root
                    }
                }
                form.tvHistoryStatus.text = when {
                    results.hits.isEmpty() -> getString(R.string.assistant_history_search_empty)
                    results.hasMore -> getString(R.string.assistant_history_search_more)
                    keyword.isEmpty() -> getString(R.string.assistant_history_count, results.hits.size)
                    else -> getString(R.string.assistant_history_search_count, results.hits.size)
                }
                form.historyResults.setOnItemClickListener { _, _, position, _ ->
                    rows[position].hit?.let { hit -> if (viewModel.busy.value != true) {
                        closeHistory()
                        viewModel.openHistory(hit.conversationId, hit.messageId, keyword)
                    } }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { form.tvHistoryStatus.text = error.message }
            catch (_: Exception) { form.tvHistoryStatus.setText(R.string.assistant_history_load_failed) }
            finally { if (generation == historySearchGeneration) { historySearching = false; updateControls() } }
        }
    }

    private fun showQuickPrompts() {
        if (viewModel.busy.value == true || viewModel.pendingChanges.value != null || viewModel.targetChoice.value != null) return
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_assistant_prompts, null)
        dialog.setContentView(sheet)
        val examples = listOf(R.id.btnPromptAdd to R.string.assistant_example_simple,
            R.id.btnPromptQuery to R.string.assistant_example_query, R.id.btnPromptChange to R.string.assistant_example_details,
            R.id.btnPromptDelete to R.string.assistant_example_delete)
        examples.forEach { (id, text) -> sheet.findViewById<View>(id).setOnClickListener {
            dialog.dismiss()
            fillExample(text)
            binding.etMessage.requestFocus()
        } }
        dialog.show()
    }

    companion object {
        const val EXTRA_DISPLAYED_WEEK = "assistant_displayed_week"
        private const val MENU_NEW = 3
        private const val MENU_DELETE = 4
    }
}
