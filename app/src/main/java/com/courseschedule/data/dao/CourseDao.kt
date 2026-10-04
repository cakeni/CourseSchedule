package com.courseschedule.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.courseschedule.data.entity.Course

/**
 * 课程数据访问对象
 */
@Dao
interface CourseDao {

    @Query("SELECT * FROM courses")
    suspend fun getAllCoursesSync(): List<Course>

    // 插入课程
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    // 批量插入课程
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<Course>): List<Long>

    // 更新课程
    @Update
    suspend fun updateCourse(course: Course)

    // 删除课程
    @Delete
    suspend fun deleteCourse(course: Course)

    // 根据ID删除课程
    @Query("DELETE FROM courses WHERE id = :courseId")
    suspend fun deleteCourseById(courseId: Long)

    @Query("DELETE FROM courses WHERE id IN (:courseIds)")
    suspend fun deleteCoursesByIds(courseIds: List<Long>)

    // 删除学期下的所有课程
    @Query("DELETE FROM courses WHERE semesterId = :semesterId")
    suspend fun deleteCoursesBySemester(semesterId: Long)

    @Transaction
    suspend fun replaceCoursesBySemester(semesterId: Long, courses: List<Course>): List<Long> {
        deleteCoursesBySemester(semesterId)
        return insertCourses(courses)
    }

    // 获取学期下的所有课程
    @Query("SELECT * FROM courses WHERE semesterId = :semesterId ORDER BY dayOfWeek, startSection")
    fun getCoursesBySemester(semesterId: Long): LiveData<List<Course>>

    // 获取学期下的所有课程 (非LiveData)
    @Query("SELECT * FROM courses WHERE semesterId = :semesterId ORDER BY dayOfWeek, startSection")
    suspend fun getCoursesBySemesterSync(semesterId: Long): List<Course>

    // 获取某一天的课程
    @Query("SELECT * FROM courses WHERE semesterId = :semesterId AND dayOfWeek = :dayOfWeek ORDER BY startSection")
    fun getCoursesByDay(semesterId: Long, dayOfWeek: Int): LiveData<List<Course>>

    // 根据ID获取课程
    @Query("SELECT * FROM courses WHERE id = :courseId")
    suspend fun getCourseById(courseId: Long): Course?

    // 获取可能冲突的课程，单双周的最终判断在统一规则层完成
    @Query("""
        SELECT * FROM courses
        WHERE semesterId = :semesterId
        AND dayOfWeek = :dayOfWeek
        AND ((startSection BETWEEN :startSection AND :endSection)
             OR (endSection BETWEEN :startSection AND :endSection)
             OR (startSection <= :startSection AND endSection >= :endSection))
        AND (startWeek <= :endWeek AND endWeek >= :startWeek)
        AND id != :excludeId
    """)
    suspend fun getPotentialConflicts(
        semesterId: Long,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        startWeek: Int,
        endWeek: Int,
        excludeId: Long = 0
    ): List<Course>

    // 搜索课程
    @Query("""
        SELECT * FROM courses
        WHERE semesterId = :semesterId
        AND (courseName LIKE '%' || :keyword || '%'
             OR teacher LIKE '%' || :keyword || '%'
             OR classroom LIKE '%' || :keyword || '%')
        ORDER BY dayOfWeek, startSection
    """)
    fun searchCourses(semesterId: Long, keyword: String): LiveData<List<Course>>

    // 获取课程总数
    @Query("SELECT COUNT(*) FROM courses WHERE semesterId = :semesterId")
    fun getCourseCount(semesterId: Long): LiveData<Int>
}
