package com.courseschedule.viewmodel

import android.app.Application
import androidx.lifecycle.*
import androidx.lifecycle.switchMap
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.repository.CourseRepository
import com.courseschedule.data.repository.SemesterRepository
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.domain.SemesterWeekStatus
import androidx.lifecycle.Observer
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 课程ViewModel
 */
class CourseViewModel(application: Application) : AndroidViewModel(application) {

    private val courseRepository: CourseRepository
    private val semesterRepository: SemesterRepository

    // 当前学期
    val currentSemester: LiveData<Semester?>

    // 当前周次
    private val _currentWeek = MutableLiveData<Int>()
    val currentWeek: LiveData<Int> = _currentWeek

    private val _semesterWeekStatus = MutableLiveData<SemesterWeekStatus>()
    val semesterWeekStatus: LiveData<SemesterWeekStatus> = _semesterWeekStatus

    // 当前学期的所有课程
    val allCourses: LiveData<List<Course>>

    // 选中的星期 (1-7)
    private val _selectedDay = MutableLiveData<Int>()
    val selectedDay: LiveData<Int> = _selectedDay

    private val semesterObserver = Observer<Semester?> { semester ->
        semester?.let(::calculateCurrentWeek)
    }

    init {
        val database = AppDatabase.getDatabase(application)
        courseRepository = CourseRepository(database.courseDao())
        semesterRepository = SemesterRepository(database.semesterDao())

        currentSemester = semesterRepository.getCurrentSemester()

        // 初始化选中日期为今天
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        _selectedDay.value = when (dayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }

        // 使用switchMap获取当前学期的课程
        allCourses = currentSemester.switchMap { semester: Semester? ->
            if (semester != null) {
                courseRepository.getCoursesBySemester(semester.id)
            } else {
                MutableLiveData(emptyList<Course>())
            }
        }

        // 当 currentSemester 变化时（含首次启动默认学期异步插入完成），
        // 重新计算当前周次，避免首次启动竞态导致周次恒为 1
        currentSemester.observeForever(semesterObserver)
    }

    /**
     * 根据给定学期计算当前是第几周
     */
    private fun calculateCurrentWeek(semester: Semester) {
        val status = ScheduleRules.semesterWeekStatus(semester)
        _semesterWeekStatus.value = status
        _currentWeek.value = status.week
    }

    /**
     * 获取某一天的课程
     */
    fun getCoursesByDay(dayOfWeek: Int): LiveData<List<Course>> {
        val semesterId = currentSemester.value?.id ?: return MutableLiveData(emptyList())
        return courseRepository.getCoursesByDay(semesterId, dayOfWeek)
    }

    /**
     * 获取指定周次的课程
     */
    fun getCoursesForWeek(week: Int): List<Course> {
        val courses = allCourses.value ?: emptyList()
        return courses.filter { course -> ScheduleRules.isCourseInWeek(course, week) }
    }

    suspend fun getCurrentSemesterCourses(): List<Course> {
        val semesterId = currentSemester.value?.id ?: return emptyList()
        return courseRepository.getCoursesBySemesterSync(semesterId)
    }

    suspend fun getSemesterCourses(semesterId: Long): List<Course> {
        return courseRepository.getCoursesBySemesterSync(semesterId)
    }

    /**
     * 判断课程是否在指定周次
     */
    /**
     * 添加课程
     */
    fun insertCourse(course: Course) {
        viewModelScope.launch {
            courseRepository.insertCourse(course)
        }
    }

    suspend fun insertCourseAndGetId(course: Course): Long {
        return courseRepository.insertCourse(course)
    }

    /**
     * 批量添加课程
     */
    fun insertCourses(courses: List<Course>) {
        viewModelScope.launch {
            courseRepository.insertCourses(courses)
        }
    }

    suspend fun insertCoursesNow(courses: List<Course>): List<Long> {
        return courseRepository.insertCourses(courses)
    }

    suspend fun deleteCoursesNow(courseIds: List<Long>) {
        courseRepository.deleteCoursesByIds(courseIds)
    }

    suspend fun deleteCurrentSemesterCourses() {
        currentSemester.value?.id?.let { courseRepository.deleteCoursesBySemester(it) }
    }

    suspend fun deleteSemesterCourses(semesterId: Long) {
        courseRepository.deleteCoursesBySemester(semesterId)
    }

    suspend fun replaceCurrentSemesterCourses(courses: List<Course>): List<Long> {
        val semesterId = currentSemester.value?.id ?: return emptyList()
        return courseRepository.replaceCoursesBySemester(semesterId, courses)
    }

    /**
     * 根据ID获取课程
     */
    suspend fun getCourseById(courseId: Long): Course? {
        return courseRepository.getCourseById(courseId)
    }

    /**
     * 更新课程
     */
    fun updateCourse(course: Course) {
        viewModelScope.launch {
            courseRepository.updateCourse(course)
        }
    }

    suspend fun updateCourseNow(course: Course) {
        courseRepository.updateCourse(course)
    }

    /**
     * 删除课程
     */
    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            courseRepository.deleteCourse(course)
        }
    }

    /**
     * 删除课程 (根据ID)
     */
    fun deleteCourseById(courseId: Long) {
        viewModelScope.launch {
            courseRepository.deleteCourseById(courseId)
        }
    }

    suspend fun deleteCourseNow(courseId: Long) {
        courseRepository.deleteCourseById(courseId)
    }

    /**
     * 检查课程冲突
     */
    suspend fun checkConflict(
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        startWeek: Int,
        endWeek: Int,
        weekType: Int,
        excludeId: Long = 0
    ): Boolean {
        val semesterId = currentSemester.value?.id ?: return false
        return courseRepository.checkConflict(
            semesterId, dayOfWeek, startSection, endSection, startWeek, endWeek, weekType, excludeId
        )
    }

    /**
     * 搜索课程
     */
    fun searchCourses(keyword: String): LiveData<List<Course>> {
        val semesterId = currentSemester.value?.id ?: return MutableLiveData(emptyList())
        return courseRepository.searchCourses(semesterId, keyword)
    }

    /**
     * 切换当前学期
     * 注：切换后 currentSemester LiveData 会重新发出，
     * 上面的 observeForever 会自动重新计算当前周次，无需手动调用
     */
    fun switchSemester(semesterId: Long) {
        viewModelScope.launch {
            semesterRepository.switchCurrentSemester(semesterId)
        }
    }

    /**
     * 设置当前周次
     */
    fun setCurrentWeek(week: Int) {
        _currentWeek.value = week
    }

    fun goToCurrentWeek() {
        currentSemester.value?.let(::calculateCurrentWeek)
    }

    fun refreshSemesterStatus() {
        currentSemester.value?.let(::calculateCurrentWeek)
    }

    override fun onCleared() {
        currentSemester.removeObserver(semesterObserver)
        super.onCleared()
    }

    /**
     * 设置选中的星期
     */
    fun setSelectedDay(day: Int) {
        _selectedDay.value = day
    }

    /**
     * 切换到下一天
     */
    fun nextDay() {
        val current = _selectedDay.value ?: 1
        if (current < 7) {
            _selectedDay.value = current + 1
        }
    }

    /**
     * 切换到上一天
     */
    fun previousDay() {
        val current = _selectedDay.value ?: 1
        if (current > 1) {
            _selectedDay.value = current - 1
        }
    }
}
