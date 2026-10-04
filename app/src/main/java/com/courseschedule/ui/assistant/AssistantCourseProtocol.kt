package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.importdata.compressImportWeeks
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDate

/** Domain validation is shared by native tools and the legacy service adapter. */
internal object AssistantCourseProtocol {
    fun parseOperation(input: JsonObject, totalWeeks: Int, existingCourses: List<Course> = emptyList(),
        semester: Semester? = null, displayedWeek: Int = 1, pending: AssistantCourseReply? = null,
        today: LocalDate = LocalDate.now(), selectedTarget: Course? = null,
        studyTasks: List<StudyTask> = emptyList(), nativeTool: Boolean = false): AssistantCourseReply {
        var failureMessage = "课程安排未通过本地校验，可能缺少必要信息或原目标已变化。本次未更改，请补充明确的目标和安排。"
        return try {
            val root = AssistantStudyProtocol.normalizeEnvelope(input)
            if (root.get("action")?.asString?.startsWith("task_") == true) {
                failureMessage = "API 返回的提醒或事项格式不完整，或目标、日期、时间无效，本次未保存。请补充明确的事项和未来时间后重试。"
                return AssistantStudyProtocol.parse(root, requireNotNull(semester), existingCourses, studyTasks, pending, today)
            }
            require(root.keySet().all { it in setOf("version", "action", "reply", "courses", "updates",
                "deleteIds", "queryIds", "undo", "query", "occurrences", "revisions", "targetQuery") })
            if (root.has("version") || root.has("action")) {
                require(integer(root, "version") == 1)
                require(string(root, "action", 20) in setOf("chat", "clarify", "query", "change", "undo", "revise"))
            }
            val reply = string(root, "reply", 4000)
            require(reply.isNotBlank())
            val rows = root.getAsJsonArray("courses") ?: JsonArray()
            val updateRows = root.getAsJsonArray("updates") ?: JsonArray()
            val byId = existingCourses.associateBy { it.id }
            fun selected(name: String, limit: Int): List<Course> {
                val ids = (root.getAsJsonArray(name) ?: JsonArray()).map { id ->
                    require(id.isJsonPrimitive && id.asJsonPrimitive.isNumber)
                    id.asBigDecimal.longValueExact().also { require(it > 0 && it in byId) }
                }
                require(ids.size <= limit && ids.distinct().size == ids.size)
                return ids.map { byId.getValue(it) }
            }
            val deletions = selected("deleteIds", 20)
            val queries = selected("queryIds", 200)
            require(rows.size() + updateRows.size() + deletions.size <= 20)
            val courses = mutableListOf<Course>()
            val additionReminders = mutableMapOf<Int, Int>()
            rows.forEach { value ->
                val row = value.asJsonObject
                readCourse(row, totalWeeks).forEach { course ->
                    if (row.has("reminderMinutes")) additionReminders[courses.size] = course.reminderMinutes
                    courses += course
                }
            }
            val allowed = setOf("id", "courseName", "teacher", "classroom", "dayOfWeek",
                "startSection", "endSection", "weeks", "note", "reminderMinutes")
            val updates = updateRows.map { value ->
                val row = value.asJsonObject
                require(row.keySet().size >= 2 && row.keySet().all { it in allowed })
                val id = row.get("id")
                require(id != null && id.isJsonPrimitive && id.asJsonPrimitive.isNumber)
                val original = byId[id.asBigDecimal.longValueExact()] ?: error("unknown course")
                AssistantCourseUpdate(original, readCourse(row, totalWeeks, original))
            }.toMutableList()
            val allDeletions = deletions.toMutableList()
            val occurrenceRows = root.getAsJsonArray("occurrences") ?: JsonArray()
            require(rows.size() + updateRows.size() + deletions.size + occurrenceRows.size() <= 20)
            val scopeNotes = mutableListOf<String>()
            occurrenceRows.forEach { value ->
                val row = value.asJsonObject
                require(row.keySet().all { it in setOf("id", "operation", "source", "target") })
                val id = row.get("id")
                require(id != null && id.isJsonPrimitive && id.asJsonPrimitive.isNumber)
                val original = byId[id.asBigDecimal.longValueExact()] ?: error("unknown course")
                val operation = string(row, "operation", 20)
                require(operation in setOf("cancel", "move", "update", "copy"))
                val currentSemester = requireNotNull(semester) { "单次调课需要学期信息。" }
                require(original.semesterId == currentSemester.id)
                val source = AssistantScheduleOperations.resolve(readSelection(row.getAsJsonObject("source")),
                    currentSemester, displayedWeek, today)
                val sourceWeek = requireNotNull(source.week)
                require((source.dayOfWeek == null || source.dayOfWeek == original.dayOfWeek) &&
                    ScheduleRules.isCourseInWeek(original, sourceWeek))
                val remainingWeeks = (original.startWeek..original.endWeek)
                    .filter { it != sourceWeek && ScheduleRules.isCourseInWeek(original, it) }
                val replacements = mutableListOf<Course>()
                if (operation != "copy") replacements += withWeeks(original, remainingWeeks)
                if (operation == "cancel") require(!row.has("target")) else {
                    val target = row.getAsJsonObject("target")
                    val dateFields = setOf("date", "dayOffset", "week", "weekOffset", "useDisplayedWeek")
                    require(target.keySet().all { it in dateFields || it in setOf("dayOfWeek", "startSection",
                        "endSection", "classroom", "teacher", "note", "reminderMinutes") })
                    val dateJson = JsonObject().apply { target.entrySet().filter { it.key in dateFields }.forEach { add(it.key, it.value) } }
                    val resolved = AssistantScheduleOperations.resolve(readSelection(dateJson), currentSemester, displayedWeek, today)
                    val targetWeek = resolved.week ?: sourceWeek
                    val targetDay = if (target.has("dayOfWeek")) integer(target, "dayOfWeek") else resolved.dayOfWeek ?: original.dayOfWeek
                    require(resolved.dayOfWeek == null || resolved.dayOfWeek == targetDay)
                    val patch = target.deepCopy().apply {
                        dateFields.forEach { remove(it) }
                        addProperty("dayOfWeek", targetDay)
                        add("weeks", JsonArray().apply { add(targetWeek) })
                        if (has("startSection") && !has("endSection")) addProperty("endSection",
                            integer(this, "startSection") + original.endSection - original.startSection)
                    }
                    if (operation == "update") require(targetWeek == sourceWeek && targetDay == original.dayOfWeek)
                    val moved = readCourse(patch, totalWeeks, original).single()
                    if (operation == "copy") {
                        additionReminders[courses.size] = moved.reminderMinutes
                        courses += moved.copy(id = 0)
                    } else replacements += moved
                }
                if (operation != "copy") {
                    if (replacements.isEmpty()) allDeletions += original else updates += AssistantCourseUpdate(original,
                        replacements.mapIndexed { index, course -> course.copy(id = if (index == 0) original.id else 0) })
                }
                val verb = when (operation) { "cancel" -> "取消"; "move" -> "移动"; "copy" -> "补课（原课保留）"; else -> "修改" }
                scopeNotes += "$verb：${original.courseName} · 仅第${sourceWeek}周周${"一二三四五六日"[original.dayOfWeek - 1]}的一次课，其他周保留。"
            }
            val changedIds = updates.map { it.original.id } + allDeletions.map { it.id }
            require(changedIds.distinct().size == changedIds.size)
            val query = if (root.has("query")) readQuery(root.getAsJsonObject("query")) else null
            require(query == null || queries.isEmpty())
            require((queries.isEmpty() && query == null) || (courses.isEmpty() && changedIds.isEmpty()))
            val undo = root.get("undo")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
                it.asBoolean
            } ?: false
            require(!undo || (courses.isEmpty() && changedIds.isEmpty() && queries.isEmpty() && query == null))
            require(courses.size + updates.sumOf { it.replacements.size } <= AssistantScheduleOperations.MAX_RECORDS)
            val inferred = when {
                root.has("revisions") -> "revise"
                undo -> "undo"
                query != null || queries.isNotEmpty() || root.get("action")?.asString == "query" -> "query"
                courses.isNotEmpty() || changedIds.isNotEmpty() -> "change"
                else -> "chat"
            }
            val action = root.get("action")?.asString ?: inferred
            require(action == inferred || (inferred == "chat" && action == "clarify"))
            if (action == "query") require(courses.isEmpty() && changedIds.isEmpty() && !undo && occurrenceRows.size() == 0)
            if (root.has("version") && action == "query") require(query != null && queries.isEmpty())
            if (query != null && semester != null) {
                try {
                    if (query.freeSlots) AssistantScheduleOperations.freeSections(query, existingCourses, semester, displayedWeek, 12, today)
                    else AssistantScheduleOperations.query(query, existingCourses, semester, displayedWeek, today)
                } catch (error: IllegalArgumentException) {
                    throw AssistantAgentFailure(AssistantFailureKind.OPERATION, error.message ?: "查询日期或范围无效，本次未修改数据。")
                }
            }
            if (pending != null) require(action in if (pending.studyChanges.isNullOrEmpty()) setOf("chat", "clarify", "query", "revise") else setOf("chat", "clarify", "query"))
            val sources = (updates.map { it.original } + deletions + occurrenceRows.map {
                byId.getValue(it.asJsonObject.get("id").asBigDecimal.longValueExact())
            }).distinctBy { it.id }
            val targetQuery = if (root.has("targetQuery")) readQuery(root.getAsJsonObject("targetQuery")) else null
            if (nativeTool && action == "change" && sources.size == 1 && selectedTarget == null && targetQuery == null)
                throw AssistantAgentFailure(AssistantFailureKind.OPERATION, "缺少原课程的定位条件targetQuery，本次未执行。请按原课名和原安排确定目标。")
            if (targetQuery != null) require(((action == "change" && sources.size == 1) || (action == "clarify" && sources.isEmpty())) &&
                !targetQuery.freeSlots && !targetQuery.courseName.isNullOrBlank())
            if (selectedTarget != null && action == "change") {
                require(byId[selectedTarget.id] == selectedTarget && sources.size == 1 && sources.single() == selectedTarget && rows.size() == 0)
            }
            val ambiguous = if (action == "clarify" && targetQuery != null && selectedTarget == null) {
                AssistantScheduleOperations.query(targetQuery, existingCourses, requireNotNull(semester), displayedWeek, today).takeIf { it.size > 1 }
            } else if (action == "change" && selectedTarget == null) sources.mapNotNull { source ->
                val sameName = existingCourses.filter { it.semesterId == source.semesterId && it.courseName == source.courseName }
                val candidates = if (targetQuery != null) AssistantScheduleOperations.query(targetQuery, sameName,
                    requireNotNull(semester), displayedWeek, today) else sameName
                require(source in candidates)
                candidates.takeIf { it.size > 1 }
            }.firstOrNull() else null
            if (action == "revise") {
                require(courses.isEmpty() && changedIds.isEmpty() && !undo && query == null && queries.isEmpty())
                revise(requireNotNull(pending), root.getAsJsonArray("revisions"), reply, totalWeeks)
            } else if (ambiguous != null) {
                require(sources.size <= 1 && rows.size() == 0 && ambiguous.size <= 200)
                AssistantCourseReply("有多个符合条件的课程安排，请先选择要操作的一条。", emptyList(),
                    targetCandidates = ambiguous)
            } else {
                require(action != "query" || query != null || root.has("queryIds"))
                AssistantCourseReply(reply, courses, updates, allDeletions, queries, undo, additionReminders.toMap(),
                    query = query, scopeNotes = scopeNotes.takeIf { it.isNotEmpty() }, queryRequested = action == "query")
            }
        } catch (error: AssistantAgentFailure) { throw error }
        catch (_: Exception) {
            throw AssistantAgentFailure(AssistantFailureKind.OPERATION, failureMessage)
        }
    }

    private fun readSelection(row: JsonObject): AssistantDateSelection {
        require(row.keySet().all { it in setOf("date", "dayOffset", "week", "weekOffset", "useDisplayedWeek") })
        val selection = AssistantDateSelection(
            date = if (row.has("date")) string(row, "date", 10).also { LocalDate.parse(it) } else null,
            dayOffset = if (row.has("dayOffset")) integer(row, "dayOffset").also { require(it in -365..365) } else null,
            week = if (row.has("week")) integer(row, "week").also { require(it in 1..52) } else null,
            weekOffset = if (row.has("weekOffset")) integer(row, "weekOffset").also { require(it in -52..52) } else null,
            useDisplayedWeek = if (row.has("useDisplayedWeek")) boolean(row, "useDisplayedWeek") else false)
        require(listOf(selection.date != null, selection.dayOffset != null, selection.week != null,
            selection.weekOffset != null, selection.useDisplayedWeek).count { it } <= 1)
        return selection
    }

    private fun readQuery(row: JsonObject): AssistantCourseQuery {
        require(row.keySet().all { it in setOf("courseName", "teacher", "classroom", "dayOfWeek",
            "startSection", "endSection", "whenTo", "freeSlots") })
        fun filter(name: String) = if (row.has(name)) string(row, name, 120).also { require(it.isNotBlank()) } else null
        val start = if (row.has("startSection")) integer(row, "startSection").also { require(it in 1..12) } else null
        val end = if (row.has("endSection")) integer(row, "endSection").also { require(it in (start ?: 1)..12) } else null
        return AssistantCourseQuery(filter("courseName"), filter("teacher"), filter("classroom"),
            if (row.has("dayOfWeek")) integer(row, "dayOfWeek").also { require(it in 1..7) } else null,
            start, end, if (row.has("whenTo")) readSelection(row.getAsJsonObject("whenTo")) else AssistantDateSelection(),
            if (row.has("freeSlots")) boolean(row, "freeSlots") else false)
    }

    private fun withWeeks(course: Course, weeks: List<Int>): List<Course> = compressImportWeeks(weeks).map { range ->
        course.copy(startWeek = range.start, endWeek = range.end, weekType = range.weekType)
    }

    private fun revise(pending: AssistantCourseReply, patches: JsonArray, reply: String, totalWeeks: Int): AssistantCourseReply {
        val flat = pending.courses + pending.updates.flatMap { it.replacements }
        require(patches.size() in 1..20)
        val changes = patches.map { value ->
            val row = value.asJsonObject
            val index = integer(row, "index")
            require(index in flat.indices && row.keySet().size >= 2 && !row.has("id"))
            index to readCourse(row.deepCopy().apply {
                remove("index")
                if (has("startSection") && !has("endSection")) addProperty("endSection",
                    integer(this, "startSection") + flat[index].endSection - flat[index].startSection)
            }, totalWeeks, flat[index])
        }
        require(changes.map { it.first }.distinct().size == changes.size)
        val byIndex = changes.toMap()
        var index = 0
        fun replace(rows: List<Course>): List<Course> = rows.flatMap { row -> byIndex[index++] ?: listOf(row) }
        val courses = replace(pending.courses)
        val updates = pending.updates.map { it.copy(replacements = replace(it.replacements)) }
        require(courses.size + updates.sumOf { it.replacements.size } <= AssistantScheduleOperations.MAX_RECORDS)
        return pending.copy(reply = reply, courses = courses, updates = updates, revisedPending = true,
            confirmationRequired = true, scopeNotes = null,
            additionReminders = courses.mapIndexed { i, course -> i to course.reminderMinutes }.toMap())
    }

    private fun readCourse(row: JsonObject, totalWeeks: Int, original: Course? = null): List<Course> {
        require(row.keySet().all { it in setOf("courseName", "teacher", "classroom", "dayOfWeek",
            "startSection", "endSection", "weeks", "note", "reminderMinutes") || (original != null && it == "id") })
        fun text(name: String, limit: Int, old: String?) =
            if (!row.has(name) && old != null) old else string(row, name, limit)
        fun number(name: String, old: Int?) =
            if (!row.has(name) && old != null) old else integer(row, name)
        val name = text("courseName", 120, original?.courseName)
        require(name.isNotBlank())
        val day = number("dayOfWeek", original?.dayOfWeek)
        val start = number("startSection", original?.startSection)
        val end = if (original != null && row.has("startSection") && !row.has("endSection"))
            start + original.endSection - original.startSection else number("endSection", original?.endSection)
        val weeks = if (!row.has("weeks") && original != null) {
            (original.startWeek..original.endWeek).filter { ScheduleRules.isCourseInWeek(original, it) }
        } else row.getAsJsonArray("weeks").map { week ->
            require(week.isJsonPrimitive && week.asJsonPrimitive.isNumber)
            week.asBigDecimal.intValueExact().also { require(it in 1..totalWeeks) }
        }
        require(weeks.isNotEmpty() && weeks.size <= 52)
        val teacher = text("teacher", 80, original?.teacher)
        val classroom = text("classroom", 120, original?.classroom)
        val note = text("note", 500, original?.note ?: "")
        val reminder = number("reminderMinutes", original?.reminderMinutes ?: -1)
        require(reminder in -1..1440)
        val base = original ?: Course(courseName = name, dayOfWeek = day, startSection = start,
            endSection = end, startWeek = 1, endWeek = totalWeeks)
        return compressImportWeeks(weeks).mapIndexed { index, range ->
            base.copy(id = if (index == 0) base.id else 0, courseName = name, teacher = teacher,
                classroom = classroom, dayOfWeek = day, startSection = start, endSection = end,
                startWeek = range.start, endWeek = range.end, weekType = range.weekType,
                note = note, reminderMinutes = reminder).also {
                require(ScheduleRules.isValidCourse(it, totalWeeks))
            }
        }
    }

    private fun string(row: JsonObject, name: String, limit: Int): String {
        val value = row.get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.trim().also { require(it.length <= limit) }
    }

    private fun integer(row: JsonObject, name: String): Int {
        val value = row.get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.intValueExact()
    }

    private fun boolean(row: JsonObject, name: String): Boolean {
        val value = row.get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }
}
