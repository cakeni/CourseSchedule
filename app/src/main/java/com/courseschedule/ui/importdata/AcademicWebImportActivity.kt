package com.courseschedule.ui.importdata

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.databinding.ActivityAcademicWebImportBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

class AcademicWebImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCHEDULE_JSON = "schedule_json"
        const val EXTRA_TOTAL_WEEKS = "total_weeks"
        const val EXTRA_SCHOOL_ID = "school_id"
        private const val EXTRA_GENERIC_SYSTEM = "generic_system"
        private const val EXTRA_GENERIC_URL = "generic_url"
        private const val EXTRA_GENERIC_PROFILE = "generic_profile"
        private const val EXTRA_GENERIC_ADAPTER = "generic_adapter"
        private const val EXTRA_GENERIC_NAME = "generic_name"
        private const val EXTRA_GENERIC_CLEARTEXT = "generic_cleartext"
        private const val EXTRA_GENERIC_AUTH_URLS = "generic_auth_urls"
        private const val EXTRA_GENERIC_LOGIN_URLS = "generic_login_urls"
        private const val EXTRA_GENERIC_TIMETABLE_URLS = "generic_timetable_urls"
        const val EXTRA_GENERIC_HINT = "generic_hint"
        private const val MIN_IDENTIFYING_DISPLAY_MILLIS = 320L
        private const val MIN_PARSING_DISPLAY_MILLIS = 360L
        private const val SUCCESS_DISPLAY_MILLIS = 1_100L
        private const val REQUEST_TIMEOUT_MILLIS = 45_000L
        private val SAFE_REPLAY_HEADERS = setOf(
            "accept", "accept-language", "user-agent", "x-requested-with"
        )

        internal fun schoolFromIntent(intent: Intent): AcademicSchool? {
            val id = intent.getStringExtra(EXTRA_SCHOOL_ID)
            if (id != GenericAcademicImport.SCHOOL_ID) return AcademicSchools.find(id)
            return runCatching {
                val profile = intent.getStringExtra(EXTRA_GENERIC_PROFILE)
                val allowCleartext = intent.getBooleanExtra(EXTRA_GENERIC_CLEARTEXT, false)
                val created = if (profile != null) {
                    val definition = GenericAcademicImport.profile(profile)
                        ?: throw ImportFormatException("不支持的教务系统类型")
                    GenericAcademicImport.createCatalog(
                        definition,
                        intent.getStringExtra(EXTRA_GENERIC_URL).orEmpty(),
                        allowCleartext,
                        intent.getStringArrayListExtra(EXTRA_GENERIC_AUTH_URLS).orEmpty(),
                        intent.getStringArrayListExtra(EXTRA_GENERIC_TIMETABLE_URLS).orEmpty(),
                        intent.getStringExtra(EXTRA_GENERIC_ADAPTER)
                            ?: AcademicAdapterRegistry.defaultAdapterId(profile),
                        intent.getStringArrayListExtra(EXTRA_GENERIC_LOGIN_URLS).orEmpty()
                    )
                }
                else GenericAcademicImport.create(
                    AcademicSystem.valueOf(intent.getStringExtra(EXTRA_GENERIC_SYSTEM).orEmpty()),
                    intent.getStringExtra(EXTRA_GENERIC_URL).orEmpty())
                intent.getStringExtra(EXTRA_GENERIC_NAME)?.takeIf(String::isNotBlank)
                    ?.let { created.copy(name = it) } ?: created
            }.getOrNull()
        }

        internal fun schoolIntent(context: Context, school: AcademicSchool): Intent =
            Intent(context, AcademicWebImportActivity::class.java)
                .putExtra(EXTRA_SCHOOL_ID, school.id).apply {
                    if (school.isGeneric) {
                        putExtra(EXTRA_GENERIC_SYSTEM, school.system.name)
                        putExtra(EXTRA_GENERIC_URL, school.loginUrl)
                        putExtra(EXTRA_GENERIC_NAME, school.name)
                        putExtra(EXTRA_GENERIC_CLEARTEXT, school.allowCleartext)
                        putStringArrayListExtra(
                            EXTRA_GENERIC_AUTH_URLS,
                            ArrayList(school.authenticationPrefixes)
                        )
                        putStringArrayListExtra(
                            EXTRA_GENERIC_LOGIN_URLS,
                            ArrayList(school.loginPrefixes)
                        )
                        putStringArrayListExtra(
                            EXTRA_GENERIC_TIMETABLE_URLS,
                            ArrayList(school.timetablePrefixes)
                        )
                        putExtra(EXTRA_GENERIC_ADAPTER, school.adapterId)
                        school.genericProfileId?.let { putExtra(EXTRA_GENERIC_PROFILE, it) }
                    }
                }
    }

    private lateinit var binding: ActivityAcademicWebImportBinding
    @Volatile private lateinit var school: AcademicSchool
    private var activeRequestToken: String? = null
    private var timeoutJob: Job? = null
    private var resultPollJob: Job? = null
    private var externalNavigationDialog: androidx.appcompat.app.AlertDialog? = null
    private var sslErrorDialog: androidx.appcompat.app.AlertDialog? = null
    private val acceptedSslHosts = mutableSetOf<String>()
    private var pageHadError = false
    private var pendingDiagnostic: String? = null
    private val diagnosticExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val diagnostic = pendingDiagnostic ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runCatching {
                contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { writer ->
                    writer.write(diagnostic)
                } ?: error("无法创建诊断文件")
            }.isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@AcademicWebImportActivity,
                    if (saved) R.string.academic_diagnostic_saved else R.string.academic_diagnostic_save_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Latest /print-data JSON body replayed from the trusted origin (new ShuWei). */
    @Volatile private var shuweiStash: Pair<String, String>? = null
    private val statusInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val totalWeeks by lazy {
        intent.getIntExtra(EXTRA_TOTAL_WEEKS, 20).coerceIn(1, 52)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedSchool = schoolFromIntent(intent)
        if (selectedSchool == null) {
            finish()
            return
        }
        school = selectedSchool
        binding = ActivityAcademicWebImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val genericHost = academicWebUri(
            school.loginUrl,
            school.allowCleartext,
            school.allowNonDefaultPort
        )?.host
        binding.toolbar.title = if (!school.isGeneric || school.name != genericHost) {
            getString(R.string.academic_school_title, school.name)
        } else getString(R.string.academic_generic_title)
        if (school.isGeneric) binding.toolbar.subtitle = school.name
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }

        configureWebView()
        val readsDisplayedPage = school.system != AcademicSystem.WISEDU
        binding.tvSchoolHint.visibility = if (readsDisplayedPage || school.isGeneric) View.VISIBLE else View.GONE
        binding.btnOpenSchool.visibility = if (readsDisplayedPage || school.isGeneric) View.VISIBLE else View.GONE
        if (!school.isGeneric) {
            when (school.id) {
                AcademicSchools.NUAA.id -> binding.tvSchoolHint.setText(R.string.academic_nuaa_hint)
                AcademicSchools.NUAA_GRADUATE.id -> binding.tvSchoolHint.setText(R.string.academic_nuaa_graduate_hint)
            }
        } else {
            val profile = GenericAcademicImport.profile(school.genericProfileId)
            val profileHint = getString(
                R.string.academic_generic_profile_hint,
                profile?.label ?: school.system.name,
                profile?.instructions ?: getString(R.string.academic_generic_check_preview)
            )
            binding.tvSchoolHint.text = if (school.allowCleartext) {
                getString(R.string.academic_cleartext_inline_hint, profileHint)
            } else profileHint
            binding.btnOpenSchool.setText(R.string.academic_return_entry)
        }
        // Per-school usage note adapted from WakeUp's import steps.
        intent.getStringExtra(EXTRA_GENERIC_HINT)?.takeIf(String::isNotBlank)?.let { hint ->
            binding.tvSchoolHint.text = buildString {
                append(binding.tvSchoolHint.text)
                append("\n\n")
                append(getString(R.string.academic_school_hint_label, hint))
            }
        }
        binding.btnOpenSchool.setOnClickListener {
            binding.webView.loadUrl(school.timetableUrl)
        }
        if (readsDisplayedPage) binding.btnFetchSchedule.setText(R.string.academic_read_displayed_term)
        binding.btnFetchSchedule.setOnClickListener { fetchCurrentSchedule() }
        setImportStatus(false, getString(R.string.academic_waiting_login))
        binding.webView.loadUrl(school.loginUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            @Suppress("DEPRECATION")
            saveFormData = false
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleNavigation(view, request?.url?.toString())
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleNavigation(view, url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && school.isGeneric && school.system == AcademicSystem.SHUWEI &&
                    AcademicAdapterRegistry.allowsReplay(
                        school, request.method, request.url.toString()
                    )) {
                    val capture = captureShuweiPrintData(request)
                    if (capture != null) return capture
                }
                return super.shouldInterceptRequest(view, request)
            }

            /** Replays a /print-data GET with the session cookie, stashes the JSON, serves it unchanged. */
            private fun captureShuweiPrintData(request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!AcademicAdapterRegistry.allowsReplay(school, request.method, url) ||
                    !ShuweiPrintDataCapture.isCapturableUrl(url, school.allowCleartext)) return null
                // A new term request supersedes the old cache even when this request fails.
                shuweiStash = null
                val connection = runCatching {
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                        // Never carry the authenticated replay across an HTTP redirect.
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        CookieManager.getInstance().getCookie(url)
                            ?.takeIf(String::isNotBlank)
                            ?.let { setRequestProperty("Cookie", it) }
                        request.requestHeaders.forEach { (name, value) ->
                            if (name.lowercase() in SAFE_REPLAY_HEADERS) {
                                setRequestProperty(name, value)
                            }
                        }
                    }
                }.getOrNull() ?: return null
                return runCatching {
                    connection.connect()
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                    val input = connection.inputStream
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16_384)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > ShuweiPrintDataCapture.MAX_STASH_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                    val body = output.toByteArray()
                    val text = String(body, Charsets.UTF_8)
                    shuweiStash = text to url
                    val (mime, encoding) = ShuweiPrintDataCapture.splitContentType(
                        connection.contentType
                    )
                    WebResourceResponse(mime, encoding, 200, "OK", emptyMap(),
                        ByteArrayInputStream(body))
                }.getOrElse {
                    runCatching { connection.disconnect() }
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                shuweiStash = null
                pageHadError = false
                invalidateRequest()
                if (school.isGeneric) binding.toolbar.subtitle = webNavigationHost(url)
                setImportStatus(false, getString(R.string.academic_waiting_login))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!pageHadError && activeRequestToken == null && isWebNavigationUrl(url)) {
                    setImportStatus(false, getString(R.string.academic_open_timetable_hint))
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageHadError = true
                    invalidateRequest()
                    setImportStatus(false, getString(R.string.academic_page_load_failed))
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    pageHadError = true
                    invalidateRequest()
                    setImportStatus(false, getString(R.string.academic_page_load_failed))
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handleSslError(handler, error)
            }
        }
    }

    private fun handleSslError(handler: SslErrorHandler?, error: SslError?) {
        handler ?: return
        val url = error?.url.orEmpty()
        val host = webNavigationHost(url)
        if (host == null) {
            handler.cancel()
            pageHadError = true
            invalidateRequest()
            setImportStatus(false, getString(R.string.academic_ssl_error))
            return
        }
        if (host in acceptedSslHosts) {
            handler.proceed()
            return
        }
        if (sslErrorDialog != null || isFinishing || isDestroyed) {
            handler.cancel()
            return
        }
        pageHadError = true
        invalidateRequest()
        setImportStatus(false, getString(R.string.academic_ssl_warning))
        var handled = false
        fun reject() {
            if (handled) return
            handled = true
            handler.cancel()
            setImportStatus(false, getString(R.string.academic_ssl_error))
        }
        sslErrorDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.academic_ssl_warning_title)
            .setMessage(getString(R.string.academic_ssl_warning_detail, host))
            .setPositiveButton(R.string.academic_ssl_continue) { _, _ ->
                handled = true
                acceptedSslHosts += host
                pageHadError = false
                handler.proceed()
                setImportStatus(false, getString(R.string.academic_waiting_login))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> reject() }
            .setOnCancelListener { reject() }
            .setOnDismissListener {
                if (!handled) reject()
                sslErrorDialog = null
            }
            .create().also { it.show() }
    }

    private fun handleNavigation(view: WebView?, url: String?): Boolean {
        val target = url?.trim().orEmpty()
        return when (navigationScheme(target)) {
            "http", "https", "about", "data", "javascript", "blob" -> false
            null -> {
                showLinkError(R.string.academic_link_invalid)
                true
            }
            else -> {
                confirmExternalNavigation(view ?: binding.webView, target)
                true
            }
        }
    }

    private fun confirmExternalNavigation(view: WebView, url: String) {
        if (isFinishing || isDestroyed || externalNavigationDialog != null) return
        val externalIntent = externalNavigationIntent(url)
        if (externalIntent == null) {
            showLinkError(R.string.academic_link_invalid)
            return
        }
        externalNavigationDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.academic_external_link_title)
            .setMessage(getString(R.string.academic_external_link_detail, url.take(240)))
            .setPositiveButton(R.string.academic_external_link_open) { _, _ ->
                try {
                    startActivity(externalIntent)
                } catch (_: RuntimeException) {
                    openIntentFallbackOrShowError(view, externalIntent)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { externalNavigationDialog = null }
            .create().also { it.show() }
    }

    private fun externalNavigationIntent(url: String): Intent? = runCatching {
        val intent = if (navigationScheme(url) == "intent") {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent
    }.getOrNull()

    private fun openIntentFallbackOrShowError(view: WebView, intent: Intent) {
        intent.getStringExtra("browser_fallback_url")
            ?.takeIf(::isWebNavigationUrl)
            ?.let(view::loadUrl)
            ?: showLinkError(R.string.academic_link_unavailable)
    }

    private fun showLinkError(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun navigationScheme(url: String?): String? = runCatching {
        Uri.parse(url.orEmpty()).scheme?.lowercase(Locale.ROOT)
    }.getOrNull()

    private fun isWebNavigationUrl(url: String?): Boolean = when (navigationScheme(url)) {
        "http", "https" -> true
        else -> false
    }

    private fun webNavigationHost(url: String?): String? =
        url?.takeIf(::isWebNavigationUrl)?.let { Uri.parse(it).host }

    private fun fetchCurrentSchedule() {
        if (activeRequestToken != null) return
        if (pageHadError) {
            showFetchError(getString(R.string.academic_page_load_failed))
            return
        }
        if (!isWebNavigationUrl(binding.webView.url)) {
            showFetchError(getString(R.string.academic_page_load_failed))
            return
        }
        val requestToken = UUID.randomUUID().toString()
        activeRequestToken = requestToken
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(REQUEST_TIMEOUT_MILLIS)
            if (isActiveRequest(requestToken)) showFetchError(getString(R.string.academic_request_timeout))
        }
        setImportStatus(true, getString(R.string.academic_identifying_term))
        if (school.system == AcademicSystem.QIANGZHI_HTML) {
            captureGenericSchedule(requestToken)
            return
        }
        if (school.system == AcademicSystem.ZHENGFANG_HTML) {
            captureGenericZhengfang(requestToken)
            return
        }
        if (school.system != AcademicSystem.WISEDU) {
            captureGenericSchedule(requestToken)
            return
        }
        if (school.isGeneric) {
            captureWisedu(requestToken)
            return
        }
        binding.webView.evaluateJavascript(ScriptHolder.termProbeScript()) { encodedPageText ->
            if (!isActiveRequest(requestToken)) return@evaluateJavascript
            val pageText = runCatching {
                JSONTokener(encodedPageText).nextValue() as? String
            }.getOrNull().orEmpty()
            val termHint = normalizeWiseduTerm(pageText).orEmpty()
            captureWisedu(requestToken, termHint)
        }
    }

    private fun captureQiangzhiSchedule(token: String) {
        lifecycleScope.launch {
            delay(MIN_IDENTIFYING_DISPLAY_MILLIS)
            if (!isActiveRequest(token)) return@launch
            setImportStatus(true, getString(R.string.academic_fetching_schedule))
            binding.webView.evaluateJavascript(QiangzhiCaptureScript.create(school)) { encoded ->
                if (!isActiveRequest(token)) return@evaluateJavascript
                val payload = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                val error = runCatching { JSONObject(payload.orEmpty()).optString("error") }.getOrNull()
                if (payload == null || error == null || error.isNotEmpty()) {
                    val message = when (error) {
                        "large" -> R.string.academic_response_too_large
                        "frame" -> R.string.academic_frame_unavailable
                        else -> if (school.isGeneric) R.string.academic_generic_no_table else R.string.academic_qiangzhi_no_table
                    }
                    val detail = getString(message)
                    val code = when (error) {
                        "large" -> AcademicImportErrorCode.PAYLOAD_TOO_LARGE
                        "frame" -> AcademicImportErrorCode.FRAME_BLOCKED
                        else -> AcademicImportErrorCode.CAPTURE_MISS
                    }
                    showFetchError(detail, payload, ImportFormatException(detail, code))
                } else receiveSchedule(token, payload)
            }
        }
    }

    private fun captureGenericSchedule(token: String) {
        lifecycleScope.launch {
            delay(MIN_IDENTIFYING_DISPLAY_MILLIS)
            if (!isActiveRequest(token)) return@launch
            setImportStatus(true, getString(R.string.academic_fetching_schedule))
            // New ShuWei deployments: prefer the JSON already captured from the
            // trusted /print-data response over the DOM snapshot.
            if (school.system == AcademicSystem.SHUWEI) {
                val stash = shuweiStash
                if (stash != null) {
                    val payload = ShuweiPrintDataCapture.wrapPayload(
                        stash.first, stash.second, AcademicSchools.MAX_PAYLOAD_CHARS
                    )
                    if (payload != null) {
                        receiveSchedule(token, payload)
                        return@launch
                    }
                }
            }
            binding.webView.evaluateJavascript(AcademicCaptureScript.create(school)) { encoded ->
                if (!isActiveRequest(token)) return@evaluateJavascript
                val payload = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                val error = runCatching { JSONObject(payload.orEmpty()).optString("error") }.getOrNull()
                if (payload == null || error == null || error.isNotEmpty()) {
                    val message = when (error) {
                        "large" -> R.string.academic_response_too_large
                        "frame" -> R.string.academic_frame_unavailable
                        else -> R.string.academic_generic_no_table
                    }
                    val detail = getString(message)
                    val code = when (error) {
                        "large" -> AcademicImportErrorCode.PAYLOAD_TOO_LARGE
                        "frame" -> AcademicImportErrorCode.FRAME_BLOCKED
                        else -> AcademicImportErrorCode.CAPTURE_MISS
                    }
                    showFetchError(detail, payload, ImportFormatException(detail, code))
                } else receiveSchedule(token, payload)
            }
        }
    }

    private fun captureGenericZhengfang(token: String) {
        resultPollJob = lifecycleScope.launch {
            delay(MIN_IDENTIFYING_DISPLAY_MILLIS)
            if (!isActiveRequest(token)) return@launch
            setImportStatus(true, getString(R.string.academic_fetching_schedule))
            binding.webView.evaluateJavascript(ScriptHolder.zhengfangFetchScript(token), null)
            while (isActiveRequest(token)) {
                delay(250L)
                val encoded = suspendCancellableCoroutine<String?> { continuation ->
                    binding.webView.evaluateJavascript(ScriptHolder.pollResultScript(token)) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
                if (!isActiveRequest(token)) return@launch
                val snapshot = runCatching {
                    JSONObject(JSONTokener(encoded.orEmpty()).nextValue() as String)
                }.getOrNull() ?: continue
                if (snapshot.has("json")) {
                    resultPollJob = null
                    receiveSchedule(token, snapshot.getString("json"))
                    return@launch
                }
                if (snapshot.optString("stage") == "fallback" || snapshot.has("error")) {
                    resultPollJob = null
                    captureGenericSchedule(token)
                    return@launch
                }
            }
        }
    }

    private fun captureWisedu(token: String, termHint: String = "") {
        resultPollJob = lifecycleScope.launch {
            delay(MIN_IDENTIFYING_DISPLAY_MILLIS)
            if (!isActiveRequest(token)) return@launch
            val basePath = runCatching {
                GenericAcademicImport.wiseduBasePath(binding.webView.url.orEmpty())
            }.getOrElse {
                showFetchError(getString(R.string.academic_open_timetable_hint))
                return@launch
            }
            binding.webView.evaluateJavascript(
                ScriptHolder.fetchScript(token, termHint, basePath),
                null
            )
            while (isActiveRequest(token)) {
                delay(250L)
                val encoded = suspendCancellableCoroutine<String?> { continuation ->
                    binding.webView.evaluateJavascript(ScriptHolder.pollResultScript(token)) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                }
                if (!isActiveRequest(token)) return@launch
                val snapshot = runCatching {
                    JSONObject(JSONTokener(encoded.orEmpty()).nextValue() as String)
                }.getOrNull() ?: continue
                if (snapshot.has("error")) {
                    showFetchError(snapshot.optString("error"))
                    return@launch
                }
                if (snapshot.has("json")) {
                    resultPollJob = null
                    receiveSchedule(token, snapshot.getString("json"))
                    return@launch
                }
                if (snapshot.optString("stage") == "schedule" &&
                    binding.tvStatus.text.toString() != getString(R.string.academic_fetching_schedule)) {
                    setImportStatus(true, getString(R.string.academic_fetching_schedule))
                }
            }
        }
    }

    private fun isActiveRequest(token: String?): Boolean =
        !pageHadError && !isFinishing && !isDestroyed && token != null && token == activeRequestToken

    private fun invalidateRequest() {
        val token = activeRequestToken
        activeRequestToken = null
        resultPollJob?.cancel()
        resultPollJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        if (token != null && ::binding.isInitialized && school.isGeneric) {
            binding.webView.evaluateJavascript(ScriptHolder.clearResultScript(token), null)
        }
    }

    private fun setImportStatus(loading: Boolean, status: String, success: Boolean = false) {
        val statusChanged = binding.tvStatus.text?.toString() != status
        binding.tvStatus.animate().cancel()
        binding.tvStatus.text = status
        if (statusChanged && binding.tvStatus.isLaidOut) {
            binding.tvStatus.alpha = 0.3f
            binding.tvStatus.translationY = 7f * resources.displayMetrics.density
            binding.tvStatus.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .setInterpolator(statusInterpolator)
                .start()
        } else {
            binding.tvStatus.alpha = 1f
            binding.tvStatus.translationY = 0f
        }
        binding.statusProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.statusIcon.visibility = if (loading) View.GONE else View.VISIBLE
        binding.statusIcon.setImageResource(if (success) R.drawable.ic_check else R.drawable.ic_school)
        binding.statusIcon.animate().cancel()
        if (success) {
            binding.statusIcon.apply {
                alpha = 0.35f
                scaleX = 0.68f
                scaleY = 0.68f
                rotation = -9f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setDuration(480L)
                    .setInterpolator(OvershootInterpolator(1.45f))
                    .start()
            }
        } else {
            binding.statusIcon.alpha = 1f
            binding.statusIcon.scaleX = 1f
            binding.statusIcon.scaleY = 1f
            binding.statusIcon.rotation = 0f
        }
        binding.btnFetchSchedule.isEnabled = !loading && !success
        binding.btnOpenSchool.isEnabled = !loading && !success
        binding.btnFetchSchedule.alpha = if (binding.btnFetchSchedule.isEnabled) 1f else 0.72f
    }

    private fun showFetchError(
        message: String,
        payload: String? = null,
        error: Throwable? = null
    ) {
        invalidateRequest()
        setImportStatus(false, getString(R.string.academic_fetch_failed))
        val currentUrl = binding.webView.url
        lifecycleScope.launch {
            val diagnostic = withContext(Dispatchers.Default) {
                AcademicImportDiagnostics.create(
                    appVersion = appVersionName(),
                    school = school,
                    payload = payload,
                    error = error ?: ImportFormatException(message),
                    currentUrl = currentUrl
                )
            }
            if (isFinishing || isDestroyed) return@launch
            pendingDiagnostic = diagnostic
            MaterialAlertDialogBuilder(this@AcademicWebImportActivity)
                .setTitle(R.string.academic_fetch_failed)
                .setMessage(message.take(240).ifBlank { getString(R.string.academic_fetch_failed_detail) })
                .setNeutralButton(R.string.academic_view_diagnostic) { _, _ ->
                    showDiagnosticPreview(diagnostic)
                }
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun showDiagnosticPreview(diagnostic: String) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = TextView(this).apply {
            text = diagnostic
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.academic_diagnostic_preview_title)
            .setMessage(R.string.academic_diagnostic_preview_detail)
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.academic_export_diagnostic) { _, _ ->
                pendingDiagnostic = diagnostic
                diagnosticExporter.launch("courseschedule-diagnostic-${school.id}.json")
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")

    private fun receiveSchedule(token: String, scheduleJson: String) {
        if (!isActiveRequest(token)) return
        if (scheduleJson.length > AcademicSchools.MAX_PAYLOAD_CHARS) {
            showFetchError(getString(R.string.academic_response_too_large))
            return
        }
        setImportStatus(true, getString(R.string.academic_parsing_courses))
        lifecycleScope.launch {
            val parsingStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    AcademicSchools.parse(school, scheduleJson, totalWeeks).courses
                        .map { it.courseName.trim() }.distinct().size
                }
            }
            val remainingDisplayTime = MIN_PARSING_DISPLAY_MILLIS -
                (SystemClock.elapsedRealtime() - parsingStartedAt)
            if (remainingDisplayTime > 0L) delay(remainingDisplayTime)
            if (!isActiveRequest(token)) return@launch
            result.onSuccess { courseCount ->
                setImportStatus(false, getString(R.string.academic_courses_ready, courseCount), success = true)
                delay(SUCCESS_DISPLAY_MILLIS)
                if (!isActiveRequest(token)) return@onSuccess
                invalidateRequest()
                setResult(Activity.RESULT_OK, schoolIntent(this@AcademicWebImportActivity, school)
                    .putExtra(EXTRA_SCHEDULE_JSON, scheduleJson))
                finish()
            }.onFailure { error ->
                showFetchError(
                    error.message ?: getString(R.string.academic_fetch_failed_detail),
                    payload = scheduleJson,
                    error = error
                )
            }
        }
    }

    override fun onDestroy() {
        invalidateRequest()
        externalNavigationDialog?.dismiss()
        sslErrorDialog?.dismiss()
        shuweiStash = null
        if (::binding.isInitialized) {
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    internal object ScriptHolder {
        fun termProbeScript(): String = TERM_PROBE_SCRIPT

        fun zhengfangFetchScript(requestToken: String): String = ZHENGFANG_FETCH_SCRIPT
            .replace("__REQUEST_TOKEN__", JSONObject.quote(requestToken))
            .replace("__BRIDGE__", SNAPSHOT_BRIDGE)
            .replace("__LIMIT__", AcademicSchools.MAX_PAYLOAD_CHARS.toString())

        fun fetchScript(requestToken: String, termHint: String, basePath: String = "/jwapp"): String = FETCH_SCRIPT
            .replace("__REQUEST_TOKEN__", JSONObject.quote(requestToken))
            .replace("__TERM_HINT__", JSONObject.quote(termHint))
            .replace("__WISEDU_BASE__", JSONObject.quote(basePath))
            .replace("__BRIDGE__", SNAPSHOT_BRIDGE)

        fun clearResultScript(token: String): String =
            "delete window[${JSONObject.quote("__courseSchedule_" + token)}];"

        fun pollResultScript(token: String): String = """
            (function () {
              const key = ${JSONObject.quote("__courseSchedule_" + token)};
              const result = window[key];
              if (!result || typeof result !== 'object') return '{}';
              if (typeof result.error === 'string') {
                delete window[key];
                return JSON.stringify({error: result.error.slice(0, 240)});
              }
              if (typeof result.json === 'string') {
                delete window[key];
                if (result.json.length > ${AcademicSchools.MAX_PAYLOAD_CHARS}) {
                  return JSON.stringify({error: '教务数据过大，请只查询一个学期后重试'});
                }
                return JSON.stringify({json: result.json});
              }
              const stage = typeof result.stage === 'string' ? result.stage.slice(0, 20) : 'term';
              return JSON.stringify({stage: stage});
            })();
        """.trimIndent()

        // This is a plain page-local object, NOT an Android JavascriptInterface.
        private val SNAPSHOT_BRIDGE = """
            (function () {
              const state = {stage: 'term'};
              window[resultKey] = state;
              return {
                onStage: function (token, stage) { if (token === requestToken) state.stage = stage; },
                onScheduleJson: function (token, json) {
                  if (token !== requestToken) return;
                  if (typeof json !== 'string' || json.length > ${AcademicSchools.MAX_PAYLOAD_CHARS}) {
                    state.error = '教务数据过大，请只查询一个学期后重试';
                  } else state.json = json;
                },
                onImportError: function (token, message) {
                  if (token === requestToken) state.error = String(message || '读取课表失败').slice(0, 240);
                }
              };
            })()
        """.trimIndent()

        private val TERM_PROBE_SCRIPT = """
            (function () {
              const values = [];
              function collect(doc, depth) {
                if (!doc || depth > 3) return;
                doc.querySelectorAll(
                  'select[name*="XNXQ"], select[id*="XNXQ"], input[name*="XNXQ"], input[id*="XNXQ"]'
                ).forEach(function (element) {
                  if (element.value) values.push(String(element.value));
                  if (element.options && element.selectedIndex >= 0) {
                    values.push(String(element.options[element.selectedIndex].text || ''));
                  }
                });
                values.push(doc.body ? doc.body.innerText : '');
                doc.querySelectorAll('iframe').forEach(function (frame) {
                  try { collect(frame.contentDocument, depth + 1); } catch (_) {}
                });
              }
              collect(document, 0);
              return values.join('\n').slice(0, 20000);
            })();
        """.trimIndent()

        private val ZHENGFANG_FETCH_SCRIPT = """
            (function () {
              'use strict';
              const requestToken = __REQUEST_TOKEN__;
              const resultKey = '__courseSchedule_' + requestToken;
              const bridge = __BRIDGE__;
              const limit = __LIMIT__;
              const fields = [
                'kcmc', 'kcm', 'courseName', 'xqj', 'xq', 'skxq', 'day', 'weekday',
                'jcs', 'jc', 'sksj', 'ksjc', 'skjc', 'startSection', 'jsjc',
                'endSection', 'cxjc', 'sectionCount', 'zcd', 'zc', 'skzc', 'weeks',
                'xm', 'jsxm', 'jsmc', 'teacher', 'cdmc', 'jxcdmc', 'jasmc',
                'classroom', 'room'
              ];

              function object(value) {
                return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
              }
              function hasAny(value, names) {
                return names.some(function (name) { return name in value; });
              }
              function courseRow(value) {
                return object(value) &&
                  hasAny(value, ['kcmc', 'kcm', 'courseName']) &&
                  hasAny(value, ['xqj', 'xq', 'skxq', 'day', 'weekday']) &&
                  hasAny(value, ['jcs', 'jc', 'sksj', 'ksjc', 'skjc', 'startSection']) &&
                  hasAny(value, ['zcd', 'zc', 'skzc', 'weeks']);
              }
              function findRows(root, depth, seen) {
                if (root == null || depth > 10) return null;
                if (typeof root === 'object') {
                  if (seen.has(root) || seen.size > 3000) return null;
                  seen.add(root);
                }
                if (Array.isArray(root)) {
                  if (root.some(courseRow)) return root.filter(courseRow).slice(0, 1500);
                  for (let index = 0; index < Math.min(root.length, 600); index++) {
                    const rows = findRows(root[index], depth + 1, seen);
                    if (rows) return rows;
                  }
                } else if (object(root)) {
                  const keys = Object.keys(root).slice(0, 160);
                  for (const key of keys) {
                    const rows = findRows(root[key], depth + 1, seen);
                    if (rows) return rows;
                  }
                }
                return null;
              }
              function cleanRows(root) {
                const rows = findRows(root, 0, new Set());
                if (!rows) return null;
                return rows.map(function (row) {
                  const clean = {};
                  fields.forEach(function (field) {
                    const value = row[field];
                    if (typeof value === 'string' || typeof value === 'number') {
                      clean[field] = String(value).slice(0,
                        /^(?:zcd|zc|skzc|weeks)$/.test(field) ? 512 : 256);
                    }
                  });
                  return clean;
                });
              }
              function decoded(value) {
                if (typeof value !== 'string') return value;
                const source = value.trim();
                if (!/^[\[{]/.test(source) || source.length > limit / 2) return null;
                try { return JSON.parse(source); } catch (_) { return null; }
              }
              function controlValue(doc, name) {
                const controls = doc.querySelectorAll('input,select');
                for (const control of controls) {
                  const id = String(control.id || '').toLowerCase();
                  const field = String(control.name || '').toLowerCase();
                  if (id === name || field === name) {
                    const value = String(control.value || '').trim();
                    if (value) return value;
                  }
                }
                return '';
              }
              function endpointFor(doc) {
                const location = doc.defaultView.location;
                const explicit = doc.querySelector(
                  '[action*="xskbcx_cxXskbcxIndex"],[href*="xskbcx_cxXskbcxIndex"]'
                );
                if (explicit) {
                  const value = explicit.getAttribute('action') || explicit.getAttribute('href');
                  try {
                    const url = new URL(value, location.href);
                    if (url.origin === location.origin) return url.origin + url.pathname;
                  } catch (_) {}
                }
                const match = location.pathname.match(/^(.*?)(?:\/(?:kbcx|xtgl|xsxxxggl|xkgl)\/)/i);
                if (!match) return '';
                return location.origin + match[1] + '/kbcx/xskbcx_cxXskbcxIndex.html';
              }
              function page(doc, depth) {
                if (!doc || depth > 4) return null;
                const win = doc.defaultView;
                const candidates = [];
                try { candidates.push(win.veInitDefaultJson); } catch (_) {}
                try { candidates.push(win.__INITIAL_STATE__); } catch (_) {}
                try {
                  const body = doc.body;
                  const raw = body && body.children.length <= 1 ? (body.textContent || '').trim() : '';
                  if (raw.length >= 2 && raw.length <= limit / 2 && /^[\[{]/.test(raw)) {
                    candidates.push(raw);
                  }
                } catch (_) {}
                for (const candidate of candidates) {
                  const rows = cleanRows(decoded(candidate));
                  if (rows) return {doc: doc, rows: rows};
                }
                const year = controlValue(doc, 'xnm');
                const term = controlValue(doc, 'xqm');
                const endpoint = endpointFor(doc);
                if (/^\d{4}$/.test(year) && /^\d{1,2}$/.test(term) && endpoint) {
                  return {doc: doc, year: year, term: term, endpoint: endpoint};
                }
                for (const frame of doc.querySelectorAll('iframe,frame')) {
                  try {
                    const found = page(frame.contentDocument, depth + 1);
                    if (found) return found;
                  } catch (_) {}
                }
                return null;
              }
              function finish(found, rows) {
                const location = found.doc.defaultView.location;
                const payload = JSON.stringify({
                  html: '', data: rows, term: found.year && found.term ? found.year + '-' + found.term : '',
                  sourceUrl: location.origin + location.pathname, profile: 'zhengfang'
                });
                if (payload.length > limit) bridge.onStage(requestToken, 'fallback');
                else bridge.onScheduleJson(requestToken, payload);
              }
              async function run() {
                const found = page(document, 0);
                if (!found) {
                  bridge.onStage(requestToken, 'fallback');
                  return;
                }
                if (found.rows) {
                  finish(found, found.rows);
                  return;
                }
                const controller = typeof AbortController === 'function' ? new AbortController() : null;
                const timer = setTimeout(function () { if (controller) controller.abort(); }, 7000);
                try {
                  const body = new URLSearchParams({
                    xnm: found.year, xqm: found.term, kzlx: 'ck',
                    'queryModel.showCount': '2000', 'queryModel.currentPage': '1',
                    'queryModel.sortName': '', 'queryModel.sortOrder': 'asc'
                  }).toString();
                  const response = await fetch(
                    found.endpoint + '?doType=query&gnmkdm=N2151',
                    {
                      method: 'POST', credentials: 'include',
                      headers: {
                        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                        'X-Requested-With': 'XMLHttpRequest'
                      },
                      body: body,
                      signal: controller ? controller.signal : undefined
                    }
                  );
                  const text = await response.text();
                  const rows = response.ok && text.length <= limit / 2
                    ? cleanRows(decoded(text)) : null;
                  if (rows) finish(found, rows);
                  else bridge.onStage(requestToken, 'fallback');
                } catch (_) {
                  bridge.onStage(requestToken, 'fallback');
                } finally {
                  clearTimeout(timer);
                }
              }
              run();
            })();
        """.trimIndent()

        private val FETCH_SCRIPT = """
            (function () {
              const requestToken = __REQUEST_TOKEN__;
              const resultKey = '__courseSchedule_' + requestToken;
              const bridge = __BRIDGE__;
              const wiseduBase = __WISEDU_BASE__;
              const wiseduRoot = wiseduBase.replace(/\/(?:jwapp|gsapp)$/, '');
              const jwappBase = wiseduRoot + '/jwapp';
              const gsappBase = wiseduRoot + '/gsapp';
              const termHint = __TERM_HINT__;
              const formHeaders = {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
              };

              async function readJson(url, options) {
                const response = await fetch(url, Object.assign({ credentials: 'include' }, options || {}));
                const text = await response.text();
                if (!response.ok) {
                  if (response.status === 401 || response.status === 403) {
                    throw new Error('登录状态无效，请先完成学校网页登录后再读取课表');
                  }
                  throw new Error('教务系统请求失败（' + response.status + '）');
                }
                try {
                  return JSON.parse(text);
                } catch (_) {
                  throw new Error('教务系统返回了登录页面，请先完成登录后再读取课表');
                }
              }

              function normalizeTerm(value) {
                const text = String(value || '').trim();
                let match = text.match(/(20\d{2})\s*[-_/]\s*(20\d{2})\s*[-_/]\s*([1-3])/);
                if (match) return match[1] + '-' + match[2] + '-' + match[3];
                match = text.match(
                  /(20\d{2})\s*[-~～—–－至/]\s*(20\d{2})\s*学年[\s\S]{0,16}?(秋季|春季|夏季|第一|第二|第三|1|2|3)\s*学期/
                );
                if (match) {
                  const semester = /^(秋季|第一|1)$/.test(match[3]) ? '1' :
                    (/^(春季|第二|2)$/.test(match[3]) ? '2' : '3');
                  return match[1] + '-' + match[2] + '-' + semester;
                }
                match = text.match(/(?:^|\D)(20\d{2})(20\d{2})([1-3])(?:\D|$)/);
                return match ? match[1] + '-' + match[2] + '-' + match[3] : '';
              }

              function selectedTermFromPage() {
                const selectors = [
                  'select[name="XNXQDM"]',
                  '#XNXQDM',
                  '[data-name="XNXQDM"] select'
                ];
                for (const selector of selectors) {
                  const element = document.querySelector(selector);
                  if (!element) continue;
                  const value = element.value || (element.options && element.options[element.selectedIndex]
                    ? element.options[element.selectedIndex].value
                    : '');
                  const normalized = normalizeTerm(value);
                  if (normalized) return normalized;
                }
                return normalizeTerm(document.body ? document.body.innerText : '');
              }

              function termFromResponse(data) {
                const candidates = [];
                function walk(value, depth) {
                  if (depth > 6 || value == null) return;
                  if (Array.isArray(value)) {
                    value.forEach(function (item) { walk(item, depth + 1); });
                    return;
                  }
                  if (typeof value !== 'object') {
                    const normalized = normalizeTerm(value);
                    if (normalized) candidates.push(normalized);
                    return;
                  }
                  Object.keys(value).forEach(function (key) {
                    const child = value[key];
                    walk(child, depth + 1);
                  });
                }
                walk(data, 0);
                return candidates[0] || '';
              }

              function containsCourseRows(value, depth) {
                if (depth > 8 || value == null) return false;
                if (Array.isArray(value)) {
                  return value.some(function (item) {
                    return item && typeof item === 'object' &&
                      (('KCM' in item && 'SKXQ' in item && 'KSJC' in item) ||
                        ('KCMC' in item && 'PKSJDD' in item) ||
                        ('kcmc' in item && 'xqj' in item && 'djj' in item && 'qmz' in item));
                  }) || value.some(function (item) { return containsCourseRows(item, depth + 1); });
                }
                if (typeof value !== 'object') return false;
                return Object.keys(value).some(function (key) {
                  return containsCourseRows(value[key], depth + 1);
                });
              }

              async function readSchedule(term) {
                const bodies = [];
                if (term) {
                  bodies.push(
                    'XNXQDM=' + encodeURIComponent(term) + '&pageSize=200&pageNumber=1'
                  );
                }
                bodies.push('pageSize=200&pageNumber=1');
                const requests = [];
                const termQuery = term ? '?XNXQDM=' + encodeURIComponent(term) : '';
                // Keep the verified SWPU route first. Only continue to the
                // other common Wisedu variants when it returns no courses.
                bodies.forEach(function (body) {
                  requests.push({
                    url: jwappBase + '/sys/wdkb/modules/xskcb/xskcb.do',
                    options: { method: 'POST', headers: formHeaders, body: body }
                  });
                });
                requests.push({
                  url: jwappBase + '/sys/xkjglapp/modules/xskcb/xsjxrwcx.do' + termQuery,
                  options: { method: 'GET' }
                });
                requests.push({
                  url: gsappBase + '/sys/wdkbapp/modules/xskcb/xsjxrwcx.do' + termQuery,
                  options: { method: 'GET' }
                });
                requests.push({
                  url: jwappBase + '/sys/homeapp/api/home/student/getMyScheduleDetail.do',
                  options: {
                    method: 'POST', headers: formHeaders,
                    body: 'termCode=' + encodeURIComponent(term || '') + '&campusCode=&type=term'
                  }
                });
                let lastResponse = null;
                let lastError = null;
                for (const request of requests) {
                  try {
                    lastResponse = await readJson(request.url, request.options);
                    if (containsCourseRows(lastResponse, 0)) return lastResponse;
                  } catch (error) {
                    lastError = error;
                  }
                }
                if (lastResponse == null && lastError) throw lastError;
                return lastResponse;
              }

              async function run() {
                try {
                  try {
                    const entry = /\/gsapp$/.test(wiseduBase)
                      ? '/sys/wdkbapp/*default/index.do'
                      : '/sys/wdkb/*default/index.do';
                    await fetch(wiseduBase + entry, { credentials: 'include' });
                  } catch (_) {}

                  let term = normalizeTerm(termHint) || selectedTermFromPage();
                  if (!term) {
                    try {
                      const termData = await readJson(
                        jwappBase + '/sys/wdkb/modules/jshkcb/dqxnxq.do',
                        { method: 'POST', headers: formHeaders, body: '' }
                      );
                      term = termFromResponse(termData);
                    } catch (_) {}
                  }

                  bridge.onStage(requestToken, 'schedule');
                  await new Promise(function (resolve) { setTimeout(resolve, 360); });
                  const schedule = await readSchedule(term);
                  if (!containsCourseRows(schedule, 0)) {
                    throw new Error(
                      term
                        ? '已识别学期 ' + term + '，但教务接口没有返回课程，请确认学生课表已有数据'
                        : '无法识别当前学期，且教务接口没有返回默认学期课表'
                    );
                  }
                  bridge.onScheduleJson(
                    requestToken,
                    JSON.stringify({ term: term, payload: schedule })
                  );
                } catch (error) {
                  let message = error && error.message ? error.message : String(error);
                  if (/Failed to fetch|Load failed|NetworkError/i.test(message)) {
                    message = '教务请求未完成，请确认已登录并进入课表页面后重试';
                  }
                  bridge.onImportError(
                    requestToken,
                    message
                  );
                }
              }

              run();
            })();
        """.trimIndent()
    }

}
