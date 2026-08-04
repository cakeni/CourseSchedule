package com.courseschedule.data.entity

/**
 * 课程详情辅助类 - 包含格式化的显示信息
 */
data class CourseDetail(
    val course: Course,
    val weekRange: String,      // "1-16周" 或 "1-16周(单)"
    val timeRange: String,      // "08:00-09:40"
    val sectionRange: String,   // "第1-2节"
)
