package com.courseschedule.ui.importdata

import com.google.gson.Gson
import com.courseschedule.data.entity.Course
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AcademicAdapterRegistryTest {
    private fun school(adapterId: String) = GenericAcademicImport.createCatalog(
        profile = GenericAcademicImport.profile("qiangzhi")!!,
        address = "https://jw.example.edu.cn/jsxsd/",
        allowCleartext = false,
        adapterId = adapterId
    )

    private fun payload(html: String): String = Gson().toJson(mapOf(
        "sourceUrl" to "https://jw.example.edu.cn/jsxsd/xskb/xskb_list.do",
        "html" to html,
        "term" to "2026-2027-1"
    ))

    @Test fun exactVariantReturnsNoMatchInsteadOfTryingAnotherFamilyShape() {
        val legacy = QiangzhiFixture.table(QiangzhiFixture.course("编译原理", "1-8周[1-2节]"))
        val error = assertThrows(ImportFormatException::class.java) {
            AcademicSchools.parse(school(AcademicAdapterRegistry.QIANGZHI_2017), payload(legacy), 20)
        }
        assertEquals(AcademicImportErrorCode.UNSUPPORTED_VARIANT, error.errorCode)
    }

    @Test fun matchedPartialVariantNeverImportsOnlyTheValidCourse() {
        val valid = QiangzhiFixture.course("有效课程", "1-8周[1-2节]")
        val incomplete = QiangzhiFixture.course("缺少周次", "[3-4节]")
        val error = assertThrows(ImportFormatException::class.java) {
            AcademicSchools.parse(
                school(AcademicAdapterRegistry.QIANGZHI_STANDARD),
                payload(QiangzhiFixture.table(valid + incomplete)),
                20
            )
        }
        assertEquals(AcademicImportErrorCode.MISSING_WEEK, error.errorCode)
    }

    @Test fun qz2024UsesItsOwnTooltipLayout() {
        val html = """
            <table id="kbtable"><tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
              <tr><th>第1-2节</th><td name="kbDataTd"><li class="qz-toolitiplists">
                <div class="qz-tooltipContent-title">数据库</div>
                <div class="qz-tooltipContent-detailitem">老师：教师甲</div>
                <div class="qz-tooltipContent-detailitem">地点：A101</div>
                <div class="qz-tooltipContent-detailitem">时间：1-8周[1-2节]</div>
              </li></td><td></td></tr>
            </table>
        """.trimIndent()
        val parsed = AcademicSchools.parse(
            school(AcademicAdapterRegistry.QIANGZHI_2024), payload(html), 20
        )
        assertEquals("数据库", parsed.courses.single().courseName)
        assertEquals(1 to 2, parsed.courses.single().startSection to parsed.courses.single().endSection)
    }

    @Test fun allSixQiangzhiVariantSignaturesHaveIndependentEntryPoints() {
        val standard = QiangzhiFixture.table(QiangzhiFixture.course("标准版", "1-8周[1-2节]"))
        val br = standard.replace("标准版", "换行版")
        val node = standard.replace("标准版", "节点版")
        val crazy = standard.replace("class=\"kbcontent\"", "class=\"kbcontent1\"")
            .replace("标准版", "特殊版")
        val v2017 = """
            <table class="el-table__header"><tr><th>节次</th><th>星期一</th><th>星期二</th></tr></table>
            <table class="el-table__body"><tr><td><div class="cell">第1-2节</div></td>
              <td><div class="cell">新版课程<br>教师甲<br>1-8周[1-2节]<br>A101</div></td><td></td></tr></table>
        """.trimIndent()
        val cases = listOf(
            AcademicAdapterRegistry.QIANGZHI_STANDARD to standard,
            AcademicAdapterRegistry.QIANGZHI_BR to br,
            AcademicAdapterRegistry.QIANGZHI_NODE to node,
            AcademicAdapterRegistry.QIANGZHI_CRAZY to crazy,
            AcademicAdapterRegistry.QIANGZHI_2017 to v2017
        )
        cases.forEach { (adapter, html) ->
            val parsed = AcademicSchools.parse(school(adapter), payload(html), 20)
            assertTrue(adapter, parsed.courses.isNotEmpty())
        }
    }

    @Test fun conflictingSuccessfulVariantsAreReportedAsAmbiguous() {
        fun parsed(name: String) = ParsedImport(listOf(Course(
            courseName = name,
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 8
        )))
        val result = AcademicAdapterRegistry.resolveVariants(listOf(
            AcademicAdapterParseResult.Success(parsed("课程甲")),
            AcademicAdapterParseResult.Success(parsed("课程乙"))
        ))
        assertTrue(result is AcademicAdapterParseResult.Failure)
        assertEquals(
            AcademicImportErrorCode.AMBIGUOUS_VARIANT,
            (result as AcademicAdapterParseResult.Failure).code
        )
    }
}
