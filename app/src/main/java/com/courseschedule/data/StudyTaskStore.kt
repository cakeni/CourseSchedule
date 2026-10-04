package com.courseschedule.data

import android.content.Context
import androidx.room.withTransaction
import com.courseschedule.data.entity.StudyTask
import com.courseschedule.domain.StudyTaskRules
import com.courseschedule.utils.ReminderManager

/** Validate and compare inside the write transaction; a stale form cannot overwrite a newer edit. */
class StudyTaskStore(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    suspend fun save(task: StudyTask, original: StudyTask? = null): StudyTask {
        StudyTaskRules.validate(task)
        StudyTaskRules.validateReminderTime(task, original)
        val saved = db.withTransaction {
            require(db.semesterDao().getSemesterById(task.semesterId) != null) { "学期已删除，请重新打开。" }
            task.courseId?.let { id ->
                val course = db.courseDao().getCourseById(id)
                // Existing historical associations remain readable after course deletion.
                if (original?.courseId != id || original.courseName != task.courseName) {
                    require(course?.semesterId == task.semesterId && course.courseName == task.courseName) { "关联课程已变化，请重新选择。" }
                }
            }
            if (original == null) {
                require(task.id == 0L)
                task.copy(id = db.studyTaskDao().insert(task))
            } else {
                require(task.id == original.id && task.semesterId == original.semesterId && db.studyTaskDao().find(original.id) == original) { "事项已被修改或删除，请重新打开。" }
                require(db.studyTaskDao().update(task) == 1)
                task
            }
        }
        ReminderManager(context).setStudyReminder(saved)
        return saved
    }

    suspend fun delete(original: StudyTask) {
        db.withTransaction {
            require(db.studyTaskDao().find(original.id) == original) { "事项已变化，请重新打开。" }
            require(db.studyTaskDao().delete(original.id) == 1)
        }
        ReminderManager(context).cancelStudyReminder(original.id)
    }
}
