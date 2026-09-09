package com.courseschedule.ui.importdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportScheduleParserTest {
    @Test fun kingosoftReportHandlesMergedFieldsAndMultipleTimeRows() {
        val html = """
            <div class="pageRpt"><table>
              <tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th><th>教师</th><th>教室</th></tr>
              <tr><td rowspan="2">大学物理</td><td>星期一</td><td>1-2节</td><td>1-4周</td><td rowspan="2">教师甲</td><td>A101</td></tr>
              <tr><td>星期三</td><td>5-6节</td><td>6-10周(双)</td><td>B202</td></tr>
            </table></div>
        """.trimIndent()
        val courses = KingosoftScheduleParser(20, selectedResults = false).parse(html).courses
        assertEquals(listOf(1, 3), courses.map { it.dayOfWeek })
        assertEquals(listOf(1 to 2, 5 to 6), courses.map { it.startSection to it.endSection })
        assertEquals(listOf(1 to 4, 6 to 10), courses.map { it.startWeek to it.endWeek })
        assertEquals(2, courses.last().weekType)
    }

    @Test fun kingosoftRejectsImageOnlySchedulesWithDedicatedCode() {
        val error = assertThrows(ImportFormatException::class.java) {
            KingosoftScheduleParser(20, selectedResults = false)
                .parse("<div class='pageRpt'><img src='schedule.png'></div>")
        }
        assertEquals(AcademicImportErrorCode.UNSUPPORTED_VARIANT, error.errorCode)
    }

    @Test fun southSoftGridRespectsRowspanAndRequiresExplicitWeeks() {
        val valid = """
            <table id="kb">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><th>第1-2节</th><td rowspan="2"><div class="course">操作系统<br>教师：教师乙<br>地点：C303<br>1-8周</div></td><td></td></tr>
              <tr><th>第3-4节</th><td></td></tr>
            </table>
        """.trimIndent()
        val course = SouthSoftScheduleParser(20).parse(valid).courses.single()
        assertEquals(1, course.dayOfWeek)
        assertEquals(1 to 4, course.startSection to course.endSection)

        val error = assertThrows(ImportFormatException::class.java) {
            SouthSoftScheduleParser(20).parse(valid.replace("1-8周", "时间待定"))
        }
        assertEquals(AcademicImportErrorCode.MISSING_WEEK, error.errorCode)
    }

    @Test fun hiddenDuplicateReportIsIgnored() {
        fun report(name: String) = """
            <div class="pageRpt"><table>
              <tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th></tr>
              <tr><td>$name</td><td>星期二</td><td>3-4节</td><td>1-8周</td></tr>
            </table></div>
        """.trimIndent()
        val html = "<div style='display:none'>${report("隐藏课程")}</div>" + report("可见课程")
        val courses = KingosoftScheduleParser(20, selectedResults = false).parse(html).courses
        assertEquals(listOf("可见课程"), courses.map { it.courseName })
    }
}
