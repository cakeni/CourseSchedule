package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.StudyTask
import java.time.LocalDate
import java.time.ZonedDateTime

/** Common one-off reminders stay local; other requests use the existing assistant protocol. */
internal object AssistantLocalReminder {
    private val marker = Regex("提醒(?:一下)?我(?:一下)?")
    private val clock = Regex("(?<![\\d.．])([0-9]{1,2})[:：.．]([0-9]{2})(?![\\d.．元])|(?<!\\d)([0-9]{1,2})(?:点|时)(半|一刻|三刻|([0-9]{1,2})分?)?(?!\\d|分)")
    private val relative = Regex("(?<![\\d.．])(?:([0-9]{1,3})小时(?:([0-9]{1,3})分钟)?|([0-9]{1,3})分钟)后")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val day = Regex("今天|明天|后天|今晚")
    private val period = Regex("早上|上午|中午|下午|晚上|凌晨|傍晚|今晚")
    private val recurring = Regex("每天|每周|每月|工作日|重复")
    private val otherAction = Regex("同时|顺便|然后|并且|删除|取消|修改|关闭|不要|不需要|不用|查看|查询|有哪些|课表")
    private val filler = Regex("那就|改成|改为|改到|吧|呀|吗")
    private val unsupportedDate = Regex("(?:下|本|这|上)周|周[一二三四五六日天]|星期|下个月|月底|[0-9]{1,2}月|[0-9]{1,2}/[0-9]{1,2}")
    private val timingToken = Regex("${datePattern.pattern}|${relative.pattern}|${clock.pattern}|${day.pattern}|${period.pattern}|${recurring.pattern}|${filler.pattern}")

    fun recognizes(text: String) = marker.containsMatchIn(text)

    private fun timeOnly(text: String) = text.isNotBlank() && text.replace(timingToken, "")
        .trim(' ', '，', ',', '。', '！', '!', '？', '?').isBlank()

    private fun chineseNumber(value: String): String {
        val digits = "零一二三四五六七八九"
        fun digit(word: String) = if (word == "两") 2 else digits.indexOf(word.singleOrNull() ?: '零').coerceAtLeast(0)
        val words = value.replace('〇', '零')
        return if ('十' in words) {
            val parts = words.split('十')
            ((if (parts[0].isEmpty()) 1 else digit(parts[0])) * 10 + (if (parts[1].isEmpty()) 0 else digit(parts[1]))).toString()
        } else words.map { character -> digit(character.toString()) }.joinToString("").toInt().toString()
    }

    private fun normalize(text: String): String {
        val halfHours = Regex("([0-9零〇一二两三四五六七八九十]{1,3})个?半小时").replace(text) { "${it.groupValues[1]}小时30分钟" }
        val normalized = Regex("([零〇一二两三四五六七八九十]{1,3})(?=点|时|分|小时)")
            .replace(halfHours.replace("半个小时", "30分钟").replace("半小时", "30分钟").replace("个小时", "小时")) { chineseNumber(it.value) }
        return Regex("(?<=[点时])[零〇一二两三四五六七八九十]{1,3}(?![零〇一二两三四五六七八九十]|刻)")
            .replace(normalized) { chineseNumber(it.value) }
    }

    private fun titleOf(request: String): String {
        val match = marker.find(request) ?: return ""
        var title = request.substring(match.range.last + 1).trim(' ', '，', ',', '。', '！', '!', '？', '?')
        // Strip timing only at the edges; event text such as “复习明天的考试” stays intact.
        while (true) {
            val token = timingToken.findAll(title).firstOrNull { it.range.first == 0 || it.range.last == title.lastIndex } ?: break
            title = title.removeRange(token.range).trim(' ', '，', ',', '。', '！', '!', '？', '?')
        }
        return title
    }

    fun followUpSource(messages: List<AssistantMessage>): String? {
        val last = messages.lastOrNull() ?: return null
        val recoverTime = last.kind in setOf("error", "reply_error") && messages.lastOrNull { it.role == "user" }?.let { timeOnly(normalize(it.content)) } == true
        if (last.kind != "reminder_question" && !recoverTime) return null
        val start = messages.indexOfLast { it.role == "user" && recognizes(it.content) }
        if (start < 0 || messages.drop(start + 1).any { it.role == "assistant" && it.kind !in setOf("reminder_question", "error", "reply_error") }) return null
        return messages.drop(start).filter { it.role == "user" }.joinToString(" ") { it.content }
    }

