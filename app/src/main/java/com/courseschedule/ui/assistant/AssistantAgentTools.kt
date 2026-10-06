package com.courseschedule.ui.assistant

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** The available functions are the capability boundary for this conversation state. */
internal object AssistantAgentTools {
    data class Tool(val name: String, val action: String, val description: String, val parameters: JsonObject)

    private fun text(limit: Int = 120) = JsonObject().apply { addProperty("type", "string"); addProperty("maxLength", limit) }
    private fun integer(min: Int, max: Int) = JsonObject().apply {
        addProperty("type", "integer"); addProperty("minimum", min); addProperty("maximum", max)
    }
    private val id get() = JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 1) }
    private val flag get() = JsonObject().apply { addProperty("type", "boolean") }
    private fun options(vararg values: String) = text().apply { add("enum", JsonArray().apply { values.forEach { add(it) } }) }
    private fun obj(properties: Map<String, JsonElement>, vararg required: String) = JsonObject().apply {
        addProperty("type", "object"); addProperty("additionalProperties", false)
        add("properties", JsonObject().apply { properties.forEach { (key, value) -> add(key, value) } })
        add("required", JsonArray().apply { required.forEach { add(it) } })
    }
    private fun rows(item: JsonObject, minimum: Int = 0, maximum: Int = 20) = JsonObject().apply {
        addProperty("type", "array"); add("items", item); addProperty("minItems", minimum); addProperty("maxItems", maximum)
    }
    private val dates get() = linkedMapOf<String, JsonElement>("date" to text(10), "dayOffset" to integer(-365, 365),
        "week" to integer(1, 52), "weekOffset" to integer(-52, 52), "useDisplayedWeek" to flag)
    private val courseFields get() = linkedMapOf<String, JsonElement>("courseName" to text(), "teacher" to text(80),
        "classroom" to text(), "dayOfWeek" to integer(1, 7), "startSection" to integer(1, 12), "endSection" to integer(1, 12).apply {
            addProperty("description", "新增课程必填。已有课程修改、移动或补课只指定开始节次时须省略，保留原课时长；仅用户明确更改结束节次或时长时填写。") },
        "weeks" to rows(integer(1, 52), 1, 52), "note" to text(500), "reminderMinutes" to integer(-1, 1440))
    private val query get() = obj(mapOf("courseName" to text(), "teacher" to text(), "classroom" to text(),
        "dayOfWeek" to integer(1, 7), "startSection" to integer(1, 12), "endSection" to integer(1, 12),
        "whenTo" to obj(dates).apply { addProperty("description", "今天/明天/后天用dayOffset:0/1/2，明确日期用date，由本地计算星期；不要用weekOffset表达单日。本周/下周按星期查询才用weekOffset配合dayOfWeek。") }, "freeSlots" to flag))
    private val taskFields get() = linkedMapOf<String, JsonElement>("title" to text(),
        "kind" to options("homework", "exam", "report", "reminder"), "courseName" to text(),
        "due" to obj(mapOf("date" to text(10), "dayOffset" to integer(-366, 730), "weekOffset" to integer(-52, 104),
            "dayOfWeek" to integer(1, 7), "time" to text(5)), "time"),
        "reminderMinutes" to integer(-1, 10080), "note" to text(2000), "completed" to flag)
    private val taskIdentity get() = mapOf<String, JsonElement>("id" to id, "targetTitle" to text(),
        "targetDueDate" to text(10), "targetIdExplicit" to flag)

    private val registry: List<Tool> by lazy { listOf(
        Tool("query_courses", "query", "按条件查询真实课程或空闲节次。只读，结果由本地生成。", obj(mapOf("query" to query), "query")),
        Tool("propose_course_changes", "change", "提出课程新增、修改、删除或单次调课方案，必须用户确认后保存。单一原目标同时提供targetQuery。",
            obj(mapOf("courses" to rows(obj(courseFields, "courseName", "teacher", "classroom", "dayOfWeek", "startSection", "endSection", "weeks")),
                "updates" to rows(obj(courseFields + ("id" to id), "id")), "deleteIds" to rows(id), "targetQuery" to query,
                "occurrences" to rows(obj(mapOf("id" to id, "operation" to options("cancel", "move", "update", "copy"),
                    "source" to obj(dates), "target" to obj(dates + courseFields.filterKeys { it in setOf("dayOfWeek", "startSection", "endSection", "classroom", "teacher", "note", "reminderMinutes") })),
                    "id", "operation", "source"))))),
        Tool("choose_course_target", "clarify", "同名课程目标含糊时提供原目标条件，由本地列出候选让用户选择。", obj(mapOf("targetQuery" to query), "targetQuery")),
        Tool("revise_course_plan", "revise", "只修正当前待确认课程方案。index来自方案顺序，不能改变原目标。",
            obj(mapOf("revisions" to rows(obj(courseFields + ("index" to integer(0, 199)), "index"), 1)), "revisions")),
        Tool("undo_course_changes", "undo", "用户明确要求撤销时恢复最近一次成功课程操作；不能用于聊天或学习事项。", obj(emptyMap())),
        Tool("query_study_tasks", "task_query", "查询真实作业、考试、报告或生活提醒，结果由本地生成。",
            obj(mapOf("taskQuery" to obj(mapOf("status" to options("pending", "completed", "all"),
                "kind" to options("homework", "exam", "report", "reminder"), "courseName" to text(), "title" to text(),
                "window" to options("all", "week", "upcoming", "overdue", "range"), "weekOffset" to integer(-52, 104),
                "daysAhead" to integer(0, 730), "startDate" to text(10), "endDate" to text(10)))), "taskQuery")),
        Tool("propose_study_changes", "task_change", "提出作业、考试、报告或单次生活提醒方案。时间不明确先追问，不调用工具。",
            obj(mapOf("taskCreates" to rows(obj(taskFields.filterKeys { it != "completed" }, "title", "kind", "due")),
                "taskUpdates" to rows(obj(taskFields + taskIdentity, "id", "targetTitle")),
                "taskDeletes" to rows(obj(taskIdentity, "id", "targetTitle"))))),
        Tool("revise_study_plan", "task_revise", "只修正当前待确认事项，index来自方案顺序，不改变原目标。",
            obj(mapOf("taskRevisions" to rows(obj(taskFields + ("index" to integer(0, 19)), "index"), 1)), "taskRevisions")),
        Tool("cancel_pending_plan", "cancel", "仅在用户明确要求取消当前待确认方案时调用。不删除已经保存的数据。", obj(emptyMap()))
    ) }

    fun available(pending: AssistantCourseReply?, canUndo: Boolean, selectedTarget: Boolean = false, awaitingChoice: Boolean = false): List<Tool> {
        val readOnly = setOf("query_courses", "query_study_tasks")
        val names = when {
            pending?.studyChanges?.isNotEmpty() == true -> readOnly + setOf("cancel_pending_plan") +
                if (pending.studyChanges.any { it.after != null }) setOf("revise_study_plan") else emptySet()
            pending != null -> readOnly + setOf("cancel_pending_plan") +
                if (pending.courses.isNotEmpty() || pending.updates.any { it.replacements.isNotEmpty() }) setOf("revise_course_plan") else emptySet()
            else -> setOf("query_courses", "propose_course_changes", "query_study_tasks", "propose_study_changes") +
                (if (!selectedTarget) setOf("choose_course_target") else emptySet()) +
                (if (canUndo) setOf("undo_course_changes") else emptySet()) +
                (if (awaitingChoice) setOf("cancel_pending_plan") else emptySet())
        }
        return registry.filter { it.name in names }
    }

    fun definitions(pending: AssistantCourseReply?, canUndo: Boolean, selectedTarget: Boolean = false, awaitingChoice: Boolean = false) = JsonArray().apply {
        available(pending, canUndo, selectedTarget, awaitingChoice).forEach { tool -> add(JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply { addProperty("name", tool.name); addProperty("description", tool.description)
                add("parameters", tool.parameters.deepCopy()) })
        }) }
    }

    fun operation(name: String, arguments: JsonObject): JsonObject {
        val tool = registry.find { it.name == name } ?: throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "服务调用了不支持的功能，本次未执行。")
        validate(arguments, tool.parameters)
        return arguments.deepCopy().apply {
            addProperty("version", 1); addProperty("action", tool.action)
            addProperty("reply", when (tool.action) { "query", "task_query" -> "查询真实安排。"; "undo" -> "撤销最近一次课程操作。"
                "cancel" -> "取消待确认方案。"; "clarify" -> "请选择要操作的课程。"; else -> "请核对方案，确认后保存。" })
            if (tool.action == "undo") addProperty("undo", true)
        }
    }

    fun action(name: String) = registry.first { it.name == name }.action

    private fun validate(value: JsonElement, schema: JsonObject, path: String = "参数") {
        fun check(condition: Boolean, reason: String) {
            if (!condition) throw AssistantAgentFailure(AssistantFailureKind.PROTOCOL, "工具参数未通过校验：$path$reason。本次未执行。")
        }
        check(!value.isJsonNull, "不能为空")
        when (schema.get("type").asString) {
            "object" -> {
                check(value.isJsonObject, "必须是对象")
                val objectValue = value.asJsonObject
                val properties = schema.getAsJsonObject("properties")
                check(objectValue.keySet().all { properties.has(it) }, "包含不支持的字段")
                val missing = schema.getAsJsonArray("required").filter { !objectValue.has(it.asString) }.map { it.asString }
                check(missing.isEmpty(), "缺少${missing.joinToString("、")}")
                objectValue.entrySet().forEach { validate(it.value, properties.getAsJsonObject(it.key), "$path.${it.key}") }
            }
            "array" -> {
                check(value.isJsonArray, "必须是数组")
                check(value.asJsonArray.size() in schema.get("minItems").asInt..schema.get("maxItems").asInt, "条目数量超出范围")
                value.asJsonArray.forEachIndexed { index, item -> validate(item, schema.getAsJsonObject("items"), "$path[$index]") }
            }
            "string" -> {
                check(value.isJsonPrimitive && value.asJsonPrimitive.isString, "必须是文字")
                check(value.asString.length <= schema.get("maxLength").asInt, "文字过长")
                if (schema.has("enum")) check(schema.getAsJsonArray("enum").any { it == value }, "不在允许选项中")
            }
            "integer" -> {
                check(value.isJsonPrimitive && value.asJsonPrimitive.isNumber, "必须是整数")
                val number = runCatching { value.asBigDecimal.longValueExact() }.getOrNull()
                check(number != null, "必须是有效整数")
                check(number!! >= schema.get("minimum").asLong && (!schema.has("maximum") || number <= schema.get("maximum").asLong), "数值超出范围")
            }
            "boolean" -> check(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean, "必须是布尔值")
            else -> error("Unsupported schema")
        }
    }
}
