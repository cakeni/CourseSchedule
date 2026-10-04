package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.StudyTask
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Common one-off reminders stay local; other requests use the existing assistant protocol. */
internal object AssistantLocalReminder {
    private val marker = Regex("提醒(?:一下)?我(?:一下)?")
    private val clock = Regex("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)|(?<!\\d)([01]?\\d|2[0-3])(?:点|时)(半|([0-5]?\\d)分?)?(?!\\d|分)")
    private val relative = Regex("(\\d{1,3})(分钟|小时)后")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun recognizes(text: String) = marker.containsMatchIn(text)

    private fun timeOnly(text: String) = text.replace(datePattern, "").replace(relative, "").replace(clock, "")
        .replace(Regex("今天|明天|后天|今晚|早上|上午|中午|下午|晚上|凌晨|傍晚|那就|改成|改为|改到|在|吧"), "")
        .trim(' ', '，', ',', '。', '！', '!', '？', '?').isBlank()

    private fun normalize(text: String) = Regex("([零〇一二两三四五六七八九十]{1,3})(?=点|时|分钟|小时)").replace(text.replace("半小时", "30分钟").replace("个小时", "小时")) {
        val digits = "零一二三四五六七八九"
        fun digit(word: String) = if (word == "两") 2 else digits.indexOf(word.singleOrNull() ?: '零').coerceAtLeast(0)
        val words = it.groupValues[1].replace('〇', '零')
        if ('十' in words) {
            val parts = words.split('十')
            ((if (parts[0].isEmpty()) 1 else digit(parts[0])) * 10 + (if (parts[1].isEmpty()) 0 else digit(parts[1]))).toString()
        } else digit(words).toString()
    }

    fun reply(input: String, semesterId: Long, now: ZonedDateTime,
              source: String? = null, pending: AssistantCourseReply? = null): AssistantCourseReply? {
        val text = normalize(input)
        val old = pending?.studyChanges?.singleOrNull()?.takeIf {
            it.original == null && it.after?.kind == "reminder"
        }?.after
        if (pending != null && old == null) return null
        if (!recognizes(text) && !timeOnly(text)) return null
        val request = if (recognizes(text)) text else source?.let(::normalize) ?: if (old != null) "提醒我${old.title}" else return null
        // Leave compound requests to the structured protocol rather than dropping other actions.
        if (marker.findAll(request).count() > 1 || recognizes(text) && clock.findAll(request).count() > 1 ||
            Regex("同时|顺便|然后|并且|删除|取消|修改|关闭|不要|不需要|不用").containsMatchIn(request)) return null
        val match = marker.find(request) ?: return null
        if (Regex("别(?:再)?$").containsMatchIn(request.substring(0, match.range.first))) return null
        val title = request.substring(match.range.last + 1)
            .replace(datePattern, "").replace(relative, "").replace(clock, "")
            .replace(Regex("今天|明天|后天|今晚|早上|上午|中午|下午|晚上|凌晨|傍晚|每天|每周|每月|工作日|重复"), "")
            .trim(' ', '，', ',', '。', '！', '!', '？', '?', '吧', '呀', '吗')
        fun ask(message: String) = AssistantCourseReply(message, emptyList())
        if (title.isBlank()) return ask("可以设置本地提醒。你想提醒什么事、在什么时间？例如：明天12:00提醒我吃饭。")
        val timing = if (text != request) {
            // A follow-up can supply just the missing clock or date.
            val inheritedDate = if (!datePattern.containsMatchIn(text) && !Regex("今天|明天|后天|今晚").containsMatchIn(text))
                datePattern.find(request)?.value ?: Regex("今天|明天|后天|今晚").find(request)?.value.orEmpty() else ""
            val inheritedClock = if (!clock.containsMatchIn(text) && !relative.containsMatchIn(text))
                Regex("早上|上午|中午|下午|晚上|凌晨|傍晚").find(request)?.value.orEmpty() + " " + clock.find(request)?.value.orEmpty() else ""
            "$text $inheritedDate $inheritedClock"
        } else request
        if (Regex("每天|每周|每月|工作日|重复").containsMatchIn(text))
            return ask("目前可以设置单次本地提醒。请告诉我这一次的日期和时间，例如：明天12:00提醒我$title。")
        val offset = relative.find(timing)
        val due = if (offset != null) {
            val amount = offset.groupValues[1].toLong()
            if (amount == 0L) return ask("请提供大于0的间隔，例如：30分钟后提醒我$title。")
            if (offset.groupValues[2] == "小时") now.plusHours(amount) else now.plusMinutes(amount)
        } else {
            val found = clock.find(timing)
                ?: return ask("可以，我会用手机通知提醒你$title。想在什么时候提醒？例如：今天18:00，或30分钟后。")
            var hour = (found.groupValues[1].ifBlank { found.groupValues[3] }).toInt()
            val minute = when {
                found.groupValues[2].isNotBlank() -> found.groupValues[2].toInt()
                found.groupValues[4] == "半" -> 30
                else -> found.groupValues[5].toIntOrNull() ?: 0
            }
            val evening = Regex("下午|晚上|今晚|傍晚").containsMatchIn(timing)
            if (evening && hour in 1..11) hour += 12
            if (Regex("凌晨").containsMatchIn(timing) && hour == 12) hour = 0
            if (Regex("中午").containsMatchIn(timing) && hour in 1..10) hour += 12
            if (found.groupValues[1].isBlank() && hour in 1..11 &&
                !Regex("早上|上午|凌晨|中午|下午|晚上|今晚|傍晚").containsMatchIn(timing))
                return ask("你说的是上午还是晚上？请用明确时间，例如今天08:00或今天20:00，提醒你$title。")
            val date = datePattern.find(timing)?.value?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
                    ?: return ask("日期无效，请提供日期和时间，例如：明天12:00提醒我$title。")
            } ?: when {
                timing.contains("后天") -> now.toLocalDate().plusDays(2)
                timing.contains("明天") -> now.toLocalDate().plusDays(1)
                Regex("今天|今晚").containsMatchIn(timing) -> now.toLocalDate()
                old != null -> java.time.Instant.ofEpochMilli(old.dueAt).atZone(now.zone).toLocalDate()
                else -> return ask("几点已记下，是今天还是明天？例如：明天${LocalTime.of(hour, minute)}提醒我$title。")
            }
            val local = date.atTime(hour, minute)
            if (now.zone.rules.getValidOffsets(local).isEmpty()) return ask("这个时间不存在，请换一个明确的提醒时间。")
            local.atZone(now.zone)
        }
        if (!due.isAfter(now)) return ask("这个时间已经过去了，请换一个未来时间，例如：明天12:00提醒我$title。")
        val task = (old ?: StudyTask(semesterId = semesterId, title = title, kind = "reminder", dueAt = 0))
            .copy(title = title, dueAt = due.toInstant().toEpochMilli(), reminderMinutes = 0)
        return AssistantCourseReply("请核对提醒时间，确认后保存到手机。", emptyList(),
            studyChanges = listOf(AssistantStudyChange(null, task)), revisedPending = old != null)
    }
}
