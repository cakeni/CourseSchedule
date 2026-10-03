package com.courseschedule.ui.addcourse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.courseschedule.R
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.utils.ReminderManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A pending write survives rotation; conflict checking and the write share one transaction. */
class CourseEditorViewModel(application: Application) : AndroidViewModel(application) {
    data class Result(val saved: Boolean, val message: String)
    val writing = MutableLiveData(false)
    val result = MutableLiveData<Result?>()
    private val database = AppDatabase.getDatabase(application)
    private var completionFeedbackConsumed = false

    fun consumeCompletionFeedback(): Boolean {
        if (result.value?.saved != true || completionFeedbackConsumed) return false
        completionFeedbackConsumed = true
        return true
    }

    fun save(course: Course, original: Course?, semester: Semester) = write {
        val saved = database.withTransaction {
            checkContext(semester, original)
            val conflicts = database.courseDao().getPotentialConflicts(
                semester.id, course.dayOfWeek, course.startSection, course.endSection,
                course.startWeek, course.endWeek, course.id
            )
            check(conflicts.none { ScheduleRules.coursesOverlap(it, course) }) {
                text(R.string.course_conflict)
            }
            if (original == null) course.copy(id = database.courseDao().insertCourse(course))
            else course.also { database.courseDao().updateCourse(it) }
        }
        val reminders = ReminderManager(getApplication())
        // A reminder failure must never offer to insert an already saved course again.
        val reminderIssue = runCatching {
            reminders.cancelReminder(saved.id)
            if (saved.reminderMinutes > 0) reminders.setReminder(saved, semester)
        }.exceptionOrNull()
        Result(true, if (reminderIssue == null) text(R.string.save_success)
            else "课程已保存，提醒设置未完成，请检查提醒权限")
    }

    fun delete(original: Course, semester: Semester) = write {
        database.withTransaction {
            checkContext(semester, original)
            database.courseDao().deleteCourseById(original.id)
        }
        runCatching { ReminderManager(getApplication()).cancelReminder(original.id) }
        Result(true, text(R.string.delete_success))
    }

    private suspend fun checkContext(semester: Semester, original: Course?) {
        check(database.semesterDao().getCurrentSemesterSync() == semester) {
            text(R.string.editor_changed_semester)
        }
        if (original != null) check(database.courseDao().getCourseById(original.id) == original) {
            text(R.string.editor_changed_course)
        }
    }

    private fun write(action: suspend () -> Result) {
        if (writing.value == true || result.value?.saved == true) return
        writing.value = true
        result.value = null
        viewModelScope.launch {
            try { result.value = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                result.value = Result(false, if (error is IllegalStateException)
                    error.message ?: text(R.string.editor_write_error) else text(R.string.editor_write_error))
            } finally { writing.value = false }
        }
    }

    private fun text(id: Int) = getApplication<Application>().getString(id)
}
