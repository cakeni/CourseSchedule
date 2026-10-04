package com.courseschedule.ui.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AssistantHistorySectionsTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private fun hit(id: String, days: Long) = AssistantHistoryHit(id, id, null,
        today.minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli(), "")

    @Test fun groupsCalendarDaysWithoutDuplicatingOrDroppingRecords() {
        val hits = listOf(hit("older", 7), hit("week", 6), hit("yesterday", 1), hit("today", 0), hit("two-days", 2))
        val rows = historyRows(hits, today, zone)
        assertEquals(AssistantHistoryPeriod.values().toList(), rows.filter { it.hit == null }.map { it.period })
        assertEquals(listOf("today", "yesterday", "two-days", "week", "older"), rows.mapNotNull { it.hit?.conversationId })
        assertEquals(AssistantHistoryPeriod.WEEK, rows.first { it.hit?.conversationId == "week" }.period)
        assertEquals(AssistantHistoryPeriod.OLDER, rows.first { it.hit?.conversationId == "older" }.period)
    }

    @Test fun midnightAndEmptySectionsUseLocalCalendarBoundaries() {
        val lastNight = hit("last-night", 1).copy(createdAt = today.atStartOfDay(zone).toInstant().toEpochMilli() - 1)
        val rows = historyRows(listOf(lastNight, hit("future-clock", -1)), today, zone)
        assertEquals(listOf(AssistantHistoryPeriod.TODAY, AssistantHistoryPeriod.YESTERDAY), rows.filter { it.hit == null }.map { it.period })
        assertEquals(AssistantHistoryPeriod.YESTERDAY, rows.first { it.hit == lastNight }.period)
        assertTrue(historyRows(emptyList(), today, zone).isEmpty())
    }
}
