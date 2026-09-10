package com.courseschedule.ui.importdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportScheduleParserTest {
    @Test fun gdeiNestedGridReadsCourseBlockFields() {
        val html = """
            <table><tbody><tr>
              <td>第1-2节</td>
              <td><div class="course-block">
                <div>离散数学</div><div><span>第1-8周;第10-16周双</span><span>第3-4节</span></div>
                <div>占位</div><div>A201(主楼)</div><div>赵老师</div>
              </div></td>
            </tr></tbody></table>
        """.trimIndent()
        val courses = GdeiScheduleParser(20).parse(html).courses
        val first = courses.first()
        assertEquals("离散数学", first.courseName)
        assertEquals(1, first.dayOfWeek)
        assertEquals(3 to 4, first.startSection to first.endSection)
        assertEquals("赵老师", first.teacher)
        assertEquals("A201", first.classroom)
    }

    @Test fun xhtdBlocksUseDivCoordinatesAndFiveLineRecords() {
        val html = """
            <div id="kbtable"><div id="2-3"><nobr>编译原理</nobr><br>占位<br>陈老师<br>
              第1-8周,第10-16周双<br>A301
            </div></div>
        """.trimIndent()
        val courses = XhtdScheduleParser(20).parse(html).courses
        val first = courses.first()
        assertEquals("编译原理", first.courseName)
        assertEquals(3, first.dayOfWeek)
        assertEquals(3 to 4, first.startSection to first.endSection)
        assertEquals("陈老师", first.teacher)
        assertEquals("A301", first.classroom)
    }

    @Test fun uestcPostGridReadsSlashDelimitedFields() {
        val html = """
            <table id="tbl">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>上午</td><td>/高等代数/代码/类型/张老师/1-8周,10-16周双/(3-4)/A101</td><td></td></tr>
            </table>
        """.trimIndent()
        val courses = UestcPostScheduleParser(20).parse(html).courses
        val first = courses.first()
        assertEquals("高等代数", first.courseName)
        assertEquals(1, first.dayOfWeek)
        assertEquals(3 to 4, first.startSection to first.endSection)
        assertEquals("张老师", first.teacher)
        assertEquals("A101", first.classroom)
    }

    @Test fun hitPrintGridUsesRowSectionsAndWeekMarkers() {
        val html = """
            <div class="xfyq_con"><table><tr>
              <td></td><td>第1-2节</td>
              <td>高等数学<br>[1-8周] 教师：张老师 地点：A101</td>
              <td></td><td></td><td></td><td></td><td></td><td></td>
            </tr></table></div>
        """.trimIndent()
        val course = HitScheduleParser(20).parse(html).courses.single()
        assertEquals("高等数学", course.courseName)
        assertEquals(1, course.dayOfWeek)
        assertEquals(1 to 2, course.startSection to course.endSection)
        assertEquals(1 to 8, course.startWeek to course.endWeek)
        assertEquals("张老师", course.teacher)
        assertEquals("A101", course.classroom)
    }

    @Test fun hitszCardGridReadsBracketMetadataByDayColumn() {
        val html = """
            <table><tbody class="ivu-table-tbody"><tr>
              <td>节次</td>
              <td><div class="ivu-card-body">数据结构 [王老师] [A101][1-8周] [第1-2节]</div></td>
              <td><div class="ivu-card-body">【实验】物理实验 [3-10周双][第3-4节] [实验楼202]</div></td>
            </tr></tbody></table>
        """.trimIndent()
        val courses = HitszScheduleParser(20).parse(html).courses
        val data = courses.first { it.courseName == "数据结构" }
        assertEquals(1, data.dayOfWeek)
        assertEquals("王老师", data.teacher)
        assertEquals("A101", data.classroom)
        assertEquals(1 to 2, data.startSection to data.endSection)
        val experiment = courses.first { it.courseName == "[实验]物理实验" }
        assertEquals(2, experiment.dayOfWeek)
        assertEquals("实验楼202", experiment.classroom)
        assertEquals(2, experiment.weekType)
    }

    @Test fun scauPrintGridUsesColumnDaysAndTwoSectionsPerRow() {
        val html = """
            <table border="1" bordercolor="#000000">
              <tr><th colspan="8">学生课表</th></tr>
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>1-2</td>
                <td valign="top">高等数学：张老师<br>A101<br>1-8周</td>
                <td valign="top">大学英语 1-16：李老师<br>B202<br>无效占位</td>
              </tr>
            </table>
        """.trimIndent()
        val courses = ScauScheduleParser(20).parse(html).courses
        val math = courses.first { it.courseName == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1 to 2, math.startSection to math.endSection)
        assertEquals(1 to 8, math.startWeek to math.endWeek)
        val english = courses.first { it.courseName == "大学英语" }
        assertEquals(2, english.dayOfWeek)
        assertEquals(1 to 16, english.startWeek to english.endWeek)
    }

    @Test fun xjuPostGridSupportsBraceBlocksAndLegacyParenthesisBlocks() {
        val html = """
            <table id="ctl00_contentParent_dgData">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td align="center">第1节</td><td rowspan="2">
                高等数学｛1-8周[地点:A101,教师:张老师]｝；大学英语(李老师 B202)
              </td><td></td></tr>
              <tr><td align="center">第2节</td><td></td></tr>
            </table>
        """.trimIndent()
        val courses = StrictTimetableTableParser(
            20, "新疆大学研究生教务", listOf("#ctl00_contentParent_dgData"),
            orderedRowsAreSections = true,
            compactBraceFormat = true,
            defaultAllWeeks = true
        ).parse(html).courses
        val math = courses.first { it.courseName == "高等数学" }
        assertEquals("张老师", math.teacher)
        assertEquals("A101", math.classroom)
        assertEquals(1 to 8, math.startWeek to math.endWeek)
        assertEquals(1 to 2, math.startSection to math.endSection)
        val english = courses.first { it.courseName == "大学英语" }
        assertEquals("李老师", english.teacher)
        assertEquals("B202", english.classroom)
        assertEquals(1 to 20, english.startWeek to english.endWeek)
    }

    @Test fun cuplPostGridSplitsBoldCourseBlocks() {
        val html = """
            <table id="tabCT">
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>第1-2节课</td><td rowspan="2">
                <b>民法学</b><br>周次：第1-8周<br>校区：学院路<br>教室：端升101<br>教师：王老师
                <b>刑法学</b><br>第10-16周<br>教室：端升102<br>教师：李老师
              </td><td></td></tr>
              <tr><td>第2节课</td><td></td></tr>
            </table>
        """.trimIndent()
        val courses = StrictTimetableTableParser(
            20, "法大研究生教务", listOf("#tabCT"),
            orderedRowsAreSections = true,
            splitBoldBlocks = true
        ).parse(html).courses
        assertEquals(setOf("民法学", "刑法学"), courses.map { it.courseName }.toSet())
        assertEquals(setOf(1 to 2), courses.map { it.startSection to it.endSection }.toSet())
        assertEquals("端升102", courses.first { it.courseName == "刑法学" }.classroom)
    }

    @Test fun sudaPostGridUsesKnownRowAndRowspanSectionSemantics() {
        val html = """
            <table id="DataGrid1">
              <tr align="center"><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td align="center">1</td><td rowspan="2">
                课程:操作系统<br>(A101)<br>第1-8周<br>主讲教师:教师甲<br><br>
                课程:编译原理<br>(A102)<br>第10-16周(双)<br>主讲教师:教师乙
              </td><td></td></tr>
              <tr><td align="center">2</td><td></td></tr>
            </table>
        """.trimIndent()
        val courses = StrictTimetableTableParser(
            20, "苏大研究生教务", listOf("#DataGrid1"),
            orderedRowsAreSections = true, splitDoubleBreaks = true
        ).parse(html).courses
        assertEquals(setOf("操作系统", "编译原理"), courses.map { it.courseName }.toSet())
        assertEquals(setOf(1 to 2), courses.map { it.startSection to it.endSection }.toSet())
        assertEquals(2, courses.first { it.courseName == "编译原理" }.weekType)
    }

    @Test fun zjuPostGridUsesLeafCourseBlocksAndRowspanSections() {
        val html = """
            <form id="kcbForm"><table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><td>1</td><td rowspan="2"><div><strong>数据结构</strong><br>
                || (1-8周)<br>东1-101<br>王老师</div></td><td></td></tr>
              <tr><td>2</td><td></td></tr>
            </table></form>
        """.trimIndent()
        val course = StrictTimetableTableParser(
            20, "浙大研究生教务", listOf("#kcbForm table"),
            orderedRowsAreSections = true,
            splitLeafCourseDivs = true
        ).parse(html).courses.single()
        assertEquals("数据结构", course.courseName)
        assertEquals(1, course.dayOfWeek)
        assertEquals(1 to 2, course.startSection to course.endSection)
        assertEquals(1 to 8, course.startWeek to course.endWeek)
    }

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
