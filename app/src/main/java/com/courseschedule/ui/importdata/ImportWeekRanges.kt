package com.courseschedule.ui.importdata

internal data class ImportWeekRange(val start: Int, val end: Int, val weekType: Int)

/** Represent exactly the supplied weeks, never filling gaps between runs. */
internal fun compressImportWeeks(weeks: List<Int>): List<ImportWeekRange> {
    val sorted = weeks.distinct().sorted()
    val groups = mutableListOf<ImportWeekRange>()
    var index = 0
    while (index < sorted.size) {
        val start = sorted[index]
        val step = if (index + 1 < sorted.size && sorted[index + 1] - start == 2) 2 else 1
        var endIndex = index
        while (endIndex + 1 < sorted.size && sorted[endIndex + 1] - sorted[endIndex] == step) endIndex++
        groups += ImportWeekRange(start, sorted[endIndex], if (step == 2) {
            if (start % 2 == 0) 2 else 1
        } else 0)
        index = endIndex + 1
    }
    return groups
}
