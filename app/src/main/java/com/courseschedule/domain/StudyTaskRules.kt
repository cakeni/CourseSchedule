package com.courseschedule.domain

import com.courseschedule.data.entity.StudyTask
import java.time.*
import java.time.format.DateTimeFormatter

object StudyTaskRules {
    val kinds = linkedMapOf("homework" to "作业", "exam" to "考试", "report" to "报告", "reminder" to "提醒")
    val reminders = linkedMapOf(-1 to "不提醒", 0 to "截止时", 30 to "提前30分钟", 60 to "提前1小时",
        1440 to "提前1天", 4320 to "提前3天")
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun validate(task: StudyTask) {
        require(task.semesterId > 0 && task.id >= 0 && (task.courseId == null || task.courseId > 0)) { "学习事项关联无效。" }
        require(task.title.isNotBlank() && task.title.length <= 120 && task.courseName.length <= 120 && task.note.length <= 2000) { "请填写1–120字的标题，备注最多2000字。" }
        require(task.kind in kinds && task.reminderMinutes in -1..10080) { "事项类型或提醒时间无效。" }
        require(task.dueAt in 946684800000L..4102444799999L && (task.completedAt == null || task.completedAt > 0)) { "截止时间须在2000–2099年之间。" }
    }

    fun validateReminderTime(task: StudyTask, old: StudyTask? = null, now: Long = System.currentTimeMillis()) {
        if (task.kind == "reminder" && task.completedAt == null &&
            (old == null || old.kind != "reminder" || task.dueAt != old.dueAt))
            require(task.dueAt > now) { "提醒时间已经过去，请选择未来时间。" }
    }

    fun deadline(date: String, time: String, zone: ZoneId = ZoneId.systemDefault()): Long = try {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && Regex("\\d{2}:\\d{2}").matches(time)) { "请提供明确的截止日期和时间。" }
        val local = LocalDate.parse(date).atTime(LocalTime.parse(time))
        require(zone.rules.getValidOffsets(local).isNotEmpty()) { "该时间因夏令时调整不存在，请选择其他时间。" }
        local.atZone(zone).toInstant().toEpochMilli()
    } catch (error: DateTimeException) { throw IllegalArgumentException("截止日期或时间无效。", error) }

    fun describe(task: StudyTask): String = buildString {
        append("${kinds[task.kind]} · ${task.title}\n")
        if (task.courseName.isNotBlank()) append("${task.courseName} · ")
        append(Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).format(format))
        append("\n${if (task.completedAt != null) "已完成" else if (task.kind == "reminder") "待提醒" else "待完成"} · ${if (task.kind == "reminder" && task.reminderMinutes == 0) "准时提醒" else reminders[task.reminderMinutes] ?: "提前${task.reminderMinutes}分钟"}")
        if (task.note.isNotBlank()) append("\n${task.note}")
    }

    fun trigger(task: StudyTask, now: Long): Long? {
        if (task.completedAt != null || task.reminderMinutes < 0 || task.dueAt < now) return null
        // If the chosen lead time has passed, deliver once now, while the deadline is still valid.
        return maxOf(now + 1000L, task.dueAt - task.reminderMinutes * 60_000L)
    }
}
