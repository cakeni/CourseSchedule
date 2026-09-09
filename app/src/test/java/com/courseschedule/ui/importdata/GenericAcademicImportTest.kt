package com.courseschedule.ui.importdata

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class GenericAcademicImportTest {
    private val qiangzhi = AcademicSystem.QIANGZHI_HTML

    @Test fun exposesTheGenericSystemProfilesWithoutDuplicatingSchoolEntries() {
        assertEquals(24, GenericAcademicImport.profiles.size)
        assertEquals(24, GenericAcademicImport.profiles.map { it.id }.distinct().size)
        assertTrue(GenericAcademicImport.profiles.all { it.label.isNotBlank() && it.instructions.isNotBlank() })
        listOf("金智", "AIC", "URP", "为途", "乘方", "优慕课", "凌展", "EAMS", "南软", "奕联", "强智",
            "拓扑", "树维", "青果", "正方", "超星").forEach { keyword ->
            assertTrue(keyword, GenericAcademicImport.profiles.any { keyword in it.label })
        }
        val school = GenericAcademicImport.create("chaoxing_share", "https://kb.example.edu.cn/share/123?ticket=secret")
        assertEquals("chaoxing_share", school.genericProfileId)
        assertEquals(AcademicSystem.CHAOXING_SHARE, school.system)
        assertEquals("https://kb.example.edu.cn/share/123", school.loginUrl)
        assertEquals(4, AcademicSchools.all.size)
    }

    @Test fun unlistedSchoolCanReuseParserWithoutChangingBundledRegistry() {
        val school = GenericAcademicImport.create(qiangzhi, "jw.example.edu.cn/student/login?ticket=PRIVATE#PRIVATE")
        assertEquals("https://jw.example.edu.cn/student/login", school.loginUrl)
        assertEquals(listOf("https://jw.example.edu.cn/"), school.timetablePrefixes)
        assertTrue(school.isGeneric)
        assertFalse(school.verified)
        assertEquals(4, AcademicSchools.all.size)
        assertFalse(AcademicSchools.all.any { it.id == school.id })
        val parsed = AcademicSchools.parse(school, Gson().toJson(mapOf(
            "sourceUrl" to "https://jw.example.edu.cn/student/kb", "term" to "2026-2027-1",
            "html" to QiangzhiFixture.table(QiangzhiFixture.course("课程", "1-2,5-6(周)[01-02节]"))
        )), 20)
        assertTrue(parsed.sourceLabel.startsWith("通用强智 · jw.example.edu.cn"))
        assertEquals(listOf(1 to 2, 5 to 6), parsed.courses.map { it.startWeek to it.endWeek })
    }

    @Test fun rejectsUnsafeSchemesCredentialsPortsLocalAddressesAndAmbiguousPaths() {
        listOf("", "http://jw.example.edu.cn/", "javascript:alert(1)", "file:///data/local/",
            "https://user:password@jw.example.edu.cn/", "https://jw.example.edu.cn:8443/", "https://localhost/",
            "https://127.0.0.1/", "https://[::1]/", "https://jw.example.edu.cn./", "https://jw.example.edu.cn/a/../http/x/",
            "https://jw.example.edu.cn/http%2fx/", "https://webvpn.example.edu.cn/login").forEach {
            assertThrows(it, ImportFormatException::class.java) { GenericAcademicImport.create(qiangzhi, it) }
        }
    }

    @Test fun reviewedCatalogCanUseOnlyItsExactLegacyHttpOriginAndSafeHttpsUpgrade() {
        val profile = GenericAcademicImport.profile("qiangzhi")!!
        val school = GenericAcademicImport.createCatalog(
            profile,
            "http://jw.example.edu.cn:8080/jsxsd/?ticket=PRIVATE",
            allowCleartext = true
        )
        assertEquals("http://jw.example.edu.cn:8080/jsxsd/", school.loginUrl)
        assertTrue(school.allowCleartext)
        assertTrue(school.allowNonDefaultPort)
        assertTrue(school.allowsNavigation("http://jw.example.edu.cn:8080/student/kb"))
        assertTrue(school.allowsNavigation("https://jw.example.edu.cn/student/kb"))
        assertFalse(school.allowsNavigation("http://jw.example.edu.cn/student/kb"))
        assertFalse(school.allowsNavigation("http://cdn.example.edu.cn:8080/asset.js"))
        assertFalse(school.allowsNavigation("http://127.0.0.1:8080/"))
        assertNull(GenericAcademicImport.authenticationScope(school, "http://ids.example.edu.cn/login"))
    }

    @Test fun reviewedCatalogCanPreapproveACleartextAuthenticationScope() {
        val school = GenericAcademicImport.createCatalog(
            GenericAcademicImport.profile("topology")!!,
            "http://portal.example.edu.cn/",
            allowCleartext = true,
            authenticationUrls = listOf("http://ids.example.edu.cn/authserver/")
        )

        assertTrue(school.allowsNavigation("http://ids.example.edu.cn/authserver/login?service=portal"))
        assertFalse(school.allowsTimetable("http://ids.example.edu.cn/authserver/login"))
        assertFalse(school.allowsNavigation("http://other.example.edu.cn/"))
    }

    @Test fun reviewedCatalogRejectsUnsafeAuthenticationScopes() {
        val profile = GenericAcademicImport.profile("topology")!!
        listOf(
            "http://ids.example.edu.cn/authserver/",
            "https://127.0.0.1/authserver/",
            "https://user:password@ids.example.edu.cn/authserver/"
        ).forEach { authenticationUrl ->
            assertThrows(authenticationUrl, ImportFormatException::class.java) {
                GenericAcademicImport.createCatalog(
                    profile,
                    "https://portal.example.edu.cn/",
                    allowCleartext = false,
                    authenticationUrls = listOf(authenticationUrl)
                )
            }
        }
    }

    @Test fun sameUniversityEduCnAuthenticationMayNavigateButCanNeverBeCaptured() {
        val school = GenericAcademicImport.create(qiangzhi, "https://jw.example.edu.cn/")
        val auth = "https://authserver.example.edu.cn/authserver/login?ticket=PRIVATE"
        assertTrue(school.allowsNavigation(auth))
        assertFalse(school.allowsTimetable(auth))
        assertNull(GenericAcademicImport.authenticationScope(school, auth))
        assertFalse(school.allowsNavigation("http://authserver.example.edu.cn/authserver/login"))
        assertFalse(school.allowsNavigation("https://authserver.other.edu.cn/"))
        assertFalse(school.allowsNavigation("https://example.edu.cn.evil.example/"))
    }

    @Test fun thirdPartyAuthenticationConsentIsPerSessionAndNeverExpandsTimetableAccess() {
        val original = GenericAcademicImport.create(qiangzhi, "https://jw.example.edu.cn/")
        val auth = "https://login.example.com/authserver/login?ticket=PRIVATE"
        assertFalse(original.allowsNavigation(auth))
        assertEquals("https://login.example.com/", GenericAcademicImport.authenticationScope(original, auth))
        val allowed = GenericAcademicImport.allowAuthentication(original, auth)
        assertTrue(allowed.allowsNavigation(auth))
        assertFalse(allowed.allowsTimetable(auth))
        assertFalse(allowed.allowsNavigation("https://sub.login.example.com/"))
        assertFalse(allowed.allowsNavigation("https://login.example.com.evil.example/"))
        assertFalse(original.allowsNavigation(auth))
        assertFalse(allowed.authenticationPrefixes.any { "PRIVATE" in it })
        assertEquals(original.timetablePrefixes, allowed.timetablePrefixes)
        assertEquals("https://login.example.com/",
            GenericAcademicImport.authenticationScope(AcademicSchools.SWPU, auth))
        assertNull(GenericAcademicImport.authenticationScope(original, "http://ids.example.edu.cn/"))
    }

    @Test fun webVpnPortalAndEachProxyUpstreamNeedSeparateConsent() {
        val base = "https://webvpn.example.edu.cn/http/course-upstream/"
        val school = GenericAcademicImport.create(qiangzhi, base + "student/kb")
        assertTrue(school.allowsTimetable(base + "table"))
        assertFalse(school.allowsNavigation("https://webvpn.example.edu.cn/login"))
        val portal = GenericAcademicImport.allowAuthentication(school, "https://webvpn.example.edu.cn/login")
        assertTrue(portal.allowsNavigation("https://webvpn.example.edu.cn/login"))
        assertFalse(portal.allowsTimetable("https://webvpn.example.edu.cn/login"))
        assertFalse(portal.allowsNavigation("https://webvpn.example.edu.cn/http/other-upstream/"))
        val auth = "https://webvpn.example.edu.cn/https/auth-upstream/authserver/login"
        val authenticated = GenericAcademicImport.allowAuthentication(portal, auth)
        assertTrue(authenticated.allowsNavigation(auth))
        assertFalse(authenticated.allowsTimetable(auth))
        assertFalse(authenticated.allowsNavigation("https://webvpn.example.edu.cn/https/auth-upstream-evil/"))
    }

    @Test fun wiseduEndpointKeepsContextAndProxyPrefixInsteadOfCallingTheGatewayRoot() {
        assertEquals("/jwapp", GenericAcademicImport.wiseduBasePath("https://jw.example.edu.cn/login"))
        assertEquals("/portal/jwapp", GenericAcademicImport.wiseduBasePath("https://jw.example.edu.cn/portal/jwapp/sys/wdkb/"))
        assertEquals("/portal/gsapp", GenericAcademicImport.wiseduBasePath("https://jw.example.edu.cn/portal/gsapp/sys/wdkbapp/"))
        assertEquals("/http/course-upstream/jwapp", GenericAcademicImport.wiseduBasePath(
            "https://webvpn.example.edu.cn/http/course-upstream/jwapp/sys/wdkb/"))
    }

    @Test fun genericWiseduDoesNotInventMissingWeeksOrSilentlySkipMalformedTimes() {
        val school = GenericAcademicImport.create(AcademicSystem.WISEDU, "https://jw.example.edu.cn/")
        val row = """{"KCM":"课程","SKXQ":"2","KSJC":"3","JSJC":"4","SKZC":"110110"}"""
        val parsed = AcademicSchools.parse(school, """{"rows":[$row]}""", 20)
        assertTrue(parsed.sourceLabel.startsWith("通用金智 · jw.example.edu.cn"))
        assertEquals(listOf(1 to 2, 4 to 5), parsed.courses.map { it.startWeek to it.endWeek })
        listOf(row.replace("110110", ""), row.replace("110110", "无法确认"), row.replace("\"JSJC\":\"4\",", ""),
            row.replace("\"SKXQ\":\"2\"", "\"SKXQ\":\"2,3\"")).forEach { invalid ->
            assertThrows(ImportFormatException::class.java) {
                AcademicSchools.parse(school, """{"rows":[$row,$invalid]}""", 20)
            }
        }
    }

}
