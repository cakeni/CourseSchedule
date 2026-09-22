package com.courseschedule.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

internal const val EXTRA_SCHEDULE_RETURN_SOURCE = "schedule_return_source"
internal enum class ScheduleReturnSource { SETTINGS, IMPORT }

internal fun AppCompatActivity.returnToSchedule(source: ScheduleReturnSource) {
    startActivity(Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(EXTRA_SCHEDULE_RETURN_SOURCE, source.name)
    })
    finish()
    overridePendingTransition(0, 0)
}
