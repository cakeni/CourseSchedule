package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.google.gson.JsonObject
import java.time.*

internal data class AssistantStudyChange(val original: StudyTask?, val after: StudyTask?)
internal data class AssistantStudyQuery(val status: String = "pending", val kind: String? = null,
    val courseName: String? = null, val title: String? = null, val window: String = "all",
    val weekOffset: Int = 0, val daysAhead: Int = 7, val startDate: String? = null, val endDate: String? = null)

internal object AssistantStudyProtocol {
    fun normalizeEnvelope(input: JsonObject): JsonObject = input.deepCopy().apply {
        val taskAction = get("action")?.asString?.startsWith("task_") == true
        val unused = if (taskAction) listOf("courses", "updates", "deleteIds", "queryIds", "occurrences", "revisions")
            else listOf("taskCreates", "taskUpdates", "taskDeletes", "taskRevisions")
        unused.forEach { key -> if (has(key)) {
            require(get(key).isJsonArray && getAsJsonArray(key).size() == 0) { "不能混合课程和学习事项操作。" }
            remove(key)
        } }
        if (taskAction && has("undo")) { require(!bool(this, "undo")); remove("undo") }
    }
    fun parse(root: JsonObject, semester: Semester, courses: List<Course>, tasks: List<StudyTask>,
        pending: AssistantCourseReply?, today: LocalDate): AssistantCourseReply {
        require(root.keySet().all { it in setOf("version", "action", "reply", "taskCreates", "taskUpdates", "taskDeletes", "taskQuery", "taskRevisions") })
        require(int(root, "version") == 1)
        val reply = text(root, "reply", 4000).also { require(it.isNotBlank()) }
        val action = text(root, "action", 30)
        if (action == "task_query") {
            require(root.keySet().all { it in setOf("version", "action", "reply", "taskQuery") })
            val row = root.getAsJsonObject("taskQuery")
            require(row.keySet().all { it in setOf("status", "kind", "courseName", "title", "window", "weekOffset", "daysAhead", "startDate", "endDate") })
            val query = AssistantStudyQuery(status = optional(row, "status", 20) ?: "pending", kind = optional(row, "kind", 20),
                courseName = optional(row, "courseName", 120), title = optional(row, "title", 120),
                window = optional(row, "window", 20) ?: "all", weekOffset = if (row.has("weekOffset")) int(row, "weekOffset") else 0,
                daysAhead = if (row.has("daysAhead")) int(row, "daysAhead") else 7,
                startDate = optional(row, "startDate", 10), endDate = optional(row, "endDate", 10))
            validateQuery(query)
            return AssistantCourseReply(reply, emptyList(), studyQuery = query)
        }
        if (action == "task_revise") {
            require(!pending?.studyChanges.isNullOrEmpty() && root.keySet().all { it in setOf("version", "action", "reply", "taskRevisions") })
            val changes = pending!!.studyChanges!!.toMutableList()
            val rows = root.getAsJsonArray("taskRevisions")
            require(rows.size() in 1..20)
            val indexes = mutableSetOf<Int>()
            rows.forEach {
                val patch = it.asJsonObject
                val index = int(patch, "index")
                require(index in changes.indices && indexes.add(index))
                val old = requireNotNull(changes[index].after)
                val fields = patch.deepCopy().apply { remove("index") }
                require(fields.size() > 0) { "修正方案需要提供实际改变的字段。" }
                changes[index] = changes[index].copy(after = readTask(fields, semester, courses, today, old))
            }
            return AssistantCourseReply(reply, emptyList(), studyChanges = changes, revisedPending = true)
        }
        require(action == "task_change" && pending == null && !root.has("taskQuery") && !root.has("taskRevisions"))
        val changes = mutableListOf<AssistantStudyChange>()
        root.getAsJsonArray("taskCreates")?.forEach { changes += AssistantStudyChange(null, readTask(it.asJsonObject, semester, courses, today)) }
        fun original(row: JsonObject): StudyTask {
            val id = long(row, "id")
            val task = tasks.find { it.id == id && it.semesterId == semester.id } ?: error("unknown task")
            require(text(row, "targetTitle", 120) == task.title)
            val date = optional(row, "targetDueDate", 10)
            if (date != null) require(LocalDate.parse(date) == Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate())
            val explicit = if (row.has("targetIdExplicit")) bool(row, "targetIdExplicit") else false
            require(explicit || tasks.count { it.title == task.title && (date == null ||
                Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() == date) } == 1) { "同名事项需明确编号或截止日期。" }
            return task
        }
        root.getAsJsonArray("taskUpdates")?.forEach {
            val row = it.asJsonObject
            val old = original(row)
            val patch = row.deepCopy().apply { listOf("id", "targetTitle", "targetDueDate", "targetIdExplicit").forEach { remove(it) } }
            require(patch.size() > 0)
            changes += AssistantStudyChange(old, readTask(patch, semester, courses, today, old))
        }
        root.getAsJsonArray("taskDeletes")?.forEach {
            val row = it.asJsonObject
            require(row.keySet().all { key -> key in setOf("id", "targetTitle", "targetDueDate", "targetIdExplicit") })
            changes += AssistantStudyChange(original(row), null)
        }
        require(changes.size in 1..20 && changes.mapNotNull { it.original?.id }.distinct().size == changes.count { it.original != null })
        return AssistantCourseReply(reply, emptyList(), studyChanges = changes)
    }

