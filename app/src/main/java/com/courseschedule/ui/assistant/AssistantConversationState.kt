package com.courseschedule.ui.assistant

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules
import com.google.gson.Gson
import java.time.LocalDate

internal data class AssistantPendingOperation(val semester: Semester, val reply: AssistantCourseReply)
internal data class AssistantUndoBatch(val semester: Semester, val before: List<Course>, val after: List<Course>,
    val conflictBaseline: List<Course>? = null)
internal data class AssistantRetryRequest(val text: String, val displayedWeek: Int, val requestDate: String? = null,
    val imageRef: String? = null, val boundTarget: Course? = null, val bindingRecorded: Boolean = false)
internal data class AssistantTargetChoice(val semester: Semester, val request: AssistantRetryRequest,
    val candidates: List<Course>)
internal data class AssistantConversationState(
    val pending: AssistantPendingOperation? = null,
    val lastUndo: AssistantUndoBatch? = null,
    val retryRequest: AssistantRetryRequest? = null,
    val requestRunning: Boolean = false,
    val targetChoice: AssistantTargetChoice? = null,
    val selectedTarget: Course? = null
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
            require(reply.reply.isNotBlank() && reply.requiresConfirmation && !reply.cancelPending && !reply.undo && reply.queriedCourses.isEmpty() && reply.query == null && !reply.queryRequested && reply.studyQuery == null)
            reply.studyChanges?.let { changes ->
                require(reply.courses.isEmpty() && reply.updates.isEmpty() && reply.deletions.isEmpty())
                AssistantStudyProtocol.validateChanges(changes, semesterId)
            }
            validate(it.semester, reply.courses + reply.deletions + reply.updates.flatMap { row ->
                require(row.replacements.isNotEmpty())
                listOf(row.original) + row.replacements
            })
        }
        state.lastUndo?.let {
            require((it.before + it.after).isNotEmpty())
            validate(it.semester, it.before + it.after + it.conflictBaseline.orEmpty())
            require((it.before + it.after).all { course -> course.id > 0 })
        }
        fun validateRequest(request: AssistantRetryRequest) {
            require(request.text.isNotBlank() && request.text.length <= 2000 && request.displayedWeek in 1..52)
            request.requestDate?.let { LocalDate.parse(it) }
            request.imageRef?.let { require(AssistantImageMessage.valid(it)) }
            request.boundTarget?.let {
                require(request.bindingRecorded && it.id > 0 && it.semesterId == semesterId && ScheduleRules.isValidCourse(it, 52))
                requireNotNull(it.teacher); requireNotNull(it.classroom); requireNotNull(it.note)
            }
        }
        state.retryRequest?.let(::validateRequest)
        state.targetChoice?.let {
            require(state.pending == null)
            validateRequest(it.request)
            requireNotNull(it.request.requestDate)
            validate(it.semester, it.candidates)
            require(it.candidates.size in 2..200 && it.candidates.all { row -> row.id > 0 } &&
                it.candidates.map { row -> row.id }.distinct().size == it.candidates.size)
        }
        state.selectedTarget?.let {
            require(it.id > 0 && it.semesterId == semesterId && ScheduleRules.isValidCourse(it, 52))
        }
        require(!state.requestRunning || state.retryRequest != null)
        return state
    }
}
