package com.courseschedule.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "study_tasks", foreignKeys = [ForeignKey(entity = Semester::class,
    parentColumns = ["id"], childColumns = ["semesterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("semesterId"), Index("dueAt")])
data class StudyTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val semesterId: Long,
    val courseId: Long? = null,
    // Keep the name when a timetable entry is deleted or split by a one-off change.
    val courseName: String = "",
    val title: String,
    val kind: String = "homework",
    val dueAt: Long,
    val reminderMinutes: Int = -1,
    val note: String = "",
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
