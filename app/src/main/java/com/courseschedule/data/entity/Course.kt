package com.courseschedule.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 课程实体类
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 课程名称
    val courseName: String,

    // 教师姓名
    val teacher: String = "",

    // 教室/地点
    val classroom: String = "",

    // 星期几 (1-7, 1=周一)
    val dayOfWeek: Int,

    // 开始节次
    val startSection: Int,

    // 结束节次
    val endSection: Int,

    // 开始周次
    val startWeek: Int,

    // 结束周次
    val endWeek: Int,

    // 周类型: 0=每周, 1=单周, 2=双周
    val weekType: Int = 0,

    // 学期ID
    val semesterId: Long = 1,

    // 课程颜色 (存储颜色资源ID或颜色值)
    val colorIndex: Int = 0,

    // 备注
    val note: String = "",

    // 提醒时间 (分钟, -1表示不提醒)
    val reminderMinutes: Int = -1,

    // 创建时间
    val createTime: Long = System.currentTimeMillis()
)
