package com.courseschedule.ui.importdata

import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.ui.settings.SettingsActivity
import com.courseschedule.viewmodel.CourseViewModel
import com.google.gson.JsonParser
import org.hamcrest.Matchers.allOf
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GenericImportUiTest {
    @Test fun cleartextRedirectsDoNotUseADomainAllowlist() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("jw.sdufe.edu.cn"))
        assertTrue(policy.isCleartextTrafficPermitted("example.com"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
    }

    @Suppress("DEPRECATION")
    @Test fun academicWebViewUsesBrowserCompatibilityAndConfirmsSpecialSchemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = AcademicWebImportActivity.schoolIntent(context, AcademicSchools.SWPU)
        ActivityScenario.launch<AcademicWebImportActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val web = activity.findViewById<WebView>(R.id.webView)
                web.stopLoading()
                assertEquals(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE,
                    web.settings.mixedContentMode
                )
                assertTrue(web.settings.javaScriptCanOpenWindowsAutomatically)
                assertFalse(web.settings.supportMultipleWindows())
                assertTrue(CookieManager.getInstance().acceptCookie())
                assertTrue(CookieManager.getInstance().acceptThirdPartyCookies(web))
                val client = web.webViewClient
                assertFalse(client.shouldOverrideUrlLoading(
                    web, "https://login.vendor.example/sso"
                ))
                assertFalse(client.shouldOverrideUrlLoading(
                    web, "http://webvpn.vendor.example/login"
                ))
                assertTrue(client.shouldOverrideUrlLoading(
                    web, "vendor-sso://login/callback"
                ))
            }
            onView(withText(R.string.academic_external_link_title)).check(matches(isDisplayed()))
        }
    }

    @Test fun settingsShowsPublicRepositoryAnnouncementAndClickableAddressCard() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("https://github.com/cakeni/CourseSchedule",
                    activity.findViewById<TextView>(R.id.tvProjectUrl).text.toString())
                assertTrue(activity.findViewById<View>(R.id.cardOpenSource).isClickable)
                assertTrue(activity.findViewById<View>(R.id.cardOpenSource).isLongClickable)
            }
        }
    }

    @Test fun schoolImportOpensSearchableDirectoryInsteadOfAskingForAWebsite() {
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[CourseViewModel::class.java].currentSemester.observe(activity) {
                    if (it != null) ready.countDown()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            onView(withId(R.id.cardImportSchool)).perform(click())
            onView(withId(R.id.etSchoolSearch)).check(matches(isDisplayed()))
                .perform(replaceText("西南石油"), closeSoftKeyboard())
            onView(withText("西南石油大学")).check(matches(isDisplayed()))
            onView(withId(R.id.etSchoolSearch)).perform(replaceText("xinan"), closeSoftKeyboard())
            onView(withText("西南石油大学")).check(matches(isDisplayed()))
            onView(withId(R.id.btnRequestSchool)).check(matches(withText(R.string.academic_request_school)))
            onView(withText(R.string.academic_generic_url_hint)).check(doesNotExist())
        }
    }

    @Test fun bundledDirectoryKeepsFullCoverageButOnlyOpensSafeReviewedOrigins() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = AcademicSchoolDirectory.entries(context)
        val raw = context.assets.open("academic_school_directory.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject.getAsJsonArray("entries")
        }
        val rejected = raw.mapNotNull { element ->
            val item = element.asJsonObject
            if (item.get("support")?.asString == "adapter_required") return@mapNotNull null
            val profile = GenericAcademicImport.profile(item.get("profile")?.asString) ?: return@mapNotNull null
            runCatching {
                GenericAcademicImport.createCatalog(profile, item.get("url").asString,
                    item.get("cleartext")?.asBoolean == true,
                    item.getAsJsonArray("authenticationUrls")?.map { it.asString }.orEmpty())
            }.exceptionOrNull()?.let { "${item.get("name").asString}: ${it.message}" }
        }
        assertTrue("bundled rows rejected at runtime: ${rejected.joinToString(" | ")}", rejected.isEmpty())
        assertEquals(raw.size(), entries.size)
        assertTrue("directory size=${entries.size}", entries.size >= 3_500)
        assertEquals("西南石油大学", entries.first().name)
        assertTrue(entries.first().verified)
        assertTrue(entries.any { it.name == "浙江大学" })
        assertTrue(entries.any { it.name == "北京大学" })
        // The built-in NUAA graduate route replaces its adapter-required catalog row.
        assertEquals(2_682, entries.count { it.canImport })
        assertEquals(884, entries.count { !it.canImport })
        assertEquals(764, entries.count { it.needsUserUrl })
        assertEquals(120, entries.count { !it.canImport && !it.needsUserUrl })
        assertEquals(1_088, entries.count { it.isCloudOnly })
        // The built-in SDUFE route upgrades the catalog's HTTP entry to WebVPN HTTPS.
        assertEquals(934, entries.count { it.allowCleartext })
        entries.filter { it.canImport }.forEach { entry ->
            val school = entry.toAcademicSchool()
            assertNotNull(entry.name, school)
            assertTrue(entry.name, school!!.loginUrl.startsWith("https://") ||
                entry.allowCleartext && school.loginUrl.startsWith("http://"))
            assertTrue(entry.name, school.allowsNavigation(school.loginUrl))
        }
        entries.filterNot { it.canImport }.forEach { assertNull(it.name, it.toAcademicSchool()) }
        val zju = entries.first { it.name == "浙江大学" }
        assertEquals("zdbk.zju.edu.cn", zju.host)
        assertTrue(zju.url.startsWith("https://"))
        val nuaa = entries.first { it.name == "南京航空航天大学" }
        assertEquals("nuaa", nuaa.builtInSchoolId)
        assertEquals("aao-eas.nuaa.edu.cn", nuaa.host)
        assertTrue(nuaa.canImport)
        val nuaaGraduate = entries.first { it.name == "南京航空航天大学 - 研究生" }
        assertEquals("nuaa_graduate", nuaaGraduate.builtInSchoolId)
        assertEquals("graduate.nuaa.edu.cn", nuaaGraduate.host)
        assertTrue(nuaaGraduate.canImport)
    }

    @Test fun genericSystemRowsAskForTheSchoolUrlInsteadOfClaimingTheyNeedAnAdapter() {
        AcademicSchoolDirectory.entries(InstrumentationRegistry.getInstrumentation().targetContext)
        ActivityScenario.launch(SchoolPickerActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                val count = activity.findViewById<TextView>(R.id.tvSchoolCount)
                val loading = activity.getString(R.string.academic_school_loading)
                if (count.text.toString() != loading) loaded.countDown()
                count.doAfterTextChanged { if (it.toString() != loading) loaded.countDown() }
            }
            assertTrue("school directory did not finish loading", loaded.await(10, TimeUnit.SECONDS))
            onView(withId(R.id.chipCommonSystem)).perform(click())
            onView(withId(R.id.etSchoolSearch)).perform(replaceText("强智教务"), closeSoftKeyboard())
            onView(allOf(withId(R.id.tvSchoolName), withText("强智教务"))).perform(click())
            onView(withId(R.id.etGenericUrl)).check(matches(isDisplayed()))
            onView(withText(R.string.academic_trust_and_open)).check(matches(isDisplayed()))
            onView(withText(R.string.academic_adapter_required_title)).check(doesNotExist())
        }
    }

    @Test fun genericHtmlCaptureCopiesOnlyScheduleMarkupAndParsesTheSnapshot() {
        val school = GenericAcademicImport.create("urp", "https://jw.example.edu.cn/student/table")
        withFixtureWebView(school) { web ->
            val fixture = """
                <input type="password" value="PRIVATE_PASSWORD"><div>PRIVATE_OUTSIDE_TABLE</div>
                <table class="displayTag"><tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th><th>教师</th><th>教室</th></tr>
                  <tr><td>测试课程</td><td>星期二</td><td>3-4节</td><td>1-2,5-6周</td><td>测试教师</td><td>测试教室</td></tr>
                </table>
            """.trimIndent()
            evaluate(web, "document.body.innerHTML = " + JSONObject.quote(fixture))
            val payload = JSONObject(evaluate(web, AcademicCaptureScript.create(school)) as String)
            assertFalse(payload.has("error"))
            assertFalse(payload.toString().contains("PRIVATE_"))
            assertFalse(payload.getString("html").contains("<input"))
            val courses = AcademicSchools.parse(school, payload.toString(), 20).courses
            assertEquals(listOf(1 to 2, 5 to 6), courses.map { it.startWeek to it.endWeek })
            assertTrue(courses.all { it.dayOfWeek == 2 && it.startSection == 3 && it.endSection == 4 })
        }
    }

    @Test fun eamsCaptureInfersDailySectionsFromItsSevenDayGrid() {
        val school = AcademicSchools.NUAA
        withFixtureWebView(school) { web ->
            evaluate(web, """
                window.unitCount = 13;
                window.table0 = {unitCounts: 91, activities: Array.from({length: 91}, function () { return []; })};
                window.table0.activities[0] = [{courseName:'测试课程', teacherName:'测试教师',
                    roomName:'测试教室', vaildWeeks:'011000'}];
            """.trimIndent())
            val payload = JSONObject(evaluate(web, AcademicCaptureScript.create(school)) as String)
            assertFalse(payload.has("error"))
            assertEquals(13, payload.getJSONObject("data").getInt("unitCount"))
            val courses = AcademicSchools.parse(school, payload.toString(), 20).courses
            assertEquals(1, courses.single().dayOfWeek)
            assertEquals(1, courses.single().startSection)
            assertEquals(listOf(1, 2), (1..20).filter { week ->
                ScheduleRules.isCourseInWeek(courses.single(), week)
            })
        }
    }

    @Test fun genericWiseduUsesPageLocalResultAndCorrectProxyBaseWithoutNativeBridge() {
        val school = GenericAcademicImport.create(AcademicSystem.WISEDU,
            "https://webvpn.example.edu.cn/http/course-upstream/jwapp/sys/wdkb/")
        withFixtureWebView(school) { web ->
            assertEquals("undefined", evaluate(web, "typeof window.CourseScheduleBridge"))
            evaluate(web, MOCK_FETCH)
            val token = "fixture-request"
            val basePath = GenericAcademicImport.wiseduBasePath(school.loginUrl)
            evaluate(web, AcademicWebImportActivity.ScriptHolder.fetchScript(token, "", basePath))
            val result = awaitResult(web, token)
            assertFalse(result.has("error"))
            val parsed = AcademicSchools.parse(school, result.getString("json"), 20)
            assertEquals("测试课程", parsed.courses.first().courseName)
            assertEquals(listOf(1 to 2, 4 to 5), parsed.courses.map { it.startWeek to it.endWeek })
            val paths = evaluate(web, "JSON.stringify(window.testRequestedPaths)").toString()
            assertTrue(paths.contains("/http/course-upstream/jwapp/sys/wdkb/modules/xskcb/xskcb.do"))
            assertFalse(result.toString().contains("PRIVATE_PASSWORD"))
            assertEquals("undefined", evaluate(web, "typeof window['__courseSchedule_fixture-request']"))
            assertEquals("{}", evaluate(web, AcademicWebImportActivity.ScriptHolder.pollResultScript(token)))
        }
    }

    @Test fun genericActivityDoesNotRegisterANativeBridgeOrRetainPastedTickets() {
        val school = GenericAcademicImport.create(AcademicSystem.WISEDU,
            "https://jw.fixture.example/jwapp/?ticket=PRIVATE_TICKET#PRIVATE_FRAGMENT")
        val intent = AcademicWebImportActivity.schoolIntent(
            InstrumentationRegistry.getInstrumentation().targetContext, school)
        assertFalse(intent.toUri(0).contains("PRIVATE_"))
        ActivityScenario.launch<AcademicWebImportActivity>(intent).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                web = activity.findViewById(R.id.webView)
                web.stopLoading()
            }
            assertEquals("undefined", evaluate(web, "typeof window.CourseScheduleBridge"))
        }
    }

    @Test fun intentRoundTripPreservesAdapterAndThreeIndependentScopes() {
        val original = GenericAcademicImport.createCatalog(
            profile = GenericAcademicImport.profile("qiangzhi")!!,
            address = "https://portal.example.edu.cn/login?ticket=PRIVATE",
            allowCleartext = false,
            authenticationUrls = listOf("https://auth.example.edu.cn/authserver/"),
            timetableUrls = listOf("https://jw.example.edu.cn/jsxsd/"),
            adapterId = AcademicAdapterRegistry.QIANGZHI_2024
        )
        val intent = AcademicWebImportActivity.schoolIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            original
        )
        assertFalse(intent.toUri(0).contains("PRIVATE"))
        val restored = AcademicWebImportActivity.schoolFromIntent(intent)!!
        assertEquals(AcademicAdapterRegistry.QIANGZHI_2024, restored.adapterId)
        assertEquals(original.loginPrefixes, restored.loginPrefixes)
        assertEquals(original.authenticationPrefixes, restored.authenticationPrefixes)
        assertEquals(original.timetablePrefixes, restored.timetablePrefixes)
        assertTrue(restored.allowsNavigation("https://portal.example.edu.cn/login"))
        assertTrue(restored.allowsNavigation("https://auth.example.edu.cn/authserver/login"))
        assertFalse(restored.allowsTimetable("https://auth.example.edu.cn/authserver/login"))
        assertTrue(restored.allowsTimetable("https://jw.example.edu.cn/jsxsd/xskb/list.do"))
    }

    @Test fun existingWiseduScriptUsesPageLocalResultAndKeepsVerifiedPaths() {
        withFixtureWebView(AcademicSchools.SWPU) { web ->
            assertEquals("undefined", evaluate(web, "typeof window.CourseScheduleBridge"))
            evaluate(web, MOCK_FETCH)
            evaluate(web, AcademicWebImportActivity.ScriptHolder.fetchScript("legacy", "2026-2027-1"))
            val result = awaitResult(web, "legacy")
            assertFalse(result.has("error"))
            assertEquals("测试课程", AcademicSchools.parse(AcademicSchools.SWPU, result.getString("json"), 20)
                .courses.first().courseName)
            val paths = evaluate(web, "JSON.stringify(window.testRequestedPaths)").toString()
            assertTrue(paths.contains("/jwapp/sys/wdkb/modules/xskcb/xskcb.do"))
        }
    }

    private fun awaitResult(web: WebView, token: String): JSONObject =
        waitForObject(web, AcademicWebImportActivity.ScriptHolder.pollResultScript(token))

    private fun waitForObject(web: WebView, script: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = JSONObject(evaluate(web, script).toString())
            if (result.has("json") || result.has("error")) return result
            Thread.sleep(75)
        }
        throw AssertionError("Synthetic Wisedu response did not finish")
    }

    private fun evaluate(web: WebView, script: String): Any? {
        val ready = CountDownLatch(1)
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { result.set(it); ready.countDown() }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return JSONTokener(result.get()).nextValue()
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun withFixtureWebView(school: AcademicSchool, action: (WebView) -> Unit) {
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            lateinit var web: WebView
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                web = WebView(activity)
                web.settings.javaScriptEnabled = true
                activity.findViewById<ViewGroup>(android.R.id.content).addView(web,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                }
                web.loadDataWithBaseURL(school.loginUrl,
                    "<html><body><input type='password' value='PRIVATE_PASSWORD'></body></html>", "text/html", "UTF-8", null)
            }
            try {
                assertTrue(loaded.await(5, TimeUnit.SECONDS))
                action(web)
            } finally {
                scenario.onActivity { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
            }
        }
    }

    private val MOCK_FETCH = """
        window.testRequestedPaths = [];
        window.fetch = function(url, options) {
          window.testRequestedPaths.push(String(url));
          const data = String(url).indexOf('dqxnxq.do') >= 0 ? {term:'2026-2027-1'} :
            {rows:[{KCM:'测试课程',SKJS:'教师',JASMC:'教室',SKXQ:'2',KSJC:'3',JSJC:'4',SKZC:'110110'}]};
          return Promise.resolve({ok:true,status:200,text:function(){return Promise.resolve(JSON.stringify(data));}});
        };
    """.trimIndent()
}
