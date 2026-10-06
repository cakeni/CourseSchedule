package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course

internal data class AssistantMessage(val role: String, val content: String,
    val kind: String = "chat", val createdAt: Long = System.currentTimeMillis(),
    val courseIds: List<Long> = emptyList(), val id: Long = 0, val imageRef: String? = null)
internal data class AssistantCourseUpdate(val original: Course, val replacements: List<Course>)
internal data class AssistantCourseReply(
    val reply: String,
    val courses: List<Course>,
    val updates: List<AssistantCourseUpdate> = emptyList(),
    val deletions: List<Course> = emptyList(),
    val queriedCourses: List<Course> = emptyList(),
    val undo: Boolean = false,
    val additionReminders: Map<Int, Int> = emptyMap(),
    val query: AssistantCourseQuery? = null,
    val confirmationRequired: Boolean = false,
    val revisedPending: Boolean = false,
    val scopeNotes: List<String>? = null,
    val queryRequested: Boolean = false,
    val targetCandidates: List<Course>? = null,
    val studyChanges: List<AssistantStudyChange>? = null,
    val studyQuery: AssistantStudyQuery? = null,
    val cancelPending: Boolean = false
) {
    val requiresConfirmation: Boolean get() = confirmationRequired || courses.isNotEmpty() || updates.isNotEmpty() || deletions.isNotEmpty() || !studyChanges.isNullOrEmpty()
}
