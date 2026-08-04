package com.courseschedule.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.repository.SemesterRepository
import kotlinx.coroutines.launch

/**
 * 学期ViewModel
 */
class SemesterViewModel(application: Application) : AndroidViewModel(application) {

    private val semesterRepository: SemesterRepository

    // 所有学期
    val allSemesters: LiveData<List<Semester>>

    // 当前学期
    val currentSemester: LiveData<Semester?>

    init {
        val database = AppDatabase.getDatabase(application)
        semesterRepository = SemesterRepository(database.semesterDao())

        allSemesters = semesterRepository.getAllSemesters()
        currentSemester = semesterRepository.getCurrentSemester()
    }

    /**
     * 添加学期
     */
    fun insertSemester(semester: Semester) {
        viewModelScope.launch {
            semesterRepository.insertSemester(semester)
        }
    }

    /**
     * 更新学期
     */
    fun updateSemester(semester: Semester) {
        viewModelScope.launch {
            semesterRepository.updateSemester(semester)
        }
    }

    suspend fun updateSemesterNow(semester: Semester) {
        semesterRepository.updateSemester(semester)
    }

    suspend fun insertSemesterNow(semester: Semester): Long {
        return semesterRepository.insertSemester(semester)
    }

    suspend fun deleteSemesterNow(semester: Semester) {
        semesterRepository.deleteSemester(semester)
    }

    suspend fun switchSemesterNow(semesterId: Long) {
        semesterRepository.switchCurrentSemester(semesterId)
    }

    /**
     * 删除学期
     */
    fun deleteSemester(semester: Semester) {
        viewModelScope.launch {
            semesterRepository.deleteSemester(semester)
        }
    }

    /**
     * 切换当前学期
     */
    fun switchSemester(semesterId: Long) {
        viewModelScope.launch {
            semesterRepository.switchCurrentSemester(semesterId)
        }
    }

    /**
     * 根据ID获取学期
     */
    suspend fun getSemesterById(semesterId: Long): Semester? {
        return semesterRepository.getSemesterById(semesterId)
    }
}
