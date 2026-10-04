package com.courseschedule.domain

import com.courseschedule.data.entity.Course

internal object AiCourseColors {
    const val DEEPSEEK_NOTE = "DeepSeek 网页识别，请核对"
    const val OPENAI_NOTE = "OpenAI 网页识别，请核对"

    fun isAiCourse(course: Course) = course.note == DEEPSEEK_NOTE || course.note == OPENAI_NOTE

    fun index(name: String) = Math.floorMod(name.trim().hashCode(), 16)
}
