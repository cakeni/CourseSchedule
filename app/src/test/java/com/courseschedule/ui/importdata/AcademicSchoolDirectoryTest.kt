package com.courseschedule.ui.importdata

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AcademicSchoolDirectoryTest {
    private fun assetFile() = sequenceOf(
        File("src/main/assets/academic_school_directory.json"),
        File("app/src/main/assets/academic_school_directory.json")
    ).first(File::isFile)

    @Test fun bundledCatalogKeepsEveryReviewedLocalRouteLoadable() {
        val asset = assetFile()
        val entries = AcademicSchoolDirectory.parse(asset.readText())

        assertEquals(3_566, entries.size)
        assertEquals(2_681, entries.count { it.canImport })
        assertEquals(984, entries.count { it.sourceType == "ziyan" && it.canImport })
        assertEquals(104, entries.count { it.sourceType == "ziyan" && !it.canImport })
        assertEquals(935, entries.count { it.allowCleartext })
    }

    @Test fun bundledV3MapsAdaptersAndLeavesAll578UnverifiedFamilyRowsBlocked() {
        val root = JsonParser.parseString(assetFile().readText()).asJsonObject
        assertEquals(3, root.get("schemaVersion").asInt)
        val entries = root.getAsJsonArray("entries").map { it.asJsonObject }
        assertTrue(entries.all { it.has("adapterId") })
        val expectedAdapters = mapOf(
            "zf" to AcademicAdapterRegistry.ZHENGFANG_AUTO,
            "qz" to AcademicAdapterRegistry.QIANGZHI_STANDARD,
            "jz" to AcademicAdapterRegistry.WISEDU_AUTO,
            "kingo_new" to AcademicAdapterRegistry.KINGOSOFT_NEW,
            "south_soft" to AcademicAdapterRegistry.SOUTH_SOFT
        )
        val blocked = entries.filter { row ->
            row.get("support").asString == "adapter_required" &&
                row.get("sourceType").asString in expectedAdapters
        }
        assertEquals(578, blocked.size)
        blocked.forEach { row ->
            assertEquals(expectedAdapters.getValue(row.get("sourceType").asString), row.get("adapterId").asString)
        }
        assertFalse(entries.any { it.get("support").asString == "verified" })
    }

    @Test fun bundledCatalogContainsNoSessionIdentifiers() {
        val raw = assetFile().readText()
        assertFalse(raw.contains(";jsessionid=", ignoreCase = true))
        assertFalse(Regex("[?&](?:ticket|token|session(?:id)?|sid)=", RegexOption.IGNORE_CASE)
            .containsMatchIn(raw))
    }

    @Test fun cleartextNavigationDoesNotUseADomainAllowlist() {
        val xml = sequenceOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml")
        ).first(File::isFile).readText()
        assertTrue(xml.contains("<base-config cleartextTrafficPermitted=\"true\""))
        assertFalse(xml.contains("<domain"))
    }

    @Test fun directoryAcceptsOnlyKnownProfilesAndSafeHttpsEntries() {
        val entries = AcademicSchoolDirectory.parse(
            """{
              "schemaVersion": 1,
              "entries": [
                {"id":"safe","name":"测试大学","profile":"qiangzhi","url":"https://jw.example.edu.cn/jsxsd/?ticket=private","aliases":["测试大","test"]},
                {"id":"http","name":"明文大学","profile":"qiangzhi","url":"http://jw.example.edu.cn/"},
                {"id":"unknown","name":"未知大学","profile":"not-real","url":"https://jw.example.edu.cn/"},
                {"id":"safe","name":"重复大学","profile":"qiangzhi","url":"https://other.example.edu.cn/"}
              ]
            }""".trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("测试大学", entries.single().name)
        assertEquals("https://jw.example.edu.cn/jsxsd/", entries.single().url)
        assertEquals(listOf("测试大", "test"), entries.single().aliases)
        assertTrue(entries.single().toAcademicSchool()!!.isGeneric)
    }

    @Test fun searchMatchesNameAliasHostAndProtocolButNotUnrelatedText() {
        val entry = AcademicSchoolDirectoryEntry(
            id = "sample",
            name = "示例财经大学",
            profileId = "wisedu",
            url = "https://dean.example.edu.cn/jwapp/",
            aliases = listOf("示财", "SCU")
        )
        assertTrue(entry.matches("财经"))
        assertTrue(entry.matches("scu"))
        assertTrue(entry.matches("dean.example"))
        assertTrue(entry.matches("金智"))
        assertFalse(entry.matches("强智"))
    }

    @Test fun malformedOrOversizedCatalogFailsClosed() {
        assertTrue(AcademicSchoolDirectory.parse("not-json").isEmpty())
        assertTrue(AcademicSchoolDirectory.parse("{\"schemaVersion\":2,\"entries\":[]}").isEmpty())
        assertTrue(AcademicSchoolDirectory.parse(" ".repeat(1_500_001)).isEmpty())
    }

    @Test fun versionTwoKeepsFullDirectoryStatusButOnlyOpensReviewedWebOrigins() {
        val entries = AcademicSchoolDirectory.parse(
            """{
              "schemaVersion": 2,
              "entries": [
                {"id":"legacy","name":"明文学校","profile":"qiangzhi","url":"http://jw.example.edu.cn:8080/jsxsd/?ticket=secret",
                 "support":"compatible","category":"undergraduate","sourceType":"qz","cleartext":true},
                {"id":"private-adapter","name":"专用学校","profile":null,"url":"",
                 "support":"adapter_required","category":"graduate","sourceType":"school_post","cleartext":false},
                {"id":"unsafe-ip","name":"地址学校","profile":"qiangzhi","url":"http://192.168.1.8/",
                 "support":"compatible","category":"undergraduate","sourceType":"qz","cleartext":true}
              ]
            }""".trimIndent()
        )

        assertEquals(2, entries.size)
        val legacy = entries.first { it.id == "legacy" }
        assertTrue(legacy.canImport)
        assertTrue(legacy.allowCleartext)
        assertEquals("http://jw.example.edu.cn:8080/jsxsd/", legacy.url)
        assertTrue(legacy.toAcademicSchool()!!.allowsNavigation(legacy.url))
        val privateAdapter = entries.first { it.id == "private-adapter" }
        assertFalse(privateAdapter.canImport)
        assertEquals(AcademicDirectorySupport.ADAPTER_REQUIRED, privateAdapter.support)
        assertEquals(AcademicDirectoryCategory.GRADUATE, privateAdapter.category)
    }

    @Test fun adapterRequiredEntriesKeepTypeMetadataWithoutPretendingTheyCanOpen() {
        val entries = AcademicSchoolDirectory.parse(
            """{
              "schemaVersion": 2,
              "entries": [
                {"id":"no-url","name":"无址大学","profile":null,"url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"zf",
                 "referenceUrl":"http://10.0.0.1/jwglxt/xtgl/login_slogin.html"},
                {"id":"cloud","name":"云端大学","profile":null,"url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"ziyan",
                 "referenceUrl":"https://jw.cloud.edu.cn/"},
                {"id":"custom","name":"专用大学","profile":null,"url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"suda_post"},
                {"id":"with-profile","name":"带型大学","profile":"qiangzhi","url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"qz"},
                {"id":"note-school","name":"西北农林科技大学","profile":null,"url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"jz"},
                {"id":"note-type","name":"指引大学","profile":null,"url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"nwpu_post"}
              ]
            }""".trimIndent()
        )

        val noUrl = entries.first { it.id == "no-url" }
        assertFalse(noUrl.canImport)
        assertNull(noUrl.toAcademicSchool())
        assertEquals("zhengfang", noUrl.profile!!.id)
        assertEquals("http://10.0.0.1/jwglxt/xtgl/login_slogin.html", noUrl.referenceUrl)
        assertFalse(noUrl.isCloudOnly)

        val cloud = entries.first { it.id == "cloud" }
        assertTrue(cloud.isCloudOnly)
        assertNull(cloud.profile)
        assertEquals("https://jw.cloud.edu.cn/", cloud.referenceUrl)

        val custom = entries.first { it.id == "custom" }
        assertFalse(custom.isCloudOnly)
        assertNull(custom.profile)

        val withProfile = entries.first { it.id == "with-profile" }
        assertEquals("qiangzhi", withProfile.profile!!.id)

        val noteSchool = entries.first { it.id == "note-school" }
        assertTrue(noteSchool.importHint!!.contains("个人课表"))
        val noteType = entries.first { it.id == "note-type" }
        assertTrue(noteType.importHint!!.contains("选课结果查询"))
        assertNull(noUrl.importHint)
    }

    @Test fun versionThreeCarriesAdapterAndSeparateTimetableScope() {
        val entry = AcademicSchoolDirectory.parse(
            """{
              "schemaVersion":3,
              "entries":[{
                "id":"v3-school","name":"第三版大学","profile":"qiangzhi",
                "url":"https://portal.example.edu.cn/login",
                "timetableUrls":["https://jw.example.edu.cn/jsxsd/"],
                "authenticationUrls":["https://auth.example.edu.cn/login"],
                "adapterId":"qiangzhi_2024","support":"experimental",
                "category":"undergraduate","sourceType":"qz_2024"
              }]
            }""".trimIndent()
        ).single()
        assertEquals(AcademicAdapterRegistry.QIANGZHI_2024, entry.adapterId)
        val school = entry.toAcademicSchool()!!
        assertTrue(school.allowsNavigation("https://auth.example.edu.cn/login"))
        assertFalse(school.allowsTimetable("https://auth.example.edu.cn/login"))
        assertTrue(school.allowsTimetable("https://jw.example.edu.cn/jsxsd/xskb/list.do"))
        assertFalse(school.allowsTimetable("https://portal.example.edu.cn/login"))
    }

    @Test fun versionThreeRejectsAnAdapterFromAnotherFamily() {
        assertTrue(AcademicSchoolDirectory.parse(
            """{"schemaVersion":3,"entries":[{
              "id":"mismatch","name":"错配大学","profile":"qiangzhi",
              "url":"https://jw.example.edu.cn/","adapterId":"wisedu_auto",
              "support":"experimental","category":"undergraduate","sourceType":"qz"
            }]}"""
        ).isEmpty())
    }

    @Test fun reviewedZiyanEntryCanUseIndependentLocalFallback() {
        val entry = AcademicSchoolDirectory.parse(
            """{
              "schemaVersion": 2,
              "entries": [{
                "id":"cloud-table","name":"云解析学校","profile":"topology",
                "url":"https://jw.example.edu.cn/","support":"experimental",
                "category":"undergraduate","sourceType":"ziyan"
              }]
            }""".trimIndent()
        ).single()
        assertTrue(entry.isCloudOnly)
        assertEquals(AcademicDirectorySupport.EXPERIMENTAL, entry.support)
        assertTrue(entry.canImport)
        assertEquals("topology", entry.profile!!.id)
        assertEquals("https://jw.example.edu.cn/", entry.toAcademicSchool()!!.loginUrl)
    }

    @Test fun referenceUrlIsSanitizedAndNeverLoaded() {
        val longUrl = "https://jw.example.edu.cn/path;jsessionid=secret/" + "a".repeat(400)
        val entries = AcademicSchoolDirectory.parse(
            """{
              "schemaVersion": 2,
              "entries": [
                {"id":"ref","name":"参考大学","profile":null,"url":"",
                 "support":"adapter_required","category":"undergraduate","sourceType":"zf",
                 "referenceUrl":"$longUrl"}
              ]
            }""".trimIndent()
        )
        val entry = entries.single()
        assertEquals(240, entry.referenceUrl.length)
        assertFalse(entry.referenceUrl.contains("jsessionid", ignoreCase = true))
        assertFalse('?' in entry.referenceUrl)
        assertFalse(entry.canImport)
        assertNull(entry.toAcademicSchool())
    }
}
