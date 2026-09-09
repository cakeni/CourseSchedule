package com.courseschedule.ui.importdata

import com.courseschedule.domain.ScheduleRules
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EamsScheduleParserTest {
    @Test fun parsesTable0CellsAndMergesOnlyAdjacentSections() {
        val data = JsonParser.parseString(
            """{
              "unitCount": 4,
              "activities": [
                [{"courseName":"离散数学","teacherName":"教师甲","roomName":"A101","vaildWeeks":"0110101"}],
                [{"courseName":"离散数学","teacherName":"教师甲","roomName":"A101","vaildWeeks":"0110101"}],
                [],
                [{"courseName":"实验","teacherName":"教师乙","roomName":"B202","vaildWeeks":"0010101"}]
              ]
            }""".trimIndent()
        )
        val courses = EamsScheduleParser().parse(data).courses
        val math = courses.filter { it.courseName == "离散数学" }
        assertEquals(listOf(1 to 2), math.map { it.startSection to it.endSection }.distinct())
        val mathWeeks = (1..6).filter { week -> math.any { ScheduleRules.isCourseInWeek(it, week) } }
        assertEquals(listOf(1, 2, 4, 6), mathWeeks)
        assertEquals(4, courses.first { it.courseName == "实验" }.startSection)
    }

    @Test fun matchedButIncompleteEamsDataFailsTheWholeImport() {
        val data = JsonParser.parseString(
            """{"unitCount":2,"activities":[[
              {"courseName":"有效课","teacherName":"","roomName":"","vaildWeeks":"011"},
              {"courseName":"缺周次","teacherName":"","roomName":"","vaildWeeks":""}
            ]]}"""
        )
        val error = assertThrows(ImportFormatException::class.java) {
            EamsScheduleParser().parse(data)
        }
        assertEquals(AcademicImportErrorCode.MISSING_WEEK, error.errorCode)
    }
}
