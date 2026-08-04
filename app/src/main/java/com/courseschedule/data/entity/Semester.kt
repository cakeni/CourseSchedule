package com.courseschedule.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 学期实体类
 */
@Entity(tableName = "semesters")
data class Semester(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 学期名称 (如: "2024-2025 第一学期")
    val name: String,

    // 开学日期 (时间戳)
    val startDate: Long,

    // 总周数
    val totalWeeks: Int = 20,

    // 是否为当前学期
    val isCurrent: Boolean = false,

    // 创建时间
    val createTime: Long = System.currentTimeMillis()
)
