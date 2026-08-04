package com.courseschedule.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.courseschedule.data.entity.Semester

/**
 * 学期数据访问对象
 */
@Dao
interface SemesterDao {

    // 插入学期
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: Semester): Long

    // 更新学期
    @Update
    suspend fun updateSemester(semester: Semester)

    // 删除学期
    @Delete
    suspend fun deleteSemester(semester: Semester)

    // 获取所有学期
    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    fun getAllSemesters(): LiveData<List<Semester>>

    // 获取当前学期
    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentSemester(): LiveData<Semester?>

    // 获取当前学期 (非LiveData)
    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrentSemesterSync(): Semester?

    // 设置当前学期 (先取消所有，再设置新的)
    @Query("UPDATE semesters SET isCurrent = 0")
    suspend fun clearCurrentSemester()

    @Query("UPDATE semesters SET isCurrent = 1 WHERE id = :semesterId")
    suspend fun setCurrentSemester(semesterId: Long)

    @Transaction
    suspend fun switchCurrentSemester(semesterId: Long) {
        clearCurrentSemester()
        setCurrentSemester(semesterId)
    }

    // 根据ID获取学期
    @Query("SELECT * FROM semesters WHERE id = :semesterId")
    suspend fun getSemesterById(semesterId: Long): Semester?

    // 获取学期总数
    @Query("SELECT COUNT(*) FROM semesters")
    fun getSemesterCount(): LiveData<Int>
}
