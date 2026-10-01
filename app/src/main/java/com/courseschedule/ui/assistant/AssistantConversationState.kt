package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules
import com.google.gson.Gson

internal data class AssistantPendingOperation(val semester: Semester, val reply: AssistantCourseReply)
internal data class AssistantUndoBatch(val semester: Semester, val before: List<Course>, val after: List<Course>)
internal data class AssistantRetryRequest(val text: String, val displayedWeek: Int)
internal data class AssistantConversationState(
    val pending: AssistantPendingOperation? = null,
    val lastUndo: AssistantUndoBatch? = null,
    val retryRequest: AssistantRetryRequest? = null,
    val requestRunning: Boolean = false
)

internal object AssistantConversationCodec {
    private val gson = Gson()
    fun encode(state: AssistantConversationState): String = gson.toJson(state)

    fun decode(json: String, semesterId: Long): AssistantConversationState {
        val state = requireNotNull(gson.fromJson(json, AssistantConversationState::class.java))
        fun validate(semester: Semester, courses: List<Course>) {
            require(semester.id == semesterId && semester.totalWeeks in 1..52 && semester.name.isNotBlank())
            require(courses.all { it.semesterId == semesterId && ScheduleRules.isValidCourse(it, semester.totalWeeks) &&
                it.reminderMinutes in -1..1440 })
            courses.forEach {
                requireNotNull(it.teacher)
                requireNotNull(it.classroom)
                requireNotNull(it.note)
            }
        }
        state.pending?.let {
            val reply = it.reply
            require(reply.reply.isNotBlank() && reply.requiresConfirmation && !reply.undo && reply.queriedCourses.isEmpty())
            validate(it.semester, reply.courses + reply.deletions + reply.updates.flatMap { row ->
                require(row.replacements.isNotEmpty())
                listOf(row.original) + row.replacements
            })
        }
        state.lastUndo?.let {
            require((it.before + it.after).isNotEmpty())
            validate(it.semester, it.before + it.after)
            require((it.before + it.after).all { course -> course.id > 0 })
        }
        state.retryRequest?.let { require(it.text.isNotBlank() && it.text.length <= 2000 && it.displayedWeek in 1..52) }
        require(!state.requestRunning || (state.retryRequest != null && state.pending == null))
        return state
    }
}
