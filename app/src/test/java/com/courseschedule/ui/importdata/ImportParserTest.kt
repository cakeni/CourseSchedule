package com.courseschedule.ui.importdata

import com.courseschedule.data.backup.ScheduleBackup
import com.courseschedule.data.backup.SemesterSnapshot
import com.courseschedule.data.backup.SettingsSnapshot
import com.courseschedule.data.entity.Course
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportParserTest {

    private val parser = ImportParser(defaultTotalWeeks = 20)

    @Test
    fun csvSupportsQuotedCommas() {
        val csv = """
            课程名称,教师,教室,星期,节次,周次
            "大学英语,视听说",张老师,A101,周二,3-4,1-16双周
        """.trimIndent()

        val result = parser.parseCsv(csv)

        assertEquals(1, result.courses.size)
        assertEquals("大学英语,视听说", result.courses.single().courseName)
        assertEquals(2, result.courses.single().dayOfWeek)
        assertEquals(2, result.courses.single().weekType)
    }

    @Test
    fun htmlUsesHeaderColumnsInsteadOfGuessingDefaults() {
        val html = """
            <table>
              <tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th></tr>
              <tr><td>数据结构</td><td>周三</td><td>5-6</td><td>2-18单周</td></tr>
            </table>
        """.trimIndent()

        val course = parser.parseHtml(html).courses.single()

        assertEquals(3, course.dayOfWeek)
        assertEquals(5, course.startSection)
        assertEquals(18, course.endWeek)
        assertEquals(1, course.weekType)
    }

    @Test(expected = ImportFormatException::class)
    fun vagueHtmlIsRejectedInsteadOfCreatingMondayCourse() {
        parser.parseHtml("<table><tr><td>课程说明</td></tr></table>")
    }

    @Test
    fun completeBackupRestoresMetadata() {
        val backup = ScheduleBackup(
            semester = SemesterSnapshot("秋季", 123L, 18),
            settings = SettingsSnapshot(showWeekend = false),
            courses = listOf(course("操作系统"))
        )

        val parsed = parser.parseJson(Gson().toJson(backup))

        assertEquals("秋季", parsed.semester?.name)
        assertEquals(false, parsed.settings?.showWeekend)
        assertEquals("操作系统", parsed.courses.single().courseName)
    }

    @Test
    fun schoolTimetableGridRemovesCodesAndSplitsDiscontinuousWeeks() {
        val cell = """
            5621003035-操作系统[2001]
            1周,3-5周,7-8周,星期1,第1节-第2节博学楼A508,

            2515670030-概率统计(Ⅰ)[2004]
            10-17周,星期1,第1节-第2节思学楼A413
        """.trimIndent()
        val rows = listOf(
            listOf("节次/星期", "", "星期一", "星期二", "星期三"),
            listOf("第1节-第2节", "", cell, "1", "1")
        )

        val courses = parser.parseTabularRows(rows, "Excel").courses

        assertEquals(4, courses.size)
        assertEquals(listOf("操作系统", "操作系统", "操作系统", "概率统计(Ⅰ)"), courses.map { it.courseName })
        assertEquals(listOf(1 to 1, 3 to 5, 7 to 8, 10 to 17), courses.map { it.startWeek to it.endWeek })
        assertEquals(1, courses.first().dayOfWeek)
        assertEquals(1, courses.first().startSection)
        assertEquals(2, courses.first().endSection)
        assertEquals("博学楼A508", courses.first().classroom)
    }

    @Test
    fun schoolTimetableGridKeepsLabeledTeacher() {
        val cell = """
            5621003035-操作系统[2001]
            1-8周,星期1,第1节-第2节博学楼A508
            教师：张老师
        """.trimIndent()
        val rows = listOf(
            listOf("节次/星期", "", "星期一", "星期二"),
            listOf("第1节-第2节", "", cell, "1")
        )

        val course = parser.parseTabularRows(rows, "Excel").courses.single()

        assertEquals("张老师", course.teacher)
        assertEquals("博学楼A508", course.classroom)
    }

    private fun course(name: String) = Course(
        courseName = name,
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        startWeek = 1,
        endWeek = 16
    )
}
