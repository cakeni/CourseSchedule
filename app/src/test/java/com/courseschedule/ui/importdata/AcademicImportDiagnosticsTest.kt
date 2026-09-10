package com.courseschedule.ui.importdata

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicImportDiagnosticsTest {
    @Test fun previewAndExportPayloadContainNoCanarySecretsOrFreeText() {
        val school = GenericAcademicImport.createCatalog(
            GenericAcademicImport.profile("zhengfang")!!,
            "https://jw.example.edu.cn/jwglxt/",
            false,
            adapterId = AcademicAdapterRegistry.ZHENGFANG_JWGLXT
        )
        val canaries = listOf(
            "P@ssword-Canary", "2026123456", "张三", "Cookie-Secret",
            "ticket-Secret", "量子力学课程", "教师王", "秘密教室"
        )
        val payload = """{
          "sourceUrl":"https://jw.example.edu.cn/jwglxt/kbcx/index?ticket=ticket-Secret#fragment",
          "html":"<form><input value='P@ssword-Canary'></form><script>Cookie-Secret</script><table><tr><th>课程名称</th><th>星期</th></tr><tr><td>量子力学课程</td><td>星期一</td></tr></table>",
          "data":{"Cookie":"Cookie-Secret","password":"P@ssword-Canary","studentId":"2026123456","courseName":"量子力学课程","teacher":"教师王","room":"秘密教室","xqj":1,"zcd":"1-8周"},
          "diagnostics":{"captureMode":"PAGE_FETCH","selectorHits":["#kbgrid_table","script"],"iframeState":"same_origin","documentsVisited":2}
        }""".trimIndent()
        val output = AcademicImportDiagnostics.create(
            "1.0.0", school, payload,
            ImportFormatException("张三 ticket-Secret", AcademicImportErrorCode.PARTIAL_PARSE)
        )
        canaries.forEach { canary -> assertFalse(canary, output.contains(canary)) }
        assertFalse(output.contains("?ticket="))
        assertFalse(output.contains("#fragment"))
        assertFalse(output.contains("P@ssword"))
        assertTrue(output.contains("课程名称"))
        val root = JsonParser.parseString(output).asJsonObject
        assertEquals(1, root.get("schemaVersion").asInt)
        assertEquals("zhengfang_jwglxt", root.get("adapterId").asString)
        assertEquals("PARTIAL_PARSE", root.get("errorCode").asString)
        assertEquals("same_origin", root.getAsJsonObject("capture").get("iframeState").asString)
    }
}
