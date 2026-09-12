package com.courseschedule.ui.importdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicWebDisplayModeTest {

    @Test fun desktopUserAgentKeepsTheInstalledChromiumVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/125.0.0.0 Mobile Safari/537.36"

        val desktop = AcademicWebImportActivity.desktopUserAgent(mobile)

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            desktop
        )
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains("Mobile"))
    }

    @Test fun nuaaShortcutOnlyTargetsTheExpectedMenuLabels() {
        val script = AcademicWebImportActivity.ScriptHolder.nuaaGraduateTimetableScript()

        listOf("学生课表", "培养", "iframe,frame").forEach { assertTrue(script.contains(it)) }
        listOf("document.cookie", "localStorage", "sessionStorage", "password")
            .forEach { assertFalse(script.contains(it, ignoreCase = true)) }
    }

    @Test fun mainDocumentReplayOnlyCoversTrustedTopLevelGetPages() {
        val school = AcademicSchools.NUAA
        assertTrue(AcademicWebImportActivity.shouldReplayMainDocument(
            school, school.loginUrl, "GET", true
        ))
        assertTrue(AcademicWebImportActivity.shouldReplayMainDocument(
            school, school.timetableUrl + "?semester.id=1", "get", true
        ))
        assertFalse(AcademicWebImportActivity.shouldReplayMainDocument(
            school, school.timetableUrl, "POST", true
        ))
        assertFalse(AcademicWebImportActivity.shouldReplayMainDocument(
            school, school.timetableUrl, "GET", false
        ))
        assertFalse(AcademicWebImportActivity.shouldReplayMainDocument(
            school, "https://authserver.nuaa.edu.cn/authserver/login", "GET", true
        ))
        assertFalse(AcademicWebImportActivity.shouldReplayMainDocument(
            school, "https://evil.example/eams/", "GET", true
        ))
    }
}
