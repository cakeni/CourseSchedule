package com.courseschedule.ui.importdata

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class QiangzhiWebCaptureTest {
    private val school = AcademicSchools.SDUFE
    private val fixture = """
        <html><head><meta charset="UTF-8"></head><body>
        <div>PRIVATE_OUTSIDE_TABLE</div><input type="password" value="PRIVATE_PASSWORD">
        <select id="xnxq01id"><option value="2026-2027-1" selected>2026-2027学年第一学期</option></select>
        <table id="kbtable"><tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
        <tr><th>第1-2节</th><td><div class="kbcontent">测试课程<br>
        <font title="老师">测试教师</font><br><font title="周次(节次)">1-2,5-6(周)[01-02节]</font><br>
        <font title="教室">测试教室</font><input value="PRIVATE_IN_TABLE"></div></td><td></td></tr></table>
        </body></html>
    """.trimIndent()

    @Test fun capturesOnlyTimetableAndSelectedTermWithoutNativeBridgeOrCredentials() {
        val result = capture(school.timetableUrl + "fixture", fixture)
        assertFalse(result.has("error"))
        assertEquals("2026-2027-1", result.getString("term"))
        assertFalse(result.toString().contains("PRIVATE_"))
        assertFalse(result.getString("html").contains("<input"))
        val parsed = AcademicSchools.parse(school, result.toString(), 20)
        assertEquals(listOf(1 to 2, 5 to 6), parsed.courses.map { it.startWeek to it.endWeek })
    }

    @Test fun readsTrustedNestedFrameButSkipsAnotherSiteBehindSameWebVpnOrigin() {
        val trusted = school.timetableUrl + "fixture"
        val untrusted = "https://webvpn.sdufe.edu.cn/http/unregistered/fixture"
        val wrapper = "<iframe style='width:100%;height:320px' src='$untrusted'></iframe>" +
            "<iframe style='width:100%;height:320px' src='$trusted'></iframe>"
        val result = capture("https://webvpn.sdufe.edu.cn/", wrapper,
            mapOf(trusted to fixture, untrusted to fixture.replace("测试课程", "不可信课程")))
        assertFalse(result.has("error"))
        assertEquals(trusted, result.getString("sourceUrl"))
        assertTrue(result.getString("html").contains("测试课程"))
        assertFalse(result.getString("html").contains("不可信课程"))
    }

    @Test fun neverTreatsAnInputAsTheSemesterSelector() {
        val html = fixture.replace(
            "<select id=\"xnxq01id\"><option value=\"2026-2027-1\" selected>2026-2027学年第一学期</option></select>",
            "<input id=\"xnxq01id\" type=\"password\" value=\"2026-2027-1\">"
        )
        val result = capture(school.timetableUrl + "fixture", html)
        assertFalse(result.has("error"))
        assertEquals("", result.getString("term"))
        assertFalse(result.toString().contains("2026-2027-1"))
    }

    @Test fun refusesIdentityPagesAndReportsInaccessibleFrames() {
        assertEquals("table", capture(school.loginUrl, fixture).getString("error"))
        val foreign = "https://untrusted.example/fixture"
        val result = capture(school.timetableUrl,
            "<iframe style='width:100%;height:400px' src='$foreign'></iframe>", mapOf(foreign to fixture))
        assertEquals("frame", result.getString("error"))
    }

    @Test fun genericWebVpnSnapshotNeverReadsAnApprovedAuthenticationResource() {
        val generic = GenericAcademicImport.create(AcademicSystem.QIANGZHI_HTML,
            "https://webvpn.example.edu.cn/http/course-upstream/table")
        val auth = "https://webvpn.example.edu.cn/https/auth-upstream/login"
        val allowed = GenericAcademicImport.allowAuthentication(generic, auth)
        assertEquals("table", capture(auth, fixture, selectedSchool = allowed).getString("error"))
        assertFalse(capture(generic.loginUrl, fixture, selectedSchool = allowed).has("error"))
    }

    @Test fun oversizedTrustedDomIsRejectedBeforeNativeParsing() {
        val repeated = (1..120).joinToString("") { "<span>${"x".repeat(4096)}</span>" }
        val oversized = fixture.replace("</div></td><td></td></tr></table>",
            "$repeated</div></td><td></td></tr></table>")
        assertEquals("large", capture(school.timetableUrl + "fixture", oversized).getString("error"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun capture(url: String, html: String, frames: Map<String, String> = emptyMap(),
        selectedSchool: AcademicSchool = school): JSONObject {
        val ready = CountDownLatch(1)
        val captured = AtomicReference<String>()
        ActivityScenario.launch(ImportActivity::class.java).use { scenario ->
            var testView: WebView? = null
            scenario.onActivity { activity ->
                val webView = WebView(activity)
                testView = webView
                webView.settings.javaScriptEnabled = true
                activity.findViewById<ViewGroup>(android.R.id.content).addView(webView,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                webView.webViewClient = object : WebViewClient() {
                    // Serve only synthetic HTML from memory. No test request reaches a real school.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(
                            frames[request?.url.toString()].orEmpty().toByteArray(Charsets.UTF_8)))

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                view.evaluateJavascript(AcademicCaptureScript.create(selectedSchool)) { value ->
                                    captured.set(value)
                                    ready.countDown()
                                }
                            }
                        })
                    }
                }
                webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
            }
            try {
                assertTrue("WebView did not finish the snapshot", ready.await(20, TimeUnit.SECONDS))
                return JSONObject(JSONTokener(captured.get()).nextValue() as String)
            } finally {
                scenario.onActivity { testView?.destroy() }
            }
        }
    }
}
