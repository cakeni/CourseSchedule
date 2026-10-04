package com.courseschedule.domain

enum class WeekMotionStyle(val storedValue: String) {
    SOFT_SLIDE("soft_slide"),
    CONTINUITY("continuity");

    companion object {
        fun fromStoredValue(value: String?): WeekMotionStyle =
            entries.firstOrNull { it.storedValue == value } ?: SOFT_SLIDE
    }
}
