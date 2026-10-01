package com.courseschedule.ui.importdata

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.courseschedule.R
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AiWebImportCredentialsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun credentialsAreEncryptedSeparatedPersistentAndClearable() {
        val prefix = "credential_test_${System.nanoTime()}"
        val folder = File(context.noBackupFilesDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefix, mode)
        }
        try {
            val store = AiWebCredentials(isolated)
            assertEquals("", store.load(AiWebProvider.DEEPSEEK))
            store.save(AiWebProvider.DEEPSEEK, "\u200btest-deepseek-credential\u0001")
            assertTrue(runCatching { store.save(AiWebProvider.DEEPSEEK, "test-\u0001credential") }.isFailure)
            assertEquals("test-deepseek-credential", store.load(AiWebProvider.DEEPSEEK))
            val encrypted = folder.listFiles()!!.single().readText()
            assertFalse(encrypted.contains("test-deepseek-credential"))
            assertEquals("test-deepseek-credential", AiWebCredentials(isolated).load(AiWebProvider.DEEPSEEK))
            assertEquals("", store.load(AiWebProvider.OPENAI))
            store.save(AiWebProvider.OPENAI, "test-openai-credential")
            assertEquals(AiWebProvider.OPENAI, AiWebCredentials(isolated).lastProvider())
            store.save(AiWebProvider.DEEPSEEK, "test-updated-credential")
            assertEquals("test-updated-credential", store.load(AiWebProvider.DEEPSEEK))
            assertEquals("test-openai-credential", store.load(AiWebProvider.OPENAI))
            assertFalse(context.getSharedPreferences(prefix, 0).all.values.any { it.toString().contains("credential") })
            val dsFile = File(folder, "academic_ai_DEEPSEEK.json")
            val openFile = File(folder, "academic_ai_OPENAI.json")
            openFile.writeBytes(dsFile.readBytes())
            assertTrue(runCatching { store.load(AiWebProvider.OPENAI) }.isFailure)
            store.save(AiWebProvider.OPENAI, "test-openai-credential")
            store.remove(AiWebProvider.DEEPSEEK)
            assertEquals("", AiWebCredentials(isolated).load(AiWebProvider.DEEPSEEK))
            assertEquals("test-openai-credential", store.load(AiWebProvider.OPENAI))
        } finally {
            folder.deleteRecursively()
            context.deleteSharedPreferences(prefix)
        }
    }

    @Test fun dialogRestoresProviderKeysAfterReopeningAndActivityRecreation() {
        // Run the UI case only on a test app that has no user credentials.
        assumeFalse(AiWebProvider.values().any {
            File(context.noBackupFilesDir, "academic_ai_${it.name}.json").exists()
        })
        val store = AiWebCredentials(context)
        val originalProvider = store.lastProvider()
        try {
            ActivityScenario.launch<AcademicWebImportActivity>(
                AcademicWebImportActivity.schoolIntent(context, AcademicSchools.SWPU)
            ).use { scenario ->
                preparePage(scenario)
                openDialog(scenario)
                onView(withId(R.id.radioAiDeepSeek)).perform(click())
                awaitKeyReady()
                onView(withId(R.id.etAiApiKey)).perform(replaceText("test-\u0001credential"), closeSoftKeyboard())
                onView(withId(R.id.btnSaveAiKey)).perform(click())
                onView(withText("API Key 含有空格、不可见字符或非英文字符，请重新复制完整 Key")).check(matches(isDisplayed()))
                assertEquals("", store.load(AiWebProvider.DEEPSEEK))
                onView(withId(R.id.etAiApiKey)).perform(replaceText("test-deepseek-credential\u0001"), closeSoftKeyboard())
                onView(withId(R.id.btnSaveAiKey)).perform(click())
                awaitKeyReady()
                onView(withId(R.id.etAiApiKey)).check(matches(withText("test-deepseek-credential")))
                onView(withId(R.id.tvAiKeyStatus)).check(matches(withText(context.getString(R.string.ai_key_saved_format, "DeepSeek"))))
                onView(withText(R.string.cancel)).perform(click())
                openDialog(scenario)
                onView(withId(R.id.etAiApiKey)).check(matches(withText("test-deepseek-credential")))
                onView(withId(R.id.radioAiOpenAi)).perform(click())
                awaitKeyReady()
                onView(withId(R.id.etAiApiKey)).check(matches(withText("")))
                    .perform(replaceText("test-openai-credential"), closeSoftKeyboard())
                onView(withId(R.id.btnSaveAiKey)).perform(click())
                awaitKeyReady()
                onView(withText(R.string.cancel)).perform(click())
                scenario.recreate()
                preparePage(scenario)
                openDialog(scenario)
                onView(withId(R.id.radioAiOpenAi)).check(matches(isChecked()))
                onView(withId(R.id.etAiApiKey)).check(matches(withText("test-openai-credential")))
                onView(withId(R.id.radioAiDeepSeek)).perform(click())
                awaitKeyReady()
                onView(withId(R.id.etAiApiKey)).check(matches(withText("test-deepseek-credential")))
                onView(withId(R.id.btnClearAiKey)).perform(click())
                awaitKeyReady()
                onView(withId(R.id.etAiApiKey)).check(matches(withText("")))
                onView(withText(R.string.cancel)).perform(click())
                openDialog(scenario)
                onView(withId(R.id.etAiApiKey)).check(matches(withText("test-openai-credential")))
                onView(withId(R.id.radioAiDeepSeek)).perform(click())
                awaitKeyReady()
                onView(withId(R.id.etAiApiKey)).check(matches(withText("")))
            }
        } finally {
            AiWebProvider.values().forEach { store.remove(it) }
            context.getSharedPreferences("academic_ai_provider", 0).edit().putString("provider", originalProvider.name).commit()
        }
    }

    private fun preparePage(scenario: ActivityScenario<AcademicWebImportActivity>) {
        val loaded = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = activity.findViewById<WebView>(R.id.webView)
            web.stopLoading()
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
            }
            val url = "https://deanservices.swpu.edu.cn/jwapp/sys/homeapp/"
            web.loadDataWithBaseURL(url,
                "<html><body><table><tr><td>测试课表</td></tr></table></body></html>", "text/html", "UTF-8", url)
        }
        assertTrue(loaded.await(5, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            assertTrue(AcademicSchools.SWPU.allowsTimetable(activity.findViewById<WebView>(R.id.webView).url))
        }
    }

    private fun openDialog(scenario: ActivityScenario<AcademicWebImportActivity>) {
        scenario.onActivity { activity ->
            AcademicWebImportActivity::class.java.getDeclaredField("pageHadError").apply { isAccessible = true }.setBoolean(activity, false)
        }
        onView(withId(R.id.btnAiRecognition)).check(matches(isDisplayed())).perform(click())
        awaitKeyReady()
    }

    private fun awaitKeyReady() {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = runCatching { onView(withId(R.id.etAiApiKey)).check(matches(isEnabled())) }
            if (result.isSuccess) return
            lastFailure = result.exceptionOrNull()
            Thread.sleep(75)
        }
        throw AssertionError("Credential dialog did not finish loading", lastFailure)
    }
}
