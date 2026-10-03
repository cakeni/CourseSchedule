package com.courseschedule.ui.assistant

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.AssistantChatMessage
import com.courseschedule.data.entity.AssistantConversation
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.ui.importdata.ImportAnalyzer
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.utils.ReminderManager
import com.courseschedule.utils.AlarmReceiver
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
import java.time.LocalDate

internal class CourseAssistantViewModel @JvmOverloads constructor(application: Application,
    private val clientFactory: () -> AssistantCourseClient = { AssistantCourseClient() },
    private val todayProvider: () -> LocalDate = { LocalDate.now() }
) : AndroidViewModel(application) {
    private val store = AssistantConfigStore(application)
    private val database = AppDatabase.getDatabase(application)
    private val conversations = database.assistantConversationDao()
    private val preferences = application.getSharedPreferences("assistant_conversations", Context.MODE_PRIVATE)
    private var conversation: AssistantConversation? = null
    private var state = AssistantConversationState()
    private var messageLimit = 60
    private var messageWindowEnd: Long? = null
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
    val hasNewerMessages = MutableLiveData(false)
    val historyLocation = MutableLiveData<AssistantHistoryLocation?>(null)
    val sessionTitle = MutableLiveData("新对话")
    val conversationTitle = MutableLiveData("新对话")
    val semesterName = MutableLiveData("")
    val draft = MutableLiveData("")
    val pendingChanges = MutableLiveData<AssistantCourseReply?>(null)
    val targetChoice = MutableLiveData<AssistantTargetChoice?>(null)
    var initialConversationLoaded = false
        private set
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
        }.invokeOnCompletion { initialConversationLoaded = true }
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

    suspend fun searchHistory(text: String): AssistantHistoryResults {
        val keyword = text.trim()
        require(keyword.isNotEmpty() && keyword.length <= 100) { "请输入 1–100 字的搜索关键词。" }
        val semester = currentSemester()
        val result = withContext(Dispatchers.Default) {
            val hits = mutableListOf<AssistantHistoryHit>()
            var beforeId = Long.MAX_VALUE
            // Decode native query records before matching; metadata and JSON escapes are not chat text.
            while (hits.size <= 50) {
                currentCoroutineContext().ensureActive()
                val rows = conversations.historyMessages(semester.id, beforeId, 200)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val message = row.message.toMessage()
                    if (message.content.contains(keyword, ignoreCase = true)) {
                        hits += AssistantHistoryHit(row.message.conversationId, row.title, row.message.id,
                            row.message.createdAt, AssistantHistorySearch.snippet(message.content, keyword))
                        if (hits.size > 50) break
                    }
                }
                beforeId = rows.last().message.id
            }
            if (hits.size <= 50) {
                val matched = hits.map { it.conversationId }.toSet()
                conversations.conversations(semester.id).filter { it.id !in matched && it.title.contains(keyword, true) }
                    .take(51 - hits.size).forEach {
                        hits += AssistantHistoryHit(it.id, it.title, null, it.updatedAt, "")
                    }
            }
            AssistantHistoryResults(hits.take(50), hits.size > 50)
        }
        requireCurrentSemester(semester)
        return result
    }

    fun openHistory(id: String, messageId: Long? = null, keyword: String = "") {
        if (busy.value == true) return
        launchOperation {
            val selected = conversations.conversation(id) ?: throw IllegalArgumentException("对话已被删除，请重新搜索。")
            require(selected.semesterId == currentSemester().id) { "学期已切换，请重新打开历史记录。" }
            if (messageId != null) require(conversations.message(id, messageId) != null) { "消息已被删除，请重新搜索。" }
            openConversation(selected, messageId?.let { AssistantHistoryLocation(it, keyword) })
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
        launchOperation { messageLimit += 60; refreshMessages() }
    }

    fun showLatestMessages() {
        if (busy.value == true) return
        launchOperation {
            messageWindowEnd = null
            messageLimit = 60
            historyLocation.value = null
            refreshMessages()
        }
    }

    fun send(text: String, displayedWeek: Int) {
        if (busy.value == true || text.isBlank()) return
        val bound = state.selectedTarget?.takeIf { selected ->
            Regex("它|这门|那门|这节|刚才.*课").containsMatchIn(text) &&
                !Regex("它们|这些|那些|全部|所有|都").containsMatchIn(text) &&
                (state.targetChoice == null || selected in state.targetChoice!!.candidates)
        }
        sendRequest(AssistantRetryRequest(text, displayedWeek, todayProvider().toString()), bound)
    }

    private fun sendRequest(request: AssistantRetryRequest, bound: Course? = null, choiceSemester: Semester? = null) {
        if (busy.value == true) return
        require(request.text.isNotBlank() && request.text.length <= 2000) { "每条消息最多 2000 字。" }
        config.validate()
        val requestConfig = config
        requestJob = launchOperation(retryable = true) {
            val semester = currentSemester()
            ensureConversation(semester)
            val pending = state.pending
            val requestDate = LocalDate.parse(requireNotNull(request.requestDate))
            val retry = request.copy(displayedWeek = request.displayedWeek.coerceIn(1, semester.totalWeeks))
            val existing = database.courseDao().getCoursesBySemesterSync(semester.id)
            val semesterChanged = choiceSemester != null && (choiceSemester.id != semester.id ||
                choiceSemester.startDate != semester.startDate || choiceSemester.totalWeeks != semester.totalWeeks)
            if (semesterChanged || (bound != null && existing.find { it.id == bound.id } != bound)) {
                updateDraft(request.text)
                val reason = if (semesterChanged) "学期设置已变化" else "选中的课程已变化"
                append("assistant", "$reason，请核对原请求日期（$requestDate）后重新描述并选择目标。", "error",
                    state.copy(pending = null, targetChoice = null, selectedTarget = null, retryRequest = null, requestRunning = false))
                return@launchOperation
            }
            append("user", request.text, next = state.copy(retryRequest = retry, requestRunning = true,
                targetChoice = null, selectedTarget = bound))
            updateDraft("")
            val schedule = SchedulePreferences(getApplication())
            val context = conversations.messages(conversation!!.id, 24).asReversed().map { it.toMessage() }
            val request = AssistantCourseClient.createRequest(requestConfig.model, context, semester,
                retry.displayedWeek, schedule.sectionTimes, schedule.sectionEndTimes, existing,
                state.lastUndo != null, schedule.defaultReminderMinutes, pending?.reply, requestDate, bound)
            val client = clientFactory()
            activeClient = client
            canStop.value = true
            val reply = try {
                withContext(Dispatchers.IO) { client.chat(requestConfig, request, semester.totalWeeks, existing,
                    semester, retry.displayedWeek, pending?.reply, requestDate, bound) }
            } finally { canStop.value = false; activeClient = null }
            currentCoroutineContext().ensureActive()
            receiveReply(reply, semester, pending, requestDate)
        }
    }

    fun retry() {
        if (busy.value == true || canRetry.value != true) return
        state.retryRequest?.let {
            if (it.requestDate == null) launchOperation {
                updateDraft(it.text)
                append("assistant", "旧请求未保存原日期，无法确定‘今天、明天、下周’的原含义。请核对日期后重新发送，本次未更改课表。",
                    "error", state.copy(retryRequest = null, requestRunning = false))
            } else sendRequest(it, state.selectedTarget)
        }
    }

    fun chooseTarget(course: Course) {
        if (busy.value == true) return
        val choice = state.targetChoice ?: return
        require(course in choice.candidates) { "请选择列表中的课程。" }
        sendRequest(choice.request, course, choice.semester)
    }

    fun targetLabel(course: Course): String = "课程 #${course.id}\n${describe(course)}"

    suspend fun coursesForDetails(ids: List<Long>): List<Course> {
        require(ids.size in 1..200 && ids.all { it > 0 } && ids.distinct() == ids) { "查询记录无效，请重新查询。" }
        val semester = currentSemester()
        require(conversation?.semesterId == semester.id) { "学期已切换，请重新查询。" }
        val rows = database.courseDao().getCoursesBySemesterSync(semester.id).associateBy { it.id }
        val courses = ids.map { rows[it] ?: throw IllegalArgumentException("查询中的课程已删除或移到其他学期，请重新查询。") }
        requireCurrentSemester(semester)
        return courses
    }

    fun stopRequest() {
        if (canStop.value != true) return
        canStop.value = false
        activeClient?.cancel()
        requestJob?.cancel()
    }

    internal suspend fun receiveReply(reply: AssistantCourseReply, semester: Semester,
        expectedPending: AssistantPendingOperation? = state.pending, today: LocalDate = todayProvider()) {
        requireCurrentSemester(semester)
        ensureConversation(semester)
        require(state.pending == expectedPending) { "待确认方案已在其他页面变化，请重新补充。" }
        val completed = state.copy(retryRequest = null, requestRunning = false)
        if (state.pending != null) {
            require(reply.revisedPending || (!reply.requiresConfirmation && !reply.undo && reply.query == null &&
                !reply.queryRequested && reply.queriedCourses.isEmpty())) { "请先确认或取消原方案，补充消息只能修正原方案。" }
            if (reply.revisedPending) {
                val previous = state.pending!!.reply
                require(reply.updates.map { it.original } == previous.updates.map { it.original } &&
                    reply.deletions == previous.deletions) { "补充修改不能改变原方案的目标。" }
                val existing = database.courseDao().getCoursesBySemesterSync(semester.id)
                val originals = reply.updates.map { it.original } + reply.deletions
                require(originals.all { old -> existing.find { it.id == old.id } == old }) { "原方案目标已变化，请取消后重新描述。" }
                validateReply(reply, existing, semester)
                val revised = reply.copy(revisedPending = false)
                append("assistant", "待确认方案已更新，请核对变化；确认前不会写入课表。\n\n" + pendingSummary(revised),
                    "confirmation", completed.copy(pending = AssistantPendingOperation(semester, revised)))
            } else append("assistant", reply.reply, next = completed)
            return
        }
        require(!reply.revisedPending) { "没有可修正的待确认方案。" }
        when {
            reply.undo -> undoLast()
            reply.targetCandidates != null -> {
                val request = requireNotNull(state.retryRequest) { "请重新发送原请求以选择目标。" }
                requireNotNull(request.requestDate)
                val latest = database.courseDao().getCoursesBySemesterSync(semester.id)
                val candidates = reply.targetCandidates
                require(candidates.size in 2..200 && candidates.all { course -> latest.find { it.id == course.id } == course }) {
                    "候选课程已变化，请重新查询。"
                }
                append("assistant", "有多个符合条件的课程安排，请在下方选择要操作的一条。选择后仍需核对方案并确认。", "selection",
                    completed.copy(targetChoice = AssistantTargetChoice(semester, request, candidates)))
            }
            reply.requiresConfirmation -> {
                val pending = reply.copy(courses = prepareAdditions(reply, semester))
                val latest = database.courseDao().getCoursesBySemesterSync(semester.id)
                require((pending.updates.map { it.original } + pending.deletions).all { old -> latest.find { it.id == old.id } == old }) {
                    "待操作课程已被修改或删除，本次未更改，请重新描述。"
                }
                validateReply(pending, latest, semester)
                append("assistant", "已整理好待执行方案，请核对下面的课程。确认前不会写入课表。\n日期基准：$today\n\n" +
                    pendingSummary(pending), "confirmation",
                    completed.copy(pending = AssistantPendingOperation(semester, pending), targetChoice = null,
                        selectedTarget = (pending.updates.map { it.original } + pending.deletions).distinctBy { it.id }.singleOrNull()))
            }
            reply.query != null -> {
                val latest = database.courseDao().getCoursesBySemesterSync(semester.id)
                val displayedWeek = state.retryRequest?.displayedWeek ?: 1
                val query = reply.query
                val date = AssistantScheduleOperations.resolve(query.whenTo, semester, displayedWeek, today)
                val exactDate = query.whenTo.date ?: query.whenTo.dayOffset?.let { today.plusDays(it.toLong()).toString() }
                val scope = listOfNotNull(exactDate, date.week?.let { "第${it}周" },
                    (date.dayOfWeek ?: query.dayOfWeek)?.let { "周${"一二三四五六日"[it - 1]}" },
                    query.courseName?.let { "课名包含「$it」" }, query.teacher?.let { "教师包含「$it」" },
                    query.classroom?.let { "地点包含「$it」" },
                    query.startSection?.let { "节次 $it–${query.endSection ?: it}" },
                    query.endSection?.takeIf { query.startSection == null }?.let { "节次 1–$it" })
                    .joinToString(" · ").ifEmpty { "全学期课程" }
                if (query.freeSlots) {
                    val schedule = SchedulePreferences(getApplication())
                    val free = AssistantScheduleOperations.freeSections(query, latest, semester, displayedWeek, schedule.sectionTimes.size, today)
                    val summary = if (free.isEmpty()) "指定范围内没有空闲节次。" else
                        "指定范围内的空闲节次：\n" + free.joinToString("\n") { section ->
                            "第${section}节 ${schedule.sectionTimes.getOrNull(section - 1).orEmpty()}–${schedule.sectionEndTimes.getOrNull(section - 1).orEmpty()}"
                        }
                    append("assistant", "日期基准：$today\n在「${semester.name}」查询：$scope\n$summary", "result", completed)
                } else {
                    val found = AssistantScheduleOperations.query(query, latest, semester, displayedWeek, today)
                    appendQuery("日期基准：$today\n查询范围：$scope\n" + querySummary(found, semester), found,
                        completed.copy(selectedTarget = found.singleOrNull()))
                }
            }
            reply.queryRequested || reply.queriedCourses.isNotEmpty() -> {
                val latest = database.courseDao().getCoursesBySemesterSync(semester.id).associateBy { it.id }
                val found = reply.queriedCourses.map { original ->
                    latest[original.id] ?: throw IllegalArgumentException("查询期间课程已变化，请重新查询。")
                }
                appendQuery(querySummary(found, semester), found, completed)
            }
            else -> append("assistant", reply.reply, next = completed)
        }
    }

    internal suspend fun saveCourses(reply: AssistantCourseReply, semester: Semester) {
        ensureConversation(semester)
        require(reply.updates.isEmpty() && reply.deletions.isEmpty() && reply.queriedCourses.isEmpty() && reply.query == null && !reply.undo)
        executeChanges(reply.copy(courses = prepareAdditions(reply, semester)), semester)
    }

    fun confirmPending() {
        if (busy.value == true) return
        val pending = state.pending ?: return
        launchOperation(clearPendingOnFailure = true) { executeChanges(pending.reply, pending.semester) }
    }

    fun cancelPending() {
        if (busy.value == true || (state.pending == null && state.targetChoice == null)) return
        launchOperation {
            append("assistant", "已取消待执行操作，本次没有更改课表。", "cancel",
                state.copy(pending = null, retryRequest = null, requestRunning = false, targetChoice = null))
        }
    }

    fun pendingSummary(reply: AssistantCourseReply): String = buildList {
        addAll(reply.scopeNotes.orEmpty())
        reply.courses.forEach { add("新增\n${describe(it)}") }
        reply.updates.forEach {
            add("修改 ${it.original.courseName}\n变化：${changedFields(it)}\n原安排：${describe(it.original)}\n改为：${it.replacements.joinToString("\n") { row -> describe(row) }}")
        }
        reply.deletions.forEach { add("删除整条安排\n${describe(it)}") }
    }.joinToString("\n\n")

    private suspend fun executeChanges(reply: AssistantCourseReply, semester: Semester) {
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
            validateReply(reply, existing, semester)
            database.courseDao().deleteCoursesByIds(reply.deletions.map { it.id })
            // ImportAnalyzer normalizes imports; validated updates retain their original metadata.
            val saved = ready.zip(database.courseDao().insertCourses(ready)).map { (course, id) -> course.copy(id = id) }
            val conflictsBefore = existing.filter { neighbor -> originals.any { ScheduleRules.coursesOverlap(it, neighbor) } }
            next = state.copy(pending = null, lastUndo = AssistantUndoBatch(semester, originals, saved, conflictsBefore),
                retryRequest = null, requestRunning = false, targetChoice = null,
                selectedTarget = saved.singleOrNull())
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
            writeMessage("assistant", "$result${reminderNotice(saved)}\n可撤销本对话最近一次成功操作。", "result", next)
        }
        applyWritten(updated, next)
        syncReminders(reply.deletions.map { it.id })
    }

    fun undo() {
        if (busy.value == true || state.pending != null || state.targetChoice != null || state.lastUndo == null) return
        launchOperation { undoLast() }
    }

    private suspend fun undoLast() {
        val batch = state.lastUndo ?: throw IllegalArgumentException("当前没有可撤销的课程操作。")
        require((batch.before + batch.after).all { it.id > 0 && it.semesterId == batch.semester.id }) {
            "撤销记录无效，未更改课程。"
        }
        val next = state.copy(lastUndo = null, retryRequest = null, requestRunning = false,
            selectedTarget = batch.before.singleOrNull())
        val updated = database.withTransaction {
            requireCurrentSemester(batch.semester)
            val current = database.courseDao().getCoursesBySemesterSync(batch.semester.id)
            val afterIds = batch.after.map { it.id }
            require(batch.after.all { old -> current.find { it.id == old.id } == old } &&
                batch.before.filter { it.id !in afterIds }.all { old -> database.courseDao().getCourseById(old.id) == null }) {
                "相关课程在操作后发生了变化，未撤销，以免覆盖新的修改。"
            }
            validateCourses(batch.before, current.filter { it.id !in afterIds }, batch.semester,
                batch.before, batch.before + batch.conflictBaseline.orEmpty())
            database.courseDao().deleteCoursesByIds(afterIds)
            database.courseDao().insertCourses(batch.before)
            writeMessage("assistant", "已撤销最近一次课程操作，原有安排和提醒设置已恢复。${reminderNotice(batch.before)}", "result", next)
        }
        applyWritten(updated, next)
        syncReminders(batch.after.map { it.id })
    }

    private fun prepareAdditions(reply: AssistantCourseReply, semester: Semester): List<Course> {
        val minutes = SchedulePreferences(getApplication()).defaultReminderMinutes
        return reply.courses.mapIndexed { index, course ->
            course.copy(semesterId = semester.id, reminderMinutes = reply.additionReminders[index] ?: minutes,
                colorIndex = index % 16)
        }
    }

    private suspend fun currentSemester() = database.semesterDao().getCurrentSemesterSync()
        ?: throw IllegalArgumentException("学期正在加载，请稍后重试。")

    private suspend fun requireCurrentSemester(semester: Semester) {
        val current = currentSemester()
        require(current.id == semester.id && current.totalWeeks == semester.totalWeeks &&
            current.startDate == semester.startDate) { "学期设置已变化，本次未操作，请重新发送。" }
    }

    private fun validateReply(reply: AssistantCourseReply, existing: List<Course>, semester: Semester) {
        val originals = reply.updates.map { it.original } + reply.deletions
        validateCourses(reply.updates.flatMap { it.replacements } + reply.courses,
            existing.filter { course -> originals.none { it.id == course.id } }, semester,
            reply.updates.flatMap { change -> change.replacements.map { change.original } } + reply.courses.map { null }, existing)
    }

    private fun validateCourses(incoming: List<Course>, existing: List<Course>, semester: Semester,
        origins: List<Course?> = incoming.map { null }, baseline: List<Course> = existing) {
        require(incoming.size <= AssistantScheduleOperations.MAX_RECORDS) { "展开后的课程安排超过200项，请分批操作。" }
        val analysis = ImportAnalyzer.analyze(incoming, existing, semester.id, semester.totalWeeks)
        require(analysis.invalid.isEmpty() && incoming.all { it.reminderMinutes in -1..1440 }) { "课程数据无效，本次未更改。" }
        require(analysis.duplicates.isEmpty()) {
            "发现重复课程：${analysis.duplicates.joinToString { it.courseName }}。本次未更改。"
        }
        val conflicts = AssistantScheduleOperations.newConflicts(incoming, existing, origins, baseline)
        require(conflicts.isEmpty()) {
            val conflictText = "发现时间冲突：${conflicts.take(5).joinToString("；") { (course, other) ->
                val weeks = (1..semester.totalWeeks).filter { ScheduleRules.isCourseInWeek(course, it) && ScheduleRules.isCourseInWeek(other, it) }
                "${course.courseName} 与 ${other.courseName}：第${weeks.joinToString("、")}周，周${"一二三四五六日"[course.dayOfWeek - 1]}第${maxOf(course.startSection, other.startSection)}–${minOf(course.endSection, other.endSection)}节"
            }}。本次未更改，请换个时间。"
            val course = conflicts.first().first
            val schedule = SchedulePreferences(getApplication())
            val alternatives = AssistantScheduleOperations.alternativeSections(incoming, existing, origins, baseline,
                incoming.indexOf(course), schedule.sectionTimes.size)
            conflictText + if (alternatives.isEmpty()) "\n本次未找到可用备选节次，请调整星期或其他冲突课程后重试。" else
                "\n「${course.courseName} · 第${course.startWeek}–${course.endWeek}周${when (course.weekType) { 1 -> "（单周）"; 2 -> "（双周）"; else -> "" }}」的同日备选节次（仅按个人课表计算，周次和课时长不变）：\n" +
                    alternatives.joinToString("\n") { row -> "周${"一二三四五六日"[row.dayOfWeek - 1]}第${row.startSection}–${row.endSection}节 " +
                        "${schedule.sectionTimes[row.startSection - 1]}–${schedule.sectionEndTimes[row.endSection - 1]}" } + "\n请明确选择节次后重新描述，新的方案仍须确认。"
        }
    }

    private suspend fun syncReminders(removedIds: List<Long>) {
        try {
            val reminders = ReminderManager(getApplication())
            removedIds.forEach { reminders.cancelReminder(it) }
            reminders.restoreReminders()
        } catch (_: Exception) {
            append("assistant", "课表已保存，但提醒排程失败，请返回课表检查提醒设置。")
        }
    }

    private suspend fun ensureConversation(semester: Semester) {
        val current = conversation
        if (current?.semesterId == semester.id) {
            val latest = conversations.conversation(current.id)
            if (latest != null) {
                if (latest.revision != current.revision) openConversation(latest)
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

    private suspend fun openConversation(selected: AssistantConversation, location: AssistantHistoryLocation? = null) {
        val windowEnd = location?.let {
            val following = conversations.followingMessageIds(selected.id, it.messageId)
            require(following.firstOrNull() == it.messageId) { "消息已被删除，请重新搜索。" }
            following.last()
        }
        conversation = selected
        messageLimit = 60
        messageWindowEnd = windowEnd
        historyLocation.value = location
        var damaged = false
        state = try { AssistantConversationCodec.decode(selected.stateJson, selected.semesterId) }
            catch (_: Exception) { damaged = true; AssistantConversationState() }
        preferences.edit().putString("active_${selected.semesterId}", selected.id).apply()
        val semester = currentSemester()
        sessionTitle.value = "${semester.name} · ${selected.title}"
        conversationTitle.value = selected.title
        semesterName.value = semester.name
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
        state.targetChoice?.let { choice ->
            val existing = database.courseDao().getCoursesBySemesterSync(semester.id)
            if (choice.semester != semester || choice.candidates.any { old -> existing.find { it.id == old.id } != old }) {
                append("assistant", "候选课程或学期设置已变化，请重新描述并选择目标。", "error",
                    state.copy(targetChoice = null, selectedTarget = null))
            }
        }
    }

    private fun publishState() {
        pendingChanges.value = state.pending?.reply
        targetChoice.value = state.targetChoice
        canUndo.value = state.lastUndo != null
        canRetry.value = state.retryRequest != null && !state.requestRunning
    }

    private suspend fun refreshMessages() {
        val id = conversation?.id ?: return
        val end = messageWindowEnd
        val count = conversations.messageCount(id)
        val windowCount = if (end == null) count else conversations.messageCountEndingAt(id, end)
        hasOlderMessages.value = windowCount > messageLimit
        hasNewerMessages.value = windowCount < count
        messages.value = (if (end == null) conversations.messages(id, messageLimit) else
            conversations.messagesEndingAt(id, end, messageLimit)).asReversed().map { it.toMessage() }
    }

    private fun AssistantChatMessage.toMessage() = AssistantHistorySearch.message(this)

    private suspend fun appendQuery(text: String, found: List<Course>, next: AssistantConversationState) {
        if (found.isEmpty()) append("assistant", text, "result", next) else
            append("assistant", AssistantQueryMessage.encode(text, found.take(200).map { it.id }), "query", next)
    }

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
        messageWindowEnd = null
        historyLocation.value = null
        val currentSemesterName = database.semesterDao().getSemesterById(updated.semesterId)?.name.orEmpty()
        sessionTitle.value = "$currentSemesterName · ${updated.title}"
        conversationTitle.value = updated.title
        semesterName.value = currentSemesterName
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

    private fun querySummary(found: List<Course>, semester: Semester): String =
        if (found.isEmpty()) "在「${semester.name}」没有找到符合条件的课程。" else
            "在「${semester.name}」找到 ${found.size} 项课程：\n\n" + found.take(200).joinToString("\n\n") { "课程 #${it.id}\n${describe(it)}" } +
                if (found.size > 200) "\n仅显示前200项，请缩小查询范围。" else ""

    private fun reminderNotice(courses: List<Course>): String {
        if (courses.none { it.reminderMinutes > 0 }) return ""
        val schedule = SchedulePreferences(getApplication())
        return when {
            !schedule.reminderEnabled -> "\n提醒提前量已保存，但当前总提醒关闭。可从菜单打开提醒设置。"
            !AlarmReceiver.notificationsAvailable(getApplication()) ->
                "\n提醒提前量已保存，但应用通知未开启。可从菜单打开提醒设置。"
            !ReminderManager(getApplication()).canScheduleExactAlarms() ->
                "\n提醒提前量已保存，当前使用非精确提醒，送达时间可能延后。可从菜单打开提醒设置。"
            else -> ""
        }
    }

    private fun changedFields(change: AssistantCourseUpdate): String {
        val old = change.original
        return buildList {
            if (change.replacements.any { it.courseName != old.courseName }) add("课程名称")
            if (change.replacements.any { it.teacher != old.teacher }) add("教师")
            if (change.replacements.any { it.classroom != old.classroom }) add("地点")
            if (change.replacements.any { it.dayOfWeek != old.dayOfWeek || it.startSection != old.startSection || it.endSection != old.endSection }) add("星期/节次")
            val oldWeeks = (1..52).filter { ScheduleRules.isCourseInWeek(old, it) }
            val newWeeks = change.replacements.flatMap { row -> (1..52).filter { ScheduleRules.isCourseInWeek(row, it) } }.distinct().sorted()
            if (oldWeeks != newWeeks || change.replacements.size > 1) add("周次/单次安排")
            if (change.replacements.any { it.note != old.note }) add("备注")
            if (change.replacements.any { it.reminderMinutes != old.reminderMinutes }) add("提醒")
        }.joinToString("、").ifEmpty { "安排保持不变" }
    }
}
