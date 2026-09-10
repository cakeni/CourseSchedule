package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericAcademicScheduleParserTest {
    private val parser = GenericAcademicScheduleParser(20)

    @Test fun newUrpReadsBothPayloadShapesAndKeepsEveryTimeSlot() {
        val dateList = json(
            """{
              "dateList":[{"selectCourseList":[{
                "courseName":"数据结构","attendClassTeacher":"张老师",
                "timeAndPlaceList":[
                  {"classDay":2,"classSessions":3,"continuingSession":2,"classWeek":"110110",
                   "campusName":"","teachingBuildingName":"明理楼","classroomName":"A101"},
                  {"classDay":4,"classSessions":6,"continuingSession":1,"classWeek":"10101",
                   "campusName":"南区","teachingBuildingName":"实验楼","classroomName":"201"}
                ]
              }]}]
            }"""
        )
        val courses = parser.parse(AcademicSystem.URP_NEW, "", dateList).courses
        assertEquals(setOf(2 to (3 to 4), 4 to (6 to 6)),
            courses.map { it.dayOfWeek to (it.startSection to it.endSection) }.toSet())
        assertEquals(listOf(1, 2, 4, 5), activeWeeks(courses.filter { it.dayOfWeek == 2 }))
        assertEquals(listOf(1, 3, 5), activeWeeks(courses.filter { it.dayOfWeek == 4 }))
        assertTrue(courses.filter { it.dayOfWeek == 2 }.all { it.teacher == "张老师" && it.classroom == "明理楼A101" })

        val classList = json(
            """[[{"kcm":"编译原理","jsm":"李老师","jxlm":"逸夫楼","jasm":"B203","cxjc":"3",
                    "id":{"skxq":"5","skzc":"10101","skjc":"7"}}]]"""
        )
        val other = parser.parse(AcademicSystem.URP_NEW, "", classList).courses
        assertEquals(listOf(1, 3, 5), activeWeeks(other))
        assertEquals(5 to (7 to 9), other.single().let { it.dayOfWeek to (it.startSection to it.endSection) })
    }

    @Test fun chengfangBalancesNestedJavascriptAndSplitsSectionsAndWeeks() {
        val html = """
            <script>
              var kbxx = [{"kcmc":"编译]原理","xq":"1","jcdm2":"1,2,4","zcs":"1,2,5",
                "jxcdmcs":"明德楼A205","teaxms":"唐老师","nested":[[1],[2]]}];
              const unrelated = ["not a course"];
            </script>
        """.trimIndent()
        val courses = parser.parse(AcademicSystem.CHENGFANG, html, null).courses
        assertEquals(setOf(1 to 2, 4 to 4), courses.map { it.startSection to it.endSection }.toSet())
        assertEquals(listOf(1, 2, 5), activeWeeks(courses))
        assertTrue(courses.all { it.courseName == "编译]原理" && it.dayOfWeek == 1 })
    }

    @Test fun shuweiReadsCurrentAndLegacyActivityShapesWithoutInventingTimes() {
        val current = json(
            """{"studentTableVm":{"activities":[{
              "courseName":"操作系统","room":"软件楼301","teachers":"王老师","weekday":3,
              "startUnit":3,"endUnit":4,"weekIndexes":[1,2,5,6]
            }]}}"""
        )
        val courses = parser.parse(AcademicSystem.SHUWEI, "", current).courses
        assertEquals(listOf(1, 2, 5, 6), activeWeeks(courses))
        assertEquals(3 to (3 to 4), courses.first().let { it.dayOfWeek to (it.startSection to it.endSection) })
        assertThrows(ImportFormatException::class.java) {
            parser.parse(AcademicSystem.SHUWEI, "", json(current.toString().replace("\"endUnit\":4,", "")))
        }

        val legacy = json(
            """{"unitCount":2,"activities":[
              [{"courseName":"离散数学(教学班)","teacherName":"周老师","roomName":"B101","vaildWeeks":"01010"}],
              [{"courseName":"离散数学(教学班)","teacherName":"周老师","roomName":"B101","vaildWeeks":"01010"}],
              [],[]
            ]}"""
        )
        val old = parser.parse(AcademicSystem.SHUWEI, "", legacy).courses
        assertEquals("离散数学", old.single().courseName)
        assertEquals(listOf(1, 3), activeWeeks(old))
        assertEquals(1 to (1 to 2), old.single().let { it.dayOfWeek to (it.startSection to it.endSection) })

        val eams = parser.parse(AcademicSystem.EAMS, "", legacy).courses
        assertEquals(listOf(1, 3), activeWeeks(eams))
        assertEquals(1 to 2, eams.single().let { it.startSection to it.endSection })
    }

    @Test fun zhengfangReadsJwglxtJsonAndFallsBackToExistingHtmlRoute() {
        val data = json(
            """{"items":[{
              "kcmc":"计算机伦理","xm":"王老师","cdmc":"明辨楼C402",
              "xqj":"3","jcs":"3-4节","zcd":"1-8周(单),10-12周(双)"
            }]}"""
        )
        val courses = parser.parse(AcademicSystem.ZHENGFANG_HTML, "", data).courses
        assertEquals("计算机伦理", courses.first().courseName)
        assertEquals(3 to (3 to 4), courses.first().let { it.dayOfWeek to (it.startSection to it.endSection) })
        assertEquals(listOf(1, 3, 5, 7, 10, 12), activeWeeks(courses))
        assertTrue(courses.all { it.teacher == "王老师" && it.classroom == "明辨楼C402" })
    }

    @Test fun xbellAndChaoxingApplyTheirDocumentedWeekTypeConventions() {
        fun xbell(dsz: Int) = parser.parse(AcademicSystem.XBELL, "", json(
            """[{"kcmc":"课程","xqj":"2","djj":"3","qmz":"1-4","dsz":"$dsz",
                 "jsxm":"教师","skdd":"教室"}]"""
        )).courses
        assertEquals(listOf(2, 4), activeWeeks(xbell(0)))
        assertEquals(listOf(1, 3), activeWeeks(xbell(1)))
        assertEquals(listOf(1, 2, 3, 4), activeWeeks(xbell(2)))

        val chaoxing = parser.parse(AcademicSystem.CHAOXING, "", json(
            """{"data":{"kckbData":[{"kcmc":"大学英语","xq":"4","djc":"5","zc":"1-8","zctype":"2",
                 "tmc":"赵老师","croommc":"博学楼102"}]}}"""
        )).courses
        assertEquals(listOf(2, 4, 6, 8), activeWeeks(chaoxing))
        assertEquals(4 to 5, chaoxing.first().let { it.dayOfWeek to it.startSection })
    }

    @Test fun chaoxingShareKeepsLengthMultipleSlotsAndIrregularWeeks() {
        val data = json(
            """{"data":{"lessonArray":[
              {"name":"线性代数","location":"一教201","onlineLocation":"","teacherName":"刘老师",
               "dayOfWeek":1,"beginNumber":1,"length":2,"weeks":"1,2,5,6"},
              {"name":"线性代数","location":"实验室","onlineLocation":"","teacherName":"刘老师",
               "dayOfWeek":3,"beginNumber":7,"length":1,"weeks":"3,7"}
            ]}}"""
        )
        val courses = parser.parse(AcademicSystem.CHAOXING_SHARE, "", data).courses
        assertEquals(setOf(1 to (1 to 2), 3 to (7 to 7)),
            courses.map { it.dayOfWeek to (it.startSection to it.endSection) }.toSet())
        assertEquals(listOf(1, 2, 5, 6), activeWeeks(courses.filter { it.dayOfWeek == 1 }))
        assertEquals(listOf(3, 7), activeWeeks(courses.filter { it.dayOfWeek == 3 }))
    }

    @Test fun aicStructuredAndGridHtmlRequireExplicitWeekdaySectionsAndWeeks() {
        val aic = """
            <table id="table"><tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><th>第1节</th><td><div class="courseInfo"><span>高等数学</span>
                <span class="weekDetail">1-2,5周</span><span class="teacher">教师甲</span>
                <span class="place">一教101</span></div></td><td></td></tr>
            </table>
        """.trimIndent()
        val aicCourses = parser.parse(AcademicSystem.AIC_HTML, aic, null).courses
        assertEquals(listOf(1, 2, 5), activeWeeks(aicCourses))
        assertEquals(1 to 1, aicCourses.first().let { it.dayOfWeek to it.startSection })

        val structured = """
            <table><tr><th>课程名称</th><th>星期</th><th>开始节次</th><th>结束节次</th><th>周次</th><th>教师</th><th>教室</th></tr>
              <tr><td>数据库</td><td>星期六</td><td>3</td><td>5</td><td>1-4,7-8周</td><td>教师乙</td><td>实验楼</td></tr>
            </table>
        """.trimIndent()
        val rows = parser.parse(AcademicSystem.URP_HTML, structured, null).courses
        assertEquals(listOf(1, 2, 3, 4, 7, 8), activeWeeks(rows))
        assertEquals(6 to (3 to 5), rows.first().let { it.dayOfWeek to (it.startSection to it.endSection) })

        val missingEnd = structured.replace("<th>结束节次</th>", "").replace("<td>5</td>", "")
        assertThrows(ImportFormatException::class.java) {
            parser.parse(AcademicSystem.URP_HTML, missingEnd, null)
        }
        assertThrows(ImportFormatException::class.java) {
            parser.parse(AcademicSystem.URP_HTML,
                "<table><tr><th>课程</th><th>星期</th><th>节次</th></tr><tr><td>课程</td><td>一</td><td>1-2</td></tr></table>", null)
        }

        val compactSections = structured.replace("<th>开始节次</th><th>结束节次</th>", "<th>节次</th>")
            .replace("<td>3</td><td>5</td>", "<td>3-5</td>")
        assertEquals(3 to 5, parser.parse(AcademicSystem.URP_HTML, compactSections, null).courses.first()
            .let { it.startSection to it.endSection })
    }

    @Test fun captureScriptProjectsOnlyCourseFieldsAndDoesNotExposeBrowserSecrets() {
        val school = GenericAcademicImport.create("urp_new", "https://jw.example.edu.cn/student/table")
        val script = AcademicCaptureScript.create(school)
        assertTrue(script.contains("courseName"))
        assertTrue(script.contains("weekIndexes"))
        assertTrue(script.contains("table0"))
        assertTrue(script.contains("veInitDefaultJson"))
        assertTrue(script.contains("https://jw.example.edu.cn/"))
        listOf("document.cookie", "localStorage", "sessionStorage", "password", "addJavascriptInterface")
            .forEach { forbidden -> assertFalse(forbidden, script.contains(forbidden, ignoreCase = true)) }
        assertFalse(script.contains("'value'"))
        assertFalse(script.contains("'href'"))
        assertFalse(script.contains("'src'"))
    }

    private fun json(value: String) = JsonParser.parseString(value)

    private fun activeWeeks(courses: List<Course>): List<Int> = (1..20).filter { week ->
        courses.any { course ->
            week in course.startWeek..course.endWeek && when (course.weekType) {
                1 -> week % 2 == 1
                2 -> week % 2 == 0
                else -> true
            }
        }
    }
}
