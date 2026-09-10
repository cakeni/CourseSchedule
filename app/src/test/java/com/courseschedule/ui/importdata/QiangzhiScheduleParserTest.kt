package com.courseschedule.ui.importdata

import com.courseschedule.domain.ScheduleRules
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class QiangzhiScheduleParserTest {
    private val parser = QiangzhiScheduleParser(20)

    @Test fun readsTitledFieldsAndExplicitSectionsWithoutAssumingTwoPerRow() {
        val course = parser.parse(QiangzhiFixture.table(QiangzhiFixture.course("数据结构", "1-16(周)[03-05节]")))
            .courses.single()
        assertEquals("数据结构", course.courseName)
        assertEquals("测试教师", course.teacher)
        assertEquals("测试楼A101", course.classroom)
        assertEquals(1, course.dayOfWeek)
        assertEquals(3 to 5, course.startSection to course.endSection)
        assertEquals(1 to 16, course.startWeek to course.endWeek)
    }

    @Test fun preservesContinuousDiscontinuousOddEvenAndMixedWeeksExactly() {
        val cases = mapOf(
            "1-16(周)[01-02节]" to (1..16).toList(),
            "1-2,5-6,9(周)[01-02节]" to listOf(1, 2, 5, 6, 9),
            "1-16(单周)[01-02节]" to (1..16).filter { it % 2 == 1 },
            "1-16周(双)[01-02节]" to (1..16).filter { it % 2 == 0 },
            "1-8(单),10-16(双)(周)[01-02节]" to listOf(1, 3, 5, 7, 10, 12, 14, 16),
            "1，3，5-7（周）【01-02节】" to listOf(1, 3, 5, 6, 7)
        )
        for ((time, expected) in cases) {
            val courses = parser.parse(QiangzhiFixture.table(QiangzhiFixture.course("实验课", time))).courses
            val actual = (1..20).filter { week -> courses.any { ScheduleRules.isCourseInWeek(it, week) } }
            assertEquals(time, expected, actual)
            assertEquals(0, ImportAnalyzer.analyze(courses, emptyList(), 1, 20).conflicts.size)
        }
    }

    @Test fun sharedWeekCompressionDoesNotInventOrLoseAnyWeekForEveryTenWeekBitmap() {
        for (bitmap in 0 until (1 shl 10)) {
            val weeks = (1..10).filter { bitmap and (1 shl (it - 1)) != 0 }
            val groups = compressImportWeeks(weeks)
            val expanded = groups.flatMap { group ->
                (group.start..group.end).filter { group.weekType == 0 ||
                    (group.weekType == 1 && it % 2 == 1) || (group.weekType == 2 && it % 2 == 0) }
            }
            assertEquals(weeks, expanded)
        }
    }

    @Test fun keepsDifferentCoursesInSameCellAndOneCourseAtDifferentTimes() {
        val first = QiangzhiFixture.course("程序设计", "1-8(周)[01-02节]")
        val second = QiangzhiFixture.course("线性代数", "9-16(周)[01-02节]")
        val otherDay = QiangzhiFixture.course("程序设计", "1-8(周)[05-06节]")
        val parsed = parser.parse(QiangzhiFixture.table("$first<hr>$second", otherDay))
        assertEquals(3, parsed.courses.size)
        assertEquals(listOf("程序设计", "线性代数", "程序设计"), parsed.courses.map { it.courseName })
        assertEquals(listOf(1 to 1, 1 to 1, 2 to 5), parsed.courses.map { it.dayOfWeek to it.startSection })
    }

    @Test fun readsOldBreakSeparatedCoursesAndMultipleSlotsWithoutMergingNames() {
        val old = """
            <div>高等数学<br>教师甲<br>1-4,7-8周[1-2节]<br>教学楼101<br>
            教师乙<br>9-12周[5-6节]<br>教学楼102</div>
            <div>概率论<br>教师丙<br>13-16周[1-2节]<br>教学楼103</div>
        """.trimIndent()
        val parsed = parser.parse(QiangzhiFixture.table(old)).courses
        assertEquals(listOf("高等数学", "高等数学", "高等数学", "概率论"), parsed.map { it.courseName })
        assertEquals(listOf(1 to 4, 7 to 8, 9 to 12, 13 to 16), parsed.map { it.startWeek to it.endWeek })
        assertEquals("教师乙", parsed[2].teacher)
        assertEquals("教学楼102", parsed[2].classroom)
        assertEquals(5, parsed[2].startSection)
    }

    @Test fun ignoresHiddenDuplicateViewsAndNeverParsesFormsOrScripts() {
        val course = QiangzhiFixture.course("正常课程", "1-8(周)[01-02节]")
        val hidden = "<div style='display: none'>$course</div><div hidden>未排的课程</div>"
        val parsed = parser.parse(QiangzhiFixture.table("$course$hidden<input value='credential'><script>not a course</script>"))
        assertEquals(1, parsed.courses.size)
        assertEquals("正常课程", parsed.courses.single().courseName)
    }

    @Test fun rowspanAndColspanDoNotShiftWeekdaysOrDuplicateCourses() {
        val header = "<tr><th colspan='2'>时间</th>" + (1..7).joinToString("") { "<th>星期${"一二三四五六日"[it - 1]}</th>" } + "</tr>"
        val html = "<table id='kbtable'>$header<tr><th rowspan='2'>上午</th><th>第1-2节</th>" +
            "<td rowspan='2'>${QiangzhiFixture.course("周一课程", "1-8(周)[01-02节]")}</td>" +
            "<td>${QiangzhiFixture.course("周二早课", "1-8(周)[01-02节]")}</td>" + "<td></td>".repeat(5) +
            "</tr><tr><th>第3-4节</th><td>${QiangzhiFixture.course("周二后课", "1-8(周)[03-04节]")}</td>" +
            "<td></td>".repeat(5) + "</tr></table>"
        val courses = parser.parse(html).courses
        assertEquals(listOf(1, 2, 2), courses.map { it.dayOfWeek })
        assertEquals(listOf(1, 1, 3), courses.map { it.startSection })
    }

    @Test fun usesExplicitRowSectionsAndSplitsNonConsecutiveSections() {
        val fallback = parser.parse(QiangzhiFixture.table(QiangzhiFixture.course("课堂", "1-8(周)"))).courses.single()
        assertEquals(1 to 2, fallback.startSection to fallback.endSection)
        val split = parser.parse(QiangzhiFixture.table(QiangzhiFixture.course("实验", "1-8(周)[01,02,04节]"))).courses
        assertEquals(listOf(1 to 2, 4 to 4), split.map { it.startSection to it.endSection })
    }

    @Test fun recognizesCommonVariantTableIdsAndStructuralFallback() {
        val standard = QiangzhiFixture.table(QiangzhiFixture.course("课程", "1-8(周)[01-02节]"))
        assertEquals("课程", parser.parse(standard.replace("id='kbtable'", "id='kbtable1'")).courses.single().courseName)
        assertEquals("课程", parser.parse(standard.replace("id='kbtable'", "id='customSchedule'")).courses.single().courseName)
    }

    @Test fun rejectsMissingWeeksInvalidRangesAndPartialImportsInsteadOfGuessing() {
        for (bad in listOf("[01-02节]", "8-1周[01-02节]", "0-3周[01-02节]", "1-99周[01-02节]", "待定[01-02节]")) {
            val html = QiangzhiFixture.table(QiangzhiFixture.course("有效课", "1-8(周)[01-02节]") +
                QiangzhiFixture.course("无法确认的课", bad))
            assertThrows(bad, ImportFormatException::class.java) { parser.parse(html) }
        }
        assertThrows(ImportFormatException::class.java) { parser.parse("<form><input type='password'></form>") }
        assertThrows(ImportFormatException::class.java) { parser.parse(QiangzhiFixture.table("")) }
        assertThrows(ImportFormatException::class.java) { parser.parse("<table id='kbtable'><tr><td>课表说明</td></tr></table>") }
    }

    @Test fun htmlFileFallbackDetectsQiangzhiAndHonorsGbkWithoutChangingSemesterMetadata() {
        val html = "<html><head><meta charset='GBK'></head><body>" +
            QiangzhiFixture.table(QiangzhiFixture.course("金融学", "1-2,5-6(周)[01-02节]")) + "</body></html>"
        val parsed = ImportParser(20).parseHtml(ByteArrayInputStream(html.toByteArray(charset("GBK"))))
        assertEquals(listOf("金融学", "金融学"), parsed.courses.map { it.courseName })
        assertNull(parsed.semester)
        assertNull(parsed.settings)
        assertEquals(2, ImportAnalyzer.analyze(parsed.courses, emptyList(), 7, 20).accepted.size)
        assertThrows(ImportFormatException::class.java) {
            ImportParser(20).parseHtml(ByteArrayInputStream(ByteArray(2_000_001)))
        }
    }
}

/** Synthetic public-format examples, not an export of any student's actual timetable. */
internal object QiangzhiFixture {
    fun course(name: String, time: String) = """
        <div class="kbcontent">123456-123456A<br>$name<br>
        <font title="老师">测试教师</font><br><font title="周次(节次)">$time</font><br>
        <font title="教室">测试楼A101</font></div>
    """.trimIndent()

    fun table(first: String, second: String = "") = "<table id='kbtable'><tr><th>节次</th>" +
        "一二三四五六日".map { "<th>星期$it</th>" }.joinToString("") +
        "</tr><tr><th>第1-2节</th><td>$first</td><td>$second</td>" + "<td></td>".repeat(5) + "</tr></table>"
}
