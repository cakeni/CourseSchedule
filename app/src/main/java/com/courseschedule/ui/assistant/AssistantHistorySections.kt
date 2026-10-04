package com.courseschedule.ui.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class AssistantHistoryPeriod { TODAY, YESTERDAY, WEEK, OLDER }
internal data class AssistantHistoryRow(val period: AssistantHistoryPeriod, val hit: AssistantHistoryHit? = null)

internal fun historyRows(hits: List<AssistantHistoryHit>, today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()): List<AssistantHistoryRow> {
    val grouped = hits.sortedWith(compareByDescending<AssistantHistoryHit> { it.createdAt }
        .thenByDescending { it.messageId ?: 0 }).groupBy {
        val date = Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate()
        when {
            !date.isBefore(today) -> AssistantHistoryPeriod.TODAY
            date == today.minusDays(1) -> AssistantHistoryPeriod.YESTERDAY
            !date.isBefore(today.minusDays(6)) -> AssistantHistoryPeriod.WEEK
            else -> AssistantHistoryPeriod.OLDER
        }
    }
    return buildList {
        AssistantHistoryPeriod.values().forEach { period -> grouped[period]?.let { rows ->
            add(AssistantHistoryRow(period))
            rows.forEach { add(AssistantHistoryRow(period, it)) }
        } }
    }
}
