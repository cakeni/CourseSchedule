package com.courseschedule.ui.importdata

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuweiPrintDataCaptureTest {

    @Test fun onlyPrintDataEndpointsOnValidatedOriginsAreCapturable() {
        assertTrue(
            ShuweiPrintDataCapture.isCapturableUrl(
                "https://jw.sues.edu.cn/student/for-std/course-table/semester/123/print-data/456?hasExperiment=true"
            )
        )
        assertTrue(
            ShuweiPrintDataCapture.isCapturableUrl(
                "https://jwxt.example.edu.cn/eams/course-table/print-data"
            )
        )
        // IP hosts, wrong schemes and other endpoints stay out of scope.
        assertFalse(
            ShuweiPrintDataCapture.isCapturableUrl(
                "http://10.1.2.3/student/for-std/course-table/print-data/1"
            )
        )
        assertFalse(
            ShuweiPrintDataCapture.isCapturableUrl(
                "https://jwxt.example.edu.cn/student/for-std/course-table/get-data?bizTypeId=2"
            )
        )
        assertFalse(ShuweiPrintDataCapture.isCapturableUrl("https://jw.example.edu.cn/"))
        assertFalse(ShuweiPrintDataCapture.isCapturableUrl("not a url"))
    }

    @Test fun wrapPayloadKeepsDataAndSourceAndRejectsBadOrOversizedBodies() {
        val source = "https://jw.sues.edu.cn/student/for-std/course-table/semester/1/print-data/2"
        val activities = """{"activities":[{"courseName":"高数","weekday":2,
            "startUnit":3,"endUnit":4,"weekIndexes":[1,2,3]}]}"""
        val payload = ShuweiPrintDataCapture.wrapPayload(activities, source, 400_000)
        assertNotNull(payload)
        val root = JsonParser.parseString(payload).asJsonObject
        assertEquals(2, root.getAsJsonObject("data").getAsJsonArray("activities")[0]
            .asJsonObject.get("weekday").asInt)
        assertEquals(source, root.get("sourceUrl").asString)
        assertEquals("", root.get("html").asString)

        assertNull(ShuweiPrintDataCapture.wrapPayload("", source, 400_000))
        assertNull(ShuweiPrintDataCapture.wrapPayload("not json", source, 400_000))
        assertNull(ShuweiPrintDataCapture.wrapPayload("\"just a string\"", source, 400_000))
        val oversized = "x".repeat(400_001)
        assertNull(ShuweiPrintDataCapture.wrapPayload(oversized, source, 400_000))
    }

    @Test fun contentTypeSplittingIsSafe() {
        assertEquals("application/json" to "utf-8", ShuweiPrintDataCapture.splitContentType(null))
        assertEquals("application/json" to "utf-8", ShuweiPrintDataCapture.splitContentType(""))
        assertEquals(
            "application/json" to "utf-8",
            ShuweiPrintDataCapture.splitContentType("application/json;charset=UTF-8")
        )
        assertEquals(
            "text/html" to "gbk",
            ShuweiPrintDataCapture.splitContentType("text/html; charset=\"GBK\"")
        )
    }

    @Test fun bodyLimitIsEnforced() {
        assertEquals(400_000, ShuweiPrintDataCapture.MAX_STASH_BYTES)
        assertEquals(4, ShuweiPrintDataCapture.trimToLimit(ByteArray(4))!!.size)
        assertNull(ShuweiPrintDataCapture.trimToLimit(ByteArray(400_001)))
    }

    @Test fun registryAllowsOnlyRegisteredTrustedGetReplay() {
        val school = GenericAcademicImport.create(
            "shuwei_new",
            "https://jw.example.edu.cn/student/for-std/course-table/"
        )
        val endpoint = "https://jw.example.edu.cn/student/for-std/course-table/semester/1/print-data/2"
        assertEquals(AcademicAdapterRegistry.SHUWEI_LOCAL, school.adapterId)
        assertTrue(AcademicAdapterRegistry.allowsReplay(school, "GET", endpoint))
        assertFalse(AcademicAdapterRegistry.allowsReplay(school, "POST", endpoint))
        assertFalse(AcademicAdapterRegistry.allowsReplay(
            school, "GET", "https://jw.example.edu.cn/student/profile"
        ))
        assertFalse(AcademicAdapterRegistry.allowsReplay(
            school, "GET", "https://other.example.edu.cn/student/for-std/course-table/print-data"
        ))
    }

    private fun assertNotNull(value: String?) {
        assertTrue("expected non-null payload", value != null)
    }
}