    private fun readTask(row: JsonObject, semester: Semester, courses: List<Course>, today: LocalDate, old: StudyTask? = null): StudyTask {
        require(row.keySet().all { it in setOf("title", "kind", "courseName", "due", "reminderMinutes", "note", "completed") })
        val name = optional(row, "courseName", 120) ?: old?.courseName.orEmpty()
        val matching = courses.filter { it.semesterId == semester.id && it.courseName == name }
        require(name.isBlank() || name == old?.courseName || matching.isNotEmpty()) { "关联课程不存在。" }
        val dueAt = if (row.has("due")) {
            val due = row.getAsJsonObject("due")
            require(due.keySet().all { it in setOf("date", "dayOffset", "weekOffset", "dayOfWeek", "time") })
            require(listOf("date", "dayOffset", "weekOffset").count { due.has(it) } == 1)
            val date = when {
                due.has("date") -> { require(!due.has("dayOfWeek")); LocalDate.parse(text(due, "date", 10)) }
                due.has("dayOffset") -> { require(!due.has("dayOfWeek")); today.plusDays(int(due, "dayOffset").also { require(it in -366..730) }.toLong()) }
                else -> today.minusDays((today.dayOfWeek.value - 1).toLong()).plusWeeks(int(due, "weekOffset").also { require(it in -52..104) }.toLong())
                    .plusDays((int(due, "dayOfWeek").also { require(it in 1..7) } - 1).toLong())
            }
            StudyTaskRules.deadline(date.toString(), text(due, "time", 5))
        } else old?.dueAt ?: error("missing deadline")
        val now = System.currentTimeMillis()
        val task = StudyTask(id = old?.id ?: 0, semesterId = semester.id,
            courseId = if (name == old?.courseName) old.courseId else matching.singleOrNull()?.id,
            courseName = name, title = optional(row, "title", 120) ?: old?.title ?: error("missing title"),
            kind = optional(row, "kind", 20) ?: old?.kind ?: error("missing kind"), dueAt = dueAt,
            reminderMinutes = if (row.has("reminderMinutes")) int(row, "reminderMinutes") else old?.reminderMinutes ?: if (optional(row, "kind", 20) == "reminder") 0 else -1,
            note = optional(row, "note", 2000) ?: old?.note.orEmpty(),
            completedAt = if (row.has("completed")) { if (bool(row, "completed")) old?.completedAt ?: now else null } else old?.completedAt,
            createdAt = old?.createdAt ?: now, updatedAt = now)
        if (old == null) require(!row.has("completed"))
        StudyTaskRules.validate(task)
        StudyTaskRules.validateReminderTime(task, old, now)
        return task
    }

    fun validateChanges(changes: List<AssistantStudyChange>, semesterId: Long) {
        require(changes.size in 1..20)
        val ids = changes.mapNotNull { it.original?.id }
        require(ids.distinct().size == ids.size)
        changes.forEach { change ->
            require(change.original != null || change.after != null)
            change.original?.let { StudyTaskRules.validate(it); require(it.id > 0 && it.semesterId == semesterId) }
            change.after?.let { StudyTaskRules.validate(it); require(it.semesterId == semesterId && it.id == (change.original?.id ?: 0)) }
        }
    }

    fun validateQuery(q: AssistantStudyQuery) {
        require(q.status in setOf("pending", "completed", "all") && (q.kind == null || q.kind in StudyTaskRules.kinds))
        require(q.window in setOf("all", "week", "upcoming", "overdue", "range") && q.weekOffset in -52..104 && q.daysAhead in 0..730)
        require(q.courseName == null || q.courseName.isNotBlank() && q.courseName.length <= 120)
        require(q.title == null || q.title.isNotBlank() && q.title.length <= 120)
        if (q.window == "range") require(!LocalDate.parse(requireNotNull(q.startDate)).isAfter(LocalDate.parse(requireNotNull(q.endDate))))
        else require(q.startDate == null && q.endDate == null)
    }

    fun query(q: AssistantStudyQuery, tasks: List<StudyTask>, today: LocalDate, now: Long = System.currentTimeMillis()): List<StudyTask> {
        validateQuery(q)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).plusWeeks(q.weekOffset.toLong())
        return tasks.filter { task ->
            val date = Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
            (q.status == "all" || (task.completedAt != null) == (q.status == "completed")) &&
                (q.kind == null || task.kind == q.kind) && (q.courseName == null || task.courseName.contains(q.courseName, true)) &&
                (q.title == null || task.title.contains(q.title, true)) && when (q.window) {
                    "week" -> !date.isBefore(monday) && !date.isAfter(monday.plusDays(6))
                    "upcoming" -> !date.isBefore(today) && !date.isAfter(today.plusDays(q.daysAhead.toLong()))
                    "overdue" -> task.dueAt < now && task.completedAt == null
                    "range" -> !date.isBefore(LocalDate.parse(q.startDate)) && !date.isAfter(LocalDate.parse(q.endDate))
                    else -> true
                }
        }.sortedWith(compareBy<StudyTask> { it.dueAt }.thenBy { it.id })
    }

    fun summary(changes: List<AssistantStudyChange>) = changes.joinToString("\n\n") { change ->
        when {
            change.original == null -> "新增${if (change.after?.kind == "reminder") "提醒" else "学习事项"}\n${StudyTaskRules.describe(requireNotNull(change.after))}"
            change.after == null -> "删除学习事项\n${StudyTaskRules.describe(change.original)}"
            else -> "修改学习事项 #${change.original.id}\n原：${StudyTaskRules.describe(change.original)}\n改为：${StudyTaskRules.describe(change.after)}"
        }
    }

    private fun text(o: JsonObject, key: String, max: Int): String = o.get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString.also { value -> require(value.length <= max) }
    }
    private fun optional(o: JsonObject, key: String, max: Int) = if (o.has(key)) text(o, key, max) else null
    private fun long(o: JsonObject, key: String): Long = o.get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asBigDecimal.longValueExact()
    }
    private fun int(o: JsonObject, key: String) = long(o, key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun bool(o: JsonObject, key: String): Boolean = o.get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
    }
}
