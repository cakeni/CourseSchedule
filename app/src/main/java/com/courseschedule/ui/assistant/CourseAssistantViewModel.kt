package com.courseschedule.ui.assistant

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.AssistantChatMessage
import com.courseschedule.data.entity.AssistantConversation
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.SchedulePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class CourseAssistantViewModel @JvmOverloads constructor(application: Application,
    private val clientFactory: () -> AssistantCourseClient = { AssistantCourseClient() }
) : AndroidViewModel(application) {
    private val store = AssistantConfigStore(application)
    private val database = AppDatabase.getDatabase(application)
    private val conversations = database.assistantConversationDao()
    private val preferences = application.getSharedPreferences("assistant_conversations", Context.MODE_PRIVATE)
    private var conversation: AssistantConversation? = null
    private var state = AssistantConversationState()
    private var loadedMessages = emptyList<AssistantMessage>()
    private var activeClient: AssistantCourseClient? = null
    private var requestJob: Job? = null
    var config = AssistantApiConfig()
        private set
    var remembersKey = false
        private set
    val messages = MutableLiveData<List<AssistantMessage>>(emptyList())
    val busy = MutableLiveData(true)
    val configured = MutableLiveData(false)
    val canUndo = MutableLiveData(false)
    val canRetry = MutableLiveData(false)
    val canStop = MutableLiveData(false)
    val hasOlderMessages = MutableLiveData(false)
    val sessionTitle = MutableLiveData("新对话")
    val draft = MutableLiveData("")
    val pendingChanges = MutableLiveData<AssistantCourseReply?>(null)
    val requestStage = MutableLiveData("正在准备课程上下文…")
    val undoUnavailableReason = MutableLiveData("")
    val reminderNotice = MutableLiveData("")
    internal val conversationId: String? get() = conversation?.id

    init {
        var configError = false
        try {
            config = store.load()
            remembersKey = config.apiKey.isNotBlank()
            configured.value = remembersKey
        } catch (_: Exception) { configError = true }
        launchOperation {
            removeOrphanDrafts()
            ensureConversation(currentSemester())
            if (configError) append("assistant", "保存的 API 配置无法解密，请重新填写。", "error")
        }
    }

    fun configure(value: AssistantApiConfig, remember: Boolean) {
        store.save(value, remember)
        config = value
        remembersKey = remember
        configured.value = true
    }

    fun clearConfig() {
        store.clear()
        config = AssistantApiConfig()
        remembersKey = false
        configured.value = false
    }

    fun updateDraft(text: String) {
        val id = conversation?.id ?: return
        if (draft.value == text) return
        draft.value = text
        preferences.edit().putString("draft_$id", text.take(2000)).apply()
    }

    fun refreshSemester() {
        if (busy.value == true) return
        launchOperation { removeOrphanDrafts(); ensureConversation(currentSemester()) }
    }

    fun newConversation() {
        if (busy.value == true) return
        launchOperation { openConversation(createConversation(currentSemester())) }
    }

    suspend fun history(): List<AssistantConversation> = conversations.conversations(currentSemester().id)

    fun openHistory(id: String) {
        if (busy.value == true) return
        launchOperation {
            val selected = conversations.conversation(id) ?: error("对话已被删除。")
            require(selected.semesterId == currentSemester().id) { "学期已切换，请重新打开历史记录。" }
            openConversation(selected)
        }
    }

    fun deleteConversation() {
        if (busy.value == true) return
        val old = conversation ?: return
        launchOperation {
            conversations.deleteConversation(old.id)
            preferences.edit().remove("draft_${old.id}").remove("active_${old.semesterId}").apply()
            conversation = null
            ensureConversation(currentSemester())
        }
    }

    fun loadOlderMessages() {
        if (busy.value == true || hasOlderMessages.value != true) return
        launchOperation {
            val id = conversation?.id ?: return@launchOperation
            val oldest = loadedMessages.firstOrNull()?.id ?: return@launchOperation
            val older = conversations.messagesBefore(id, oldest, 60).asReversed().map { it.toMessage() }
            loadedMessages = older + loadedMessages
            messages.value = loadedMessages
            hasOlderMessages.value = conversations.hasMessagesBefore(id, loadedMessages.first().id)
        }
    }

    fun send(text: String, displayedWeek: Int) {
        if (busy.value == true || text.isBlank()) return
        require(text.length <= 2000) { "每条消息最多 2000 字。" }
        val quick = if (state.pending == null) AssistantCourseQuery.quick(text) else null
        if (quick == null) config.validate()
        val requestConfig = config
        requestStage.value = "正在准备课程上下文…"
        requestJob = launchOperation(retryable = quick == null) {
            val semester = currentSemester()
            ensureConversation(semester)
            val retry = AssistantRetryRequest(text, displayedWeek.coerceIn(1, semester.totalWeeks), state.pending != null)
            append("user", text, next = state.copy(retryRequest = if (quick == null) retry else null, requestRunning = quick == null))
            updateDraft("")
            if (quick != null) {
                receiveReply(AssistantCourseReply("查询课程", emptyList(), query = quick), semester, retry.displayedWeek)
                return@launchOperation
            }
            val existing = database.courseDao().getCoursesBySemesterSync(semester.id)
            val schedule = SchedulePreferences(getApplication())
            val context = conversations.messages(conversation!!.id, 24).asReversed().map { it.toMessage() }
            val request = AssistantCourseClient.createRequest(requestConfig.model, context, semester,
                retry.displayedWeek, schedule.sectionTimes, schedule.sectionEndTimes, existing,
                canUndo.value == true, schedule.defaultReminderMinutes, requestConfig.jsonMode, state.pending?.reply,
                state.lastUndo?.after.orEmpty().map { it.id })
            val client = clientFactory()
            activeClient = client
            canStop.value = true
            val reply = try {
                requestStage.value = "正在连接课程助手…"
                withContext(Dispatchers.IO) { client.chat(requestConfig, request, semester.totalWeeks, existing, semester,
                    retry.displayedWeek) { requestStage.postValue(it) } }
            } finally { canStop.value = false; activeClient = null }
            currentCoroutineContext().ensureActive()
            receiveReply(reply, semester, retry.displayedWeek, retry.refining)
        }
    }

    fun retry() {
        if (busy.value == true || canRetry.value != true) return
        state.retryRequest?.let { send(it.text, it.displayedWeek) }
    }

    fun stopRequest() {
        if (canStop.value != true) return
        canStop.value = false
        activeClient?.cancel()
        requestJob?.cancel()
    }

    internal suspend fun receiveReply(reply: AssistantCourseReply, semester: Semester, displayedWeek: Int = 1, refining: Boolean = false) {
        requireCurrentSemester(semester)
        ensureConversation(semester)
        require(state.pending == null || refining) { "还有待确认的操作，请先处理。" }
        require(!refining || (!reply.undo && reply.query == null && reply.queriedCourses.isEmpty())) { "修改方案时请明确要调整的字段，或取消方案后查询。" }
        val completed = state.copy(retryRequest = null, requestRunning = false)
        when {
            reply.undo -> undoLast()
            reply.requiresConfirmation || (refining && reply.courses.isNotEmpty()) -> {
                val pending = reply.copy(courses = prepareAdditions(reply, semester))
                append("assistant", "已整理好待执行方案，请核对下面的课程。确认前不会修改或删除课表。\n\n" +
                    pendingSummary(pending), "confirmation",
                    completed.copy(pending = AssistantPendingOperation(semester, pending)))
            }
            reply.courses.isNotEmpty() -> saveCourses(reply, semester)
            reply.query != null -> {
                val found = reply.query.find(database.courseDao().getCoursesBySemesterSync(semester.id), semester, displayedWeek)
                append("assistant", if (found.isEmpty()) "在「${semester.name}」没有找到符合条件的课程。本次未更改课表。" else
                    "在「${semester.name}」找到 ${found.size} 项课程：\n\n" + found.take(200).joinToString("\n\n") { describe(it) } +
                    if (found.size > 200) "\n\n已显示前200项，请补充课名、日期或地点缩小查询范围。" else "", "result", completed)
            }
            reply.queriedCourses.isNotEmpty() -> {
                val latest = database.courseDao().getCoursesBySemesterSync(semester.id).associateBy { it.id }
                val found = reply.queriedCourses.map { original ->
                    latest[original.id]?.takeIf { it == original } ?: throw IllegalArgumentException("查询期间课程已变化，请重新查询。")
                }
                append("assistant", "在「${semester.name}」找到 ${found.size} 项课程：\n\n" +
                    found.joinToString("\n\n") { describe(it) }, "result", completed)
            }
            else -> append("assistant", "本次未更改课表。\n${reply.reply}", next = completed)
        }
    }

    internal suspend fun saveCourses(reply: AssistantCourseReply, semester: Semester) {
        ensureConversation(semester)
        require(!reply.requiresConfirmation && reply.queriedCourses.isEmpty() && !reply.undo)
        executeChanges(reply.copy(courses = prepareAdditions(reply, semester)), semester)
    }

    fun confirmPending() {
        if (busy.value == true) return
        val pending = state.pending ?: return
        launchOperation(clearPendingOnFailure = true) { executeChanges(pending.reply, pending.semester) }
    }

    fun cancelPending() {
        if (busy.value == true || state.pending == null) return
        launchOperation {
            append("assistant", "已取消待执行操作，本次没有更改课表。", "cancel", state.copy(pending = null, retryRequest = null))
        }
    }

    fun pendingSummary(reply: AssistantCourseReply): String = buildList {
        add("涉及 ${reply.courses.size + reply.updates.size + reply.deletions.size} 个目标，保存 ${reply.courses.size + reply.updates.sumOf { it.replacements.size }} 项安排")
        reply.courses.forEach { add("新增\n${describe(it)}") }
        reply.updates.forEach {
            add("修改 · ${it.original.courseName}\n${changeSummary(it)}")
        }
        reply.deletions.forEach { add("删除整条安排\n${describe(it)}") }
    }.joinToString("\n\n")

    private suspend fun executeChanges(reply: AssistantCourseReply, semester: Semester) {
        requestStage.value = "正在校验并保存课程…"
        val originals = reply.updates.map { it.original } + reply.deletions
        val targetIds = originals.map { it.id }
        require(targetIds.distinct().size == targetIds.size && originals.all { it.id > 0 && it.semesterId == semester.id }) {
            "目标课程无效，本次未更改。"
        }
        require(reply.courses.isNotEmpty() || originals.isNotEmpty()) { "没有需要执行的课程操作。" }
        require(reply.courses.all { it.id == 0L && it.semesterId == semester.id }) { "新增课程不能覆盖已有课程。" }
        require(reply.updates.all { change ->
            change.replacements.isNotEmpty() && change.replacements.first().id == change.original.id &&
                change.replacements.drop(1).all { it.id == 0L } &&
                change.replacements.all { it.semesterId == semester.id }
        }) { "修改方案无效，本次未更改。" }
        val ready = reply.updates.flatMap { it.replacements } + reply.courses
        var next = state
        val updated = database.withTransaction {
            requireCurrentSemester(semester)
            val existing = database.courseDao().getCoursesBySemesterSync(semester.id)
            require(originals.all { old -> existing.find { it.id == old.id } == old }) {
                "待操作课程已被修改或删除，本次未更改，请重新描述。"
            }
            val sourceByReplacement = reply.updates.flatMap { change -> change.replacements.map { it to change.original } }.toMap()
            AssistantChangeRules.validate(ready, existing.filter { it.id !in targetIds }, semester, sourceByReplacement)
            database.courseDao().deleteCoursesByIds(reply.deletions.map { it.id })
            val saved = ready.zip(database.courseDao().insertCourses(ready)).map { (course, id) -> course.copy(id = id) }
            next = state.copy(pending = null, lastUndo = AssistantUndoBatch(semester, originals, saved,
                AssistantChangeRules.conflictNeighbors(originals, existing)),
                retryRequest = null, requestRunning = false)
            val result = if (originals.isEmpty()) {
                "已添加到「${semester.name}」：\n" + saved.joinToString("\n\n") { describe(it) }
            } else {
                "已完成「${semester.name}」的课程操作：\n" + buildList {
                    if (reply.courses.isNotEmpty()) add("新增 ${reply.courses.size} 项课程安排")
                    if (reply.updates.isNotEmpty()) add("修改 ${reply.updates.size} 项课程安排")
                    if (reply.deletions.isNotEmpty()) add("删除 ${reply.deletions.size} 项课程安排")
                    if (saved.isNotEmpty()) add(saved.joinToString("\n\n") { describe(it) })
                    if (reply.deletions.isNotEmpty()) add("已删除：${reply.deletions.joinToString { it.courseName }}")
                }.joinToString("\n")
            }
            // Persist the receipt and undo snapshot in the same transaction as the course mutation.
            writeMessage("assistant", "$result\n可撤销本对话最近一次成功操作。", "result", next)
        }
        applyWritten(updated, next)
        undoUnavailableReason.value = ""
        publishState()
        syncReminders(reply.deletions.map { it.id })
    }

    fun undo() {
        if (busy.value == true || state.pending != null || state.lastUndo == null) return
        launchOperation { undoLast() }
    }

    private suspend fun undoLast() {
        val batch = state.lastUndo ?: throw IllegalArgumentException("当前没有可撤销的课程操作。")
        require((batch.before + batch.after).all { it.id > 0 && it.semesterId == batch.semester.id }) {
            "撤销记录无效，未更改课程。"
        }
        val next = state.copy(lastUndo = null, retryRequest = null, requestRunning = false)
        val updated = database.withTransaction {
            requireCurrentSemester(batch.semester)
            val current = database.courseDao().getCoursesBySemesterSync(batch.semester.id)
            val afterIds = batch.after.map { it.id }
            require(batch.after.all { old -> current.find { it.id == old.id } == old } &&
                batch.before.filter { it.id !in afterIds }.all { old -> database.courseDao().getCourseById(old.id) == null }) {
                "相关课程在操作后发生了变化，未撤销，以免覆盖新的修改。"
            }
            AssistantChangeRules.validate(batch.before, current.filter { it.id !in afterIds }, batch.semester,
                batch.before.associateWith { it }, batch.conflictBaseline.orEmpty())
            database.courseDao().deleteCoursesByIds(afterIds)
            database.courseDao().insertCourses(batch.before)
            writeMessage("assistant", "已撤销最近一次课程操作，原有安排和提醒设置已恢复。", "result", next)
        }
        applyWritten(updated, next)
        syncReminders(batch.after.map { it.id })
    }

    private fun prepareAdditions(reply: AssistantCourseReply, semester: Semester): List<Course> {
        val minutes = SchedulePreferences(getApplication()).defaultReminderMinutes
        return reply.courses.mapIndexed { index, course ->
            course.copy(semesterId = semester.id, reminderMinutes = reply.additionReminders[index] ?: minutes,
                colorIndex = com.courseschedule.domain.AiCourseColors.index(course.courseName))
        }
    }

    private suspend fun currentSemester() = database.semesterDao().getCurrentSemesterSync()
        ?: throw IllegalArgumentException("学期正在加载，请稍后重试。")

    private suspend fun requireCurrentSemester(semester: Semester) {
        val current = currentSemester()
        require(current.id == semester.id && current.totalWeeks == semester.totalWeeks &&
            current.startDate == semester.startDate) { "学期设置已变化，本次未操作，请重新发送。" }
    }

    private suspend fun syncReminders(removedIds: List<Long>) {
        try {
            val reminders = ReminderManager(getApplication())
            removedIds.forEach { reminders.cancelReminder(it) }
            reminders.restoreReminders()
            refreshReminderNotice()
        } catch (_: Exception) {
            append("assistant", "课表已保存，但提醒排程失败，请返回课表检查提醒设置。")
        }
    }

    fun refreshReminderNotice() {
        val settings = SchedulePreferences(getApplication())
        reminderNotice.value = when {
            !settings.reminderEnabled -> "当前总提醒开关关闭，已保存的提前量不会生效。点击打开课程提醒设置。"
            !NotificationManagerCompat.from(getApplication()).areNotificationsEnabled() -> "通知权限关闭，已保存的提前量不会生效。请在系统设置中允许通知。"
            !ReminderManager(getApplication()).canScheduleExactAlarms() -> "精确闹钟权限未开启，系统可能延迟送达。点击查看提醒设置。"
            else -> ""
        }
    }

    private suspend fun ensureConversation(semester: Semester) {
        val current = conversation
        if (current?.semesterId == semester.id) {
            val latest = conversations.conversation(current.id)
            if (latest != null) {
                if (latest.revision != current.revision) openConversation(latest) else checkUndoAvailability(semester)
                return
            }
        }
        val savedId = preferences.getString("active_${semester.id}", null)
        val selected = savedId?.let { conversations.conversation(it) }?.takeIf { it.semesterId == semester.id }
            ?: conversations.conversations(semester.id).firstOrNull() ?: createConversation(semester)
        openConversation(selected)
    }

    private suspend fun removeOrphanDrafts() {
        val ids = conversations.conversationIds().toSet()
        val orphanKeys = preferences.all.filter { (key, value) ->
            (key.startsWith("draft_") && key.removePrefix("draft_") !in ids) ||
                (key.startsWith("active_") && (value !is String || value !in ids))
        }.keys
        if (orphanKeys.isNotEmpty()) preferences.edit().apply { orphanKeys.forEach { remove(it) } }.apply()
    }

    private suspend fun createConversation(semester: Semester): AssistantConversation = AssistantConversation(
        UUID.randomUUID().toString(), semester.id, "新对话", System.currentTimeMillis()).also {
        conversations.insertConversation(it)
    }

    private suspend fun openConversation(selected: AssistantConversation) {
        conversation = selected
        loadedMessages = emptyList()
        undoUnavailableReason.value = ""
        var damaged = false
        state = try { AssistantConversationCodec.decode(selected.stateJson, selected.semesterId) }
            catch (_: Exception) { damaged = true; AssistantConversationState() }
        preferences.edit().putString("active_${selected.semesterId}", selected.id).apply()
        val semester = currentSemester()
        sessionTitle.value = "${semester.name} · ${selected.title}"
        draft.value = preferences.getString("draft_${selected.id}", "").orEmpty()
        publishState()
        refreshMessages()
        if (damaged) append("assistant", "操作状态无法读取，聊天记录仍保留。请重新描述，课表不会自动更改。", "error")
        if (state.requestRunning) {
            append("assistant", "上次请求已中断，尚未执行课程操作。可以重试上一条消息或继续输入。", "interrupted",
                state.copy(requestRunning = false))
        }
        state.pending?.let { pending ->
            val existing = database.courseDao().getCoursesBySemesterSync(semester.id).associateBy { it.id }
            if (pending.semester.id != semester.id || pending.semester.startDate != semester.startDate ||
                pending.semester.totalWeeks != semester.totalWeeks ||
                (pending.reply.updates.map { it.original } + pending.reply.deletions).any { existing[it.id] != it }) {
                append("assistant", "原待确认方案已过期，课程或学期设置已变化。请重新描述，本次没有更改课表。", "error",
                    state.copy(pending = null))
            }
        }
        checkUndoAvailability(semester)
    }

    private fun publishState() {
        pendingChanges.value = state.pending?.reply
        canUndo.value = state.lastUndo != null && undoUnavailableReason.value.isNullOrEmpty()
        canRetry.value = state.retryRequest != null && !state.requestRunning &&
            (state.pending == null || state.retryRequest?.refining == true)
    }

    private suspend fun refreshMessages() {
        val id = conversation?.id ?: return
        val recent = conversations.messages(id, 60).asReversed().map { it.toMessage() }
        loadedMessages = (loadedMessages + recent).distinctBy { it.id }.sortedBy { it.id }
        messages.value = loadedMessages
        hasOlderMessages.value = loadedMessages.firstOrNull()?.let { conversations.hasMessagesBefore(id, it.id) } ?: false
    }

    private fun AssistantChatMessage.toMessage() = AssistantMessage(role, content, kind, createdAt, id)

    private suspend fun writeMessage(role: String, content: String, kind: String,
        next: AssistantConversationState): AssistantConversation {
        val current = conversation ?: error("会话尚未加载，请重新打开课程助手。")
        val title = if (role == "user" && current.title == "新对话") content.replace('\n', ' ').take(32) else current.title
        val updated = current.copy(title = title, stateJson = AssistantConversationCodec.encode(next), updatedAt = System.currentTimeMillis(),
            revision = current.revision + 1)
        require(conversations.updateConversation(current.id, title, updated.updatedAt, updated.stateJson, current.revision) == 1) {
            "对话已被其他页面更改或删除，本次未操作，请重新打开助手。"
        }
        conversations.insertMessage(AssistantChatMessage(conversationId = current.id, role = role,
            content = content, kind = kind))
        return updated
    }

    private suspend fun applyWritten(updated: AssistantConversation, next: AssistantConversationState) {
        conversation = updated
        state = next
        sessionTitle.value = "${database.semesterDao().getSemesterById(updated.semesterId)?.name.orEmpty()} · ${updated.title}"
        publishState()
        refreshMessages()
    }

    private suspend fun append(role: String, content: String, kind: String = "chat",
        next: AssistantConversationState = state) {
        val updated = database.withTransaction { writeMessage(role, content, kind, next) }
        applyWritten(updated, next)
    }

    private fun launchOperation(retryable: Boolean = false, clearPendingOnFailure: Boolean = false,
        block: suspend () -> Unit): Job {
        busy.value = true
        return viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) {
                if (retryable) withContext(NonCancellable) {
                    // A committed receipt wins if cancellation races a completed transaction.
                    val saved = conversation?.id?.let { conversations.conversation(it) }
                    val savedState = saved?.let { runCatching {
                        AssistantConversationCodec.decode(it.stateJson, it.semesterId)
                    }.getOrNull() }
                    if (savedState?.requestRunning == true) {
                        append("assistant", "请求已停止，本次没有更改课表。可以重试上一条消息。", "interrupted",
                            savedState.copy(requestRunning = false))
                    }
                }
                throw error
            }
            catch (error: Exception) {
                if (state.lastUndo != null) checkUndoAvailability(runCatching { currentSemester() }.getOrNull())
                if (conversation != null) {
                    val failed = state.copy(requestRunning = false,
                        retryRequest = if (retryable) state.retryRequest else null,
                        pending = if (clearPendingOnFailure) null else state.pending)
                    try {
                        append("assistant", if (error is IllegalArgumentException) error.message.orEmpty()
                            else "本次操作未完成，请稍后重试。", "error", failed)
                    } catch (_: Exception) {
                        messages.value = messages.value.orEmpty() + AssistantMessage("assistant", "本地记录保存失败，请重新打开助手检查结果。")
                    }
                } else {
                    messages.value = listOf(AssistantMessage("assistant", "会话加载失败，请重新打开课程助手。"))
                }
            } finally { busy.value = false; canStop.value = false }
        }
    }

    override fun onCleared() {
        activeClient?.cancel()
        super.onCleared()
    }

    private suspend fun checkUndoAvailability(semester: Semester?) {
        val batch = state.lastUndo
        undoUnavailableReason.value = if (batch == null) "" else runCatching {
            require(semester != null && batch.semester.id == semester.id && batch.semester.startDate == semester.startDate &&
                batch.semester.totalWeeks == semester.totalWeeks) { "学期设置已变化" }
            val current = database.courseDao().getAllCoursesSync()
            val ids = batch.after.map { it.id }
            require(batch.after.all { old -> current.find { it.id == old.id } == old } &&
                batch.before.filter { it.id !in ids }.all { old -> current.none { it.id == old.id } }) { "相关课程之后已被修改" }
            AssistantChangeRules.validate(batch.before, current.filter { it.semesterId == semester.id && it.id !in ids }, semester,
                batch.before.associateWith { it }, batch.conflictBaseline.orEmpty())
        }.exceptionOrNull()?.let { "撤销暂不可用：${it.message.orEmpty()}" }.orEmpty()
        publishState()
    }

    private fun changeSummary(change: AssistantCourseUpdate): String {
        if (change.replacements.size != 1) return "原安排：${describe(change.original)}\n改为：" + change.replacements.joinToString("\n\n") { describe(it) }
        val old = change.original
        val new = change.replacements.single()
        return buildList {
            if (old.courseName != new.courseName) add("课名：${old.courseName} → ${new.courseName}")
            if (old.classroom != new.classroom) add("地点：${old.classroom.ifBlank { "未填写" }} → ${new.classroom.ifBlank { "未填写" }}")
            if (old.teacher != new.teacher) add("教师：${old.teacher.ifBlank { "未填写" }} → ${new.teacher.ifBlank { "未填写" }}")
            if (!AssistantChangeRules.sameArrangement(old, new)) add("原安排：${describe(old)}\n改为：${describe(new)}")
            if (old.note != new.note) add("备注：${old.note.ifBlank { "无" }} → ${new.note.ifBlank { "无" }}")
            if (old.reminderMinutes != new.reminderMinutes) add("提醒：${old.reminderMinutes.takeIf { it > 0 }?.let { "提前${it}分钟" } ?: "不提醒"} → ${new.reminderMinutes.takeIf { it > 0 }?.let { "提前${it}分钟" } ?: "不提醒"}")
            if (isEmpty()) add("课程安排保持不变")
        }.joinToString("\n")
    }

    private fun describe(course: Course): String {
        val sections = if (course.startSection == course.endSection) "第${course.startSection}节"
            else "第${course.startSection}–${course.endSection}节"
        val weeks = if (course.startWeek == course.endWeek) "第${course.startWeek}周"
            else "第${course.startWeek}–${course.endWeek}周"
        val weekType = when (course.weekType) { 1 -> " · 单周"; 2 -> " · 双周"; else -> "" }
        val details = listOf(course.classroom, course.teacher).filter { it.isNotBlank() }.joinToString(" · ")
        val reminder = if (course.reminderMinutes > 0) "提前 ${course.reminderMinutes} 分钟提醒" else "不提醒"
        return "${course.courseName} · 周${"一二三四五六日"[course.dayOfWeek - 1]} · $sections\n$weeks$weekType" +
            (if (details.isEmpty()) "" else " · $details") + "\n$reminder" +
            (if (course.note.isBlank()) "" else "\n备注：${course.note}")
    }
}
