package com.courseschedule.ui.importdata

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AcademicSchoolTest {
    @Test fun schoolEntriesHaveExplicitStatusAndUnknownIdsNeverFallBackToAnotherSchool() {
        assertEquals(4, AcademicSchools.all.size)
        assertEquals(AcademicSchools.all.size, AcademicSchools.all.distinctBy { it.id }.size)
        assertTrue(AcademicSchools.SWPU.verified)
        assertFalse(AcademicSchools.SDUFE.verified)
        assertFalse(AcademicSchools.NUAA.verified)
        assertFalse(AcademicSchools.NUAA_GRADUATE.verified)
        assertFalse(AcademicSchools.NUAA.isGraduate)
        assertTrue(AcademicSchools.NUAA_GRADUATE.isGraduate)
        assertNull(AcademicSchools.find("made-up-school"))
        assertNull(AcademicSchools.find(null))
        AcademicSchools.all.forEach {
            assertTrue(it.allowsNavigation(it.loginUrl))
            assertTrue(it.allowsNavigation(it.timetableUrl))
        }
    }

    @Test fun onlyConfiguredSchemesExactHostsAndExpectedPortsAreTrusted() {
        val representatives = mapOf(
            AcademicSchools.SWPU to "deanservices.swpu.edu.cn",
            AcademicSchools.SDUFE to "jw.sdufe.edu.cn",
            AcademicSchools.NUAA to "authserver.nuaa.edu.cn",
            AcademicSchools.NUAA_GRADUATE to "graduate.nuaa.edu.cn"
        )
        for ((school, host) in representatives) {
            assertTrue(school.allowsNavigation("https://$host/"))
            assertTrue(school.allowsNavigation("HTTPS://${host.uppercase()}:443/"))
            val cleartextAllowed = school.allowCleartext && host in school.cleartextHosts
            assertEquals(cleartextAllowed, school.allowsNavigation("http://$host/"))
            for (url in listOf(null, "", "https://$host.evil.example/",
                "https://$host@evil.example/", "https://user@$host/", "https://$host:8443/", "file:///etc/passwd",
                "javascript:alert(1)", "intent://$host/", "https://$host./", "https://$host\\@evil.example/",
                "https://$host/a/../http/evil", "https://$host/%2e%2e/http/evil")) {
                assertFalse("Rejected $url", school.allowsNavigation(url))
            }
        }
    }

    @Test fun nuaaUndergraduateAllowsOnlyItsObservedCasAndTimetableCallbackChain() {
        val school = AcademicSchools.NUAA
        assertEquals(AcademicSystem.EAMS, school.system)
        assertEquals("https://aao-eas.nuaa.edu.cn/eams/homeExt.action", school.loginUrl)
        assertEquals(
            "https://aao-eas.nuaa.edu.cn/eams/courseTableForStd.action",
            school.timetableUrl
        )
        assertTrue(school.allowsTimetable("https://aao-eas.nuaa.edu.cn/eams/courseTableForStd.action"))
        assertTrue(school.allowsTimetable("http://aao-eas.nuaa.edu.cn/eams/courseTableForStd.action"))
        assertTrue(school.allowsNavigation(
            "https://authserver.nuaa.edu.cn/authserver/login?service=http%3A%2F%2Faao-eas.nuaa.edu.cn%2Feams%2FlocalLogin.action"
        ))
        assertFalse(school.allowsTimetable("https://authserver.nuaa.edu.cn/authserver/login"))
        val callback = "http://aao-eas.nuaa.edu.cn/eams/localLogin.action?ticket=hidden%2Fvalue"
        assertTrue(school.allowsNavigation(callback))
        assertTrue(school.allowsTimetable(callback))
        assertEquals(
            "https://aao-eas.nuaa.edu.cn/eams/localLogin.action?ticket=hidden%2Fvalue",
            upgradedAcademicHttpsUrl(school, callback)
        )
        assertTrue(school.allowsNavigation("http://authserver.nuaa.edu.cn/authserver/login"))
        assertFalse(school.allowsTimetable("http://authserver.nuaa.edu.cn/authserver/login"))
        assertEquals(
            "https://authserver.nuaa.edu.cn/authserver/login?service=http%3A%2F%2Faao-eas.nuaa.edu.cn%2Feams%2F",
            upgradedAcademicHttpsUrl(school,
                "http://authserver.nuaa.edu.cn/authserver/login?service=http%3A%2F%2Faao-eas.nuaa.edu.cn%2Feams%2F")
        )
        assertTrue(school.allowsNavigation("https://other.nuaa.edu.cn/"))
        assertFalse(school.allowsTimetable("https://other.nuaa.edu.cn/"))
        assertFalse(school.allowsNavigation("https://aao-eas.nuaa.edu.cn.evil.example/eams/"))
        assertFalse(school.allowsNavigation("http://other.nuaa.edu.cn/eams/"))
        val captureScript = AcademicCaptureScript.create(school)
        assertTrue(captureScript.contains("http://aao-eas.nuaa.edu.cn/eams/"))
        assertFalse(captureScript.contains("http://authserver.nuaa.edu.cn/authserver/"))
        assertEquals("https://other.nuaa.edu.cn/", upgradedAcademicHttpsUrl(school, "http://other.nuaa.edu.cn/"))
    }

    @Test fun nuaaGraduateUsesTheOfficialSouthSoftScopeWithoutCapturingAuthentication() {
        val school = AcademicSchools.NUAA_GRADUATE
        assertTrue(school.allowsTimetable("https://graduate.nuaa.edu.cn/gmis5/student/course"))
        assertTrue(school.allowsNavigation("https://authserver.nuaa.edu.cn/authserver/login"))
        assertFalse(school.allowsTimetable("https://authserver.nuaa.edu.cn/authserver/login"))
        assertFalse(school.allowsNavigation("http://graduate.nuaa.edu.cn/gmis5/home/stulogin"))
        assertEquals(
            "https://graduate.nuaa.edu.cn/gmis5/oauthLogin/njhk?data=123",
            upgradedAcademicHttpsUrl(school, "http://graduate.nuaa.edu.cn/gmis5/oauthLogin/njhk?data=123")
        )
        assertEquals(
            "https://authserver.nuaa.edu.cn/authserver/login?service=http%3a%2f%2fgraduate.nuaa.edu.cn%2fgmis5%2f",
            upgradedAcademicHttpsUrl(school,
                "http://authserver.nuaa.edu.cn/authserver/login?service=http%3a%2f%2fgraduate.nuaa.edu.cn%2fgmis5%2f")
        )
        assertFalse(school.allowsNavigation("https://graduate.nuaa.edu.cn.evil.example/gmis5/"))
    }

    @Test fun webVpnOriginDoesNotGrantAccessToOtherProxiedSitesOrIdentityPages() {
        val school = AcademicSchools.SDUFE
        assertTrue(school.allowsNavigation(school.loginUrl + "?cas_login=true"))
        school.authenticationPrefixes.forEach { prefix ->
            assertTrue(school.allowsNavigation(prefix + "authserver/login?service=https%3A%2F%2Fwebvpn.sdufe.edu.cn"))
            assertFalse(school.allowsTimetable(prefix + "authserver/login"))
        }
        assertFalse(school.allowsTimetable(school.loginUrl))
        assertTrue(school.allowsTimetable(school.timetableUrl + "tkglAction.do?method=goListKb"))
        for (path in listOf("/http/unregistered/", "/https/unknown/", "/https-443/unknown/",
            "/http%2funregistered/", "//http/unknown/", "/x/../http/unknown/")) {
            assertFalse(path, school.allowsNavigation("https://webvpn.sdufe.edu.cn$path"))
        }
        assertFalse(school.allowsNavigation("https://deanservices.swpu.edu.cn/"))
        assertFalse(AcademicSchools.SWPU.allowsNavigation("https://webvpn.sdufe.edu.cn/"))
    }

    @Test fun casPublicRedirectChainIsAllowedWithoutAllowingAnotherUpstream() {
        val school = AcademicSchools.SDUFE
        val route = "77726476706e69737468656265737421f9f352d234347d567b468ca88d1b203b"
        // Observed public 302 -> 302 -> 200 chain; no account or session token is needed.
        for (proxyScheme in listOf("http", "https")) {
            val url = "https://webvpn.sdufe.edu.cn/$proxyScheme/$route/authserver/login"
            assertTrue(school.allowsNavigation(url))
            assertFalse(school.allowsTimetable(url))
            assertFalse(school.allowsNavigation(url.replace("/$route/", "/${route}0/")))
        }
    }

    @Test fun qiangzhiPayloadUsesCurrentPipelineAndKeepsSourceTermWithoutInventingDates() {
        val school = AcademicSchools.SDUFE
        val payload = Gson().toJson(mapOf("sourceUrl" to school.timetableUrl, "term" to "2026-2027-1",
            "html" to QiangzhiFixture.table(QiangzhiFixture.course("会计学", "1-8(周)[01-02节]"))))
        val parsed = AcademicSchools.parse(school, payload, 20)
        assertEquals("山东财经大学教务系统 · 2026-2027-1", parsed.sourceLabel)
        assertEquals("会计学", parsed.courses.single().courseName)
        assertNull(parsed.semester)
        assertNull(parsed.settings)
        assertEquals(1, ImportAnalyzer.analyze(parsed.courses, emptyList(), 7, 20).accepted.size)
        for (bad in listOf(payload.replace(school.timetableUrl, school.loginUrl), "{}", "null", "[]", "not-json",
            " ".repeat(AcademicSchools.MAX_PAYLOAD_CHARS + 1))) {
            assertThrows(ImportFormatException::class.java) { AcademicSchools.parse(school, bad, 20) }
        }
    }

    @Test fun swpuStillDispatchesToExistingWiseduParser() {
        val payload = """{"rows":[{"KCM":"软件工程","SKJS":"教师","JASMC":"A101",
            "SKXQ":"2","KSJC":"3","JSJC":"4","SKZC":"110110"}]}"""
        val parsed = AcademicSchools.parse(AcademicSchools.SWPU, payload, 20)
        assertEquals("西南石油大学教务系统", parsed.sourceLabel)
        assertEquals(listOf(1 to 2, 4 to 5), parsed.courses.map { it.startWeek to it.endWeek })
    }
}
