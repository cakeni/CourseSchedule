package com.courseschedule.ui.importdata

import org.junit.Assert.assertEquals
import org.junit.Test

class WiseduScheduleParserTest {

    private val parser = WiseduScheduleParser(defaultTotalWeeks = 20)

    @Test
    fun normalizesDisplayedAutumnTerm() {
        assertEquals(
            "2026-2027-1",
            normalizeWiseduTerm("课表查看 2026-2027学年 秋季学期 更改")
        )
    }

    @Test
    fun normalizesDisplayedSpringAndCodedTerms() {
        assertEquals("2025-2026-2", normalizeWiseduTerm("2025—2026学年 春季学期"))
        assertEquals("2025-2026-1", normalizeWiseduTerm("XNXQDM=2025-2026-1"))
        assertEquals("2025-2026-2", normalizeWiseduTerm("202520262"))
    }

    @Test
    fun parsesWiseduRowsIntoCourses() {
        val json = """
            {
              "term":"2026-2027-1",
              "payload":{
                "code":"0",
                "datas":{
                  "xskcb":{
                    "rows":[
                      {
                        "KCM":"数据结构",
                        "SKJS":"张老师",
                        "JASMC":"博学楼A101",
                        "SKXQ":"2",
                        "KSJC":"3",
                        "JSJC":"4",
                        "SKZC":"11111111111111110000"
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val course = parser.parse(json).courses.single()

        assertEquals("数据结构", course.courseName)
        assertEquals("张老师", course.teacher)
        assertEquals("博学楼A101", course.classroom)
        assertEquals(2, course.dayOfWeek)
        assertEquals(3, course.startSection)
        assertEquals(4, course.endSection)
        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
        assertEquals(0, course.weekType)
    }

    @Test
    fun compressesOddWeekBitmap() {
        val json = """
            {
              "datas":{
                "xskcb":{
                  "rows":[
                    {
                      "KCM":"大学物理",
                      "SKXQ":"5",
                      "KSJC":"1",
                      "JSJC":"2",
                      "SKZC":"1010101010101010"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val course = parser.parse(json).courses.single()

        assertEquals(1, course.startWeek)
        assertEquals(15, course.endWeek)
        assertEquals(1, course.weekType)
    }

    @Test
    fun splitsIrregularWeekBitmapWithoutLosingWeeks() {
        val json = """
            {
              "datas":{
                "xskcb":{
                  "rows":[
                    {
                      "KCM":"实验课",
                      "SKXQ":"3",
                      "KSJC":"7",
                      "JSJC":"8",
                      "SKZC":"1101100000000000"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val courses = parser.parse(json).courses

        assertEquals(listOf(1 to 2, 4 to 5), courses.map { it.startWeek to it.endWeek })
    }

    @Test
    fun compressesEvenWeekBitmap() {
        val json = """
            {"rows":[{
              "KCM":"线性代数","SKXQ":"1","KSJC":"5","JSJC":"6",
              "SKZC":"0101010101010101"
            }]}
        """.trimIndent()

        val course = parser.parse(json).courses.single()

        assertEquals(2, course.startWeek)
        assertEquals(16, course.endWeek)
        assertEquals(2, course.weekType)
    }

    @Test
    fun parsesExplicitRangeWithoutMistakingItForBitmap() {
        val json = """
            {"rows":[{
              "KCM":"高等数学","SKXQ":"4","KSJC":"1","JSJC":"2",
              "SKZC":"1-16周"
            }]}
        """.trimIndent()

        val course = parser.parse(json).courses.single()

        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
        assertEquals(0, course.weekType)
    }

    @Test
    fun appliesEvenMarkerToExplicitRange() {
        val json = """
            {"rows":[{
              "KCM":"工程训练","SKXQ":"6","KSJC":"3","JSJC":"4",
              "SKZC":"1-8周(双)"
            }]}
        """.trimIndent()

        val course = parser.parse(json).courses.single()

        assertEquals(2, course.startWeek)
        assertEquals(8, course.endWeek)
        assertEquals(2, course.weekType)
    }

    @Test(expected = ImportFormatException::class)
    fun ignoresRowsWithAnAllZeroWeekBitmap() {
        val json = """
            {"rows":[{
              "KCM":"未排课程","SKXQ":"1","KSJC":"1","JSJC":"2",
              "SKZC":"0000000000000000"
            }]}
        """.trimIndent()

        parser.parse(json)
    }

    @Test
    fun keepsMultipleTimeSlotsForTheSameCourse() {
        val json = """
            {"datas":{"xskcb":{"rows":[
              {
                "KCM":"程序设计","SKJS":"李老师","JASMC":"明理楼201",
                "SKXQ":"2","KSJC":"1","JSJC":"2","SKZC":"11111111"
              },
              {
                "KCM":"程序设计","SKJS":"李老师","JASMC":"实验楼305",
                "SKXQ":"5","KSJC":"7","JSJC":"8","SKZC":"11111111"
              }
            ]}}}
        """.trimIndent()

        val courses = parser.parse(json).courses

        assertEquals(2, courses.size)
        assertEquals(listOf(2 to 1, 5 to 7), courses.map { it.dayOfWeek to it.startSection })
        assertEquals(listOf("明理楼201", "实验楼305"), courses.map { it.classroom })
    }

    @Test
    fun findsRowsInsideAnAlternateResponseEnvelope() {
        val json = """
            {"payload":{"data":{"result":{"items":[{
              "KCM":"软件工程","SKJS":"陈老师","JASMC":"明志楼A210",
              "SKXQ":"1","KSJC":"1","JSJC":"2","SKZC":"11110000"
            }]}}}}
        """.trimIndent()

        val course = parser.parse(json).courses.single()

        assertEquals("软件工程", course.courseName)
        assertEquals(1, course.startWeek)
        assertEquals(4, course.endWeek)
    }
}
