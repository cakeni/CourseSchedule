package com.courseschedule.data.repository

import androidx.lifecycle.LiveData
import com.courseschedule.data.dao.SemesterDao
import com.courseschedule.data.entity.Semester

/**
 * 学期数据仓库
 */
class SemesterRepository(private val semesterDao: SemesterDao) {

    // 获取所有学期
    fun getAllSemesters(): LiveData<List<Semester>> {
        return semesterDao.getAllSemesters()
    }

    // 获取当前学期
    fun getCurrentSemester(): LiveData<Semester?> {
        return semesterDao.getCurrentSemester()
    }

    // 获取当前学期 (同步)
    suspend fun getCurrentSemesterSync(): Semester? {
        return semesterDao.getCurrentSemesterSync()
    }

    // 插入学期
    suspend fun insertSemester(semester: Semester): Long {
        return semesterDao.insertSemester(semester)
    }

    // 更新学期
    suspend fun updateSemester(semester: Semester) {
        semesterDao.updateSemester(semester)
    }

    // 删除学期
    suspend fun deleteSemester(semester: Semester) {
        semesterDao.deleteSemester(semester)
    }

    // 切换当前学期
    suspend fun switchCurrentSemester(semesterId: Long) {
        semesterDao.switchCurrentSemester(semesterId)
    }

    // 根据ID获取学期
    suspend fun getSemesterById(semesterId: Long): Semester? {
        return semesterDao.getSemesterById(semesterId)
    }

    // 获取学期总数
    fun getSemesterCount(): LiveData<Int> {
        return semesterDao.getSemesterCount()
    }
}