    fun reply(input: String, semesterId: Long, now: ZonedDateTime,
              source: String? = null, pending: AssistantCourseReply? = null): AssistantCourseReply? {
        val text = normalize(input)
        val old = pending?.studyChanges?.singleOrNull()?.takeIf {
            it.original == null && it.after?.kind == "reminder"
        }?.after
        if (pending != null && old == null) return null
        val previous = if (old != null) "提醒我${old.title}" else source?.let(::normalize)
        val suppliedTitle = !recognizes(text) && !timeOnly(text) && previous != null && titleOf(previous).isBlank() &&
            !otherAction.containsMatchIn(text)
        if (!recognizes(text) && !timeOnly(text) && !suppliedTitle) return null
        val request = if (recognizes(text)) text else previous?.let { "$it $text" } ?: return null
        // Leave compound requests to the structured protocol rather than dropping other actions.
        if (marker.findAll(request).count() > 1 || recognizes(text) && clock.findAll(request).count() > 1 ||
            otherAction.containsMatchIn(request) || unsupportedDate.containsMatchIn(request)) return null
        val match = marker.find(request) ?: return null
        if (Regex("别|勿|禁止").containsMatchIn(request.substring(0, match.range.first))) return null
        val title = if (old != null && !recognizes(text)) old.title else titleOf(request)
        fun ask(message: String) = AssistantCourseReply(message, emptyList())
        if (title.isBlank()) return ask(if (clock.containsMatchIn(request) || relative.containsMatchIn(request))
            "想提醒什么事？时间已记下，请补充提醒内容，例如：喝水。" else "想提醒什么事、在什么时间？例如：明天14:05提醒我喝水。")
        if (title.length > 120) return ask("提醒内容最多120字，请缩短后重新描述。")
        val titleIndex = request.indexOf(title, match.range.last + 1)
        val timing = if (titleIndex >= 0) request.removeRange(titleIndex, titleIndex + title.length) else request
        if (recurring.containsMatchIn(text))
            return ask("目前可以设置单次本地提醒。请告诉我这一次的日期和时间，例如：明天12:00提醒我$title。")
        val offset = relative.findAll(timing).lastOrNull()?.takeIf { it.range.first > (clock.findAll(timing).lastOrNull()?.range?.first ?: -1) &&
            it.range.first > (datePattern.findAll(timing).lastOrNull()?.range?.first ?: -1) &&
            it.range.first > (day.findAll(timing).lastOrNull()?.range?.first ?: -1) }
        val due = if (offset != null) {
            val minutes = (offset.groupValues[1].toLongOrNull() ?: 0) * 60 +
                (offset.groupValues[2].toLongOrNull() ?: offset.groupValues[3].toLongOrNull() ?: 0)
            if (minutes == 0L) return ask("请提供大于0的间隔，例如：30分钟后提醒我$title。")
            now.plusMinutes(minutes)
        } else {
            val found = clock.findAll(timing).lastOrNull()
            val oldTime = old?.let { java.time.Instant.ofEpochMilli(it.dueAt).atZone(now.zone) }
            if (found == null && oldTime == null)
                return ask("想在什么时候提醒你$title？例如：今天18:00，或30分钟后。确认保存后会安排手机通知。")
            var hour = found?.let { (it.groupValues[1].ifBlank { it.groupValues[3] }).toInt() } ?: oldTime!!.hour
            val minute = when {
                found == null -> oldTime!!.minute
                found.groupValues[2].isNotBlank() -> found.groupValues[2].toInt()
                found.groupValues[4] == "半" -> 30
                found.groupValues[4] == "一刻" -> 15
                found.groupValues[4] == "三刻" -> 45
                else -> found.groupValues[5].toIntOrNull() ?: 0
            }
            if (hour !in 0..23 || minute !in 0..59) return ask("时间无效，请使用00:00至23:59，例如：明天14:05提醒我$title。")
            val periodValue = if (clock.find(text)?.groupValues?.get(1)?.isNotBlank() == true && !period.containsMatchIn(text)) ""
                else period.findAll(timing).lastOrNull()?.value.orEmpty()
            val evening = periodValue in setOf("下午", "晚上", "今晚", "傍晚")
            if (evening && hour in 1..11) hour += 12
            if (periodValue == "凌晨" && hour == 12) hour = 0
            if (periodValue == "中午" && hour in 1..10) hour += 12
            if (found != null && found.groupValues[1].isBlank() && hour in 1..11 && periodValue.isBlank())
                return ask("你说的是上午还是晚上？请用明确时间，例如今天08:00或今天20:00，提醒你$title。")
            val explicitDate = datePattern.findAll(timing).lastOrNull()
            val relativeDay = day.findAll(timing).lastOrNull()
            val date = explicitDate?.takeIf { it.range.first > (relativeDay?.range?.first ?: -1) }?.value?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
                    ?: return ask("日期无效，请提供日期和时间，例如：明天12:00提醒我$title。")
            } ?: when {
                relativeDay?.value == "后天" -> now.toLocalDate().plusDays(2)
                relativeDay?.value == "明天" -> now.toLocalDate().plusDays(1)
                relativeDay != null -> now.toLocalDate()
                oldTime != null -> oldTime.toLocalDate()
                else -> now.toLocalDate()
            }
            val local = date.atTime(hour, minute)
            if (now.zone.rules.getValidOffsets(local).isEmpty()) return ask("这个时间不存在，请换一个明确的提醒时间。")
            local.atZone(now.zone)
        }
        if (!due.isAfter(now)) return ask("这个时间已经过去了，请换一个未来时间，例如：明天12:00提醒我$title。")
        if (due.year !in 2000..2099) return ask("提醒日期需要在2000至2099年之间，请重新提供日期和时间。")
        val task = (old ?: StudyTask(semesterId = semesterId, title = title, kind = "reminder", dueAt = 0))
            .copy(title = title, dueAt = due.toInstant().toEpochMilli(), reminderMinutes = old?.reminderMinutes ?: 0)
        return AssistantCourseReply("请核对提醒时间，确认后保存到手机。", emptyList(),
            studyChanges = listOf(AssistantStudyChange(null, task)), revisedPending = old != null)
    }
}
