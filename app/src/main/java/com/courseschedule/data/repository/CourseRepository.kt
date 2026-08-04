package com.courseschedule.data.repository

import androidx.lifecycle.LiveData
import com.courseschedule.data.dao.CourseDao
import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules

/**
 * 课程数据仓库
 */
class CourseRepository(private val courseDao: CourseDao) {

    // 获取学期下的所有课程
    fun getCoursesBySemester(semesterId: Long): LiveData<List<Course>> {
        return courseDao.getCoursesBySemester(semesterId)
    }

    suspend fun getCoursesBySemesterSync(semesterId: Long): List<Course> {
        return courseDao.getCoursesBySemesterSync(semesterId)
    }

    // 获取某一天的课程
    fun getCoursesByDay(semesterId: Long, dayOfWeek: Int): LiveData<List<Course>> {
        return courseDao.getCoursesByDay(semesterId, dayOfWeek)
    }

    // 插入课程
    suspend fun insertCourse(course: Course): Long {
        return courseDao.insertCourse(course)
    }

    // 批量插入课程
    suspend fun insertCourses(courses: List<Course>): List<Long> {
        return courseDao.insertCourses(courses)
    }

    // 更新课程
    suspend fun updateCourse(course: Course) {
        courseDao.updateCourse(course)
    }

    // 删除课程
    suspend fun deleteCourse(course: Course) {
        courseDao.deleteCourse(course)
    }

    // 根据ID删除课程
    suspend fun deleteCourseById(courseId: Long) {
        courseDao.deleteCourseById(courseId)
    }

    suspend fun deleteCoursesByIds(courseIds: List<Long>) {
        if (courseIds.isNotEmpty()) courseDao.deleteCoursesByIds(courseIds)
    }

    // 删除学期下的所有课程
    suspend fun deleteCoursesBySemester(semesterId: Long) {
        courseDao.deleteCoursesBySemester(semesterId)
    }

    suspend fun replaceCoursesBySemester(semesterId: Long, courses: List<Course>): List<Long> {
        return courseDao.replaceCoursesBySemester(semesterId, courses)
    }

    // 根据ID获取课程
    suspend fun getCourseById(courseId: Long): Course? {
        return courseDao.getCourseById(courseId)
    }

    // 检查课程冲突
    suspend fun checkConflict(
        semesterId: Long,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        startWeek: Int,
        endWeek: Int,
        weekType: Int,
        excludeId: Long = 0
    ): Boolean {
        val candidate = Course(
            id = excludeId,
            courseName = "",
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = weekType,
            semesterId = semesterId
        )
        return courseDao.getPotentialConflicts(
            semesterId, dayOfWeek, startSection, endSection, startWeek, endWeek, excludeId
        ).any { ScheduleRules.coursesOverlap(it, candidate) }
    }

    // 搜索课程
    fun searchCourses(semesterId: Long, keyword: String): LiveData<List<Course>> {
        return courseDao.searchCourses(semesterId, keyword)
    }

    // 获取课程总数
    fun getCourseCount(semesterId: Long): LiveData<Int> {
        return courseDao.getCourseCount(semesterId)
    }
}
