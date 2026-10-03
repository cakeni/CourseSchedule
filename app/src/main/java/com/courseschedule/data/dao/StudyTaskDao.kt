package com.courseschedule.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.courseschedule.data.entity.StudyTask

@Dao
interface StudyTaskDao {
    @Query("SELECT * FROM study_tasks WHERE semesterId = :semesterId ORDER BY dueAt, id")
    fun observe(semesterId: Long): LiveData<List<StudyTask>>
    @Query("SELECT * FROM study_tasks WHERE semesterId = :semesterId ORDER BY dueAt, id")
    suspend fun forSemester(semesterId: Long): List<StudyTask>
    @Query("SELECT * FROM study_tasks ORDER BY dueAt, id")
    suspend fun all(): List<StudyTask>
    @Query("SELECT * FROM study_tasks WHERE id = :id")
    suspend fun find(id: Long): StudyTask?
    @Insert suspend fun insert(task: StudyTask): Long
    @Update suspend fun update(task: StudyTask): Int
    @Query("DELETE FROM study_tasks WHERE id = :id") suspend fun delete(id: Long): Int
}
