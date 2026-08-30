package com.courseschedule.ui.importdata

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.courseschedule.R
import com.courseschedule.databinding.ActivitySwpuWebImportBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

class SwpuWebImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCHEDULE_JSON = "schedule_json"
        const val EXTRA_TOTAL_WEEKS = "total_weeks"
        private const val LOGIN_URL =
            "https://deanservices.swpu.edu.cn/jwapp/sys/jwauthapp/login/index.html"
        private const val BRIDGE_NAME = "CourseScheduleBridge"
        private const val MAX_SCHEDULE_JSON_CHARS = 400_000
        private const val MIN_IDENTIFYING_DISPLAY_MILLIS = 320L
        private const val MIN_PARSING_DISPLAY_MILLIS = 360L
        private const val SUCCESS_DISPLAY_MILLIS = 1_100L
        private val TRUSTED_HOSTS = setOf("swpu.edu.cn", "deanservices.swpu.edu.cn")
    }

    private lateinit var binding: ActivitySwpuWebImportBinding
    private var activeRequestToken: String? = null
    private val statusInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val totalWeeks by lazy {
        intent.getIntExtra(EXTRA_TOTAL_WEEKS, 20).coerceIn(1, 52)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySwpuWebImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }

        configureWebView()
        binding.btnFetchSchedule.setOnClickListener { fetchCurrentSchedule() }
        setImportStatus(false, getString(R.string.academic_waiting_login))
        binding.webView.loadUrl(LOGIN_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.addJavascriptInterface(ImportBridge(), BRIDGE_NAME)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (!isTrustedSchoolUri(uri)) {
                    showBlockedHost()
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val uri = url?.let(Uri::parse) ?: return
                if (!isTrustedSchoolUri(uri)) {
                    view?.stopLoading()
                    showBlockedHost()
                } else if (activeRequestToken != null) {
                    activeRequestToken = null
                    setImportStatus(false, getString(R.string.academic_waiting_login))
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (
                    activeRequestToken == null &&
                    url?.let(Uri::parse)?.let(::isTrustedSchoolUri) == true
                ) {
                    setImportStatus(false, getString(R.string.academic_waiting_login))
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                activeRequestToken = null
                setImportStatus(false, getString(R.string.academic_ssl_error))
            }
        }
    }

    private fun fetchCurrentSchedule() {
        val currentUri = binding.webView.url?.let(Uri::parse)
        if (currentUri == null || !isTrustedSchoolUri(currentUri)) {
            showBlockedHost()
            return
        }
        val requestToken = UUID.randomUUID().toString()
        activeRequestToken = requestToken
        setImportStatus(true, getString(R.string.academic_identifying_term))
        binding.webView.evaluateJavascript(ScriptHolder.termProbeScript()) { encodedPageText ->
            if (!isActiveTrustedRequest(requestToken)) return@evaluateJavascript
            val pageText = runCatching {
                JSONTokener(encodedPageText).nextValue() as? String
            }.getOrNull().orEmpty()
            val termHint = normalizeWiseduTerm(pageText).orEmpty()
            lifecycleScope.launch {
                delay(MIN_IDENTIFYING_DISPLAY_MILLIS)
                if (!isActiveTrustedRequest(requestToken)) return@launch
                binding.webView.evaluateJavascript(
                    ScriptHolder.fetchScript(requestToken, termHint),
                    null
                )
            }
        }
    }

    private fun isTrustedSchoolUri(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        return host in TRUSTED_HOSTS
    }

    private fun isActiveTrustedRequest(token: String?): Boolean {
        val uri = binding.webView.url?.let(Uri::parse)
        return token != null && token == activeRequestToken && uri != null && isTrustedSchoolUri(uri)
    }

    private fun showBlockedHost() {
        activeRequestToken = null
        setImportStatus(false, getString(R.string.academic_host_blocked))
        Toast.makeText(this, R.string.academic_host_blocked, Toast.LENGTH_SHORT).show()
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
        binding.btnFetchSchedule.alpha = if (binding.btnFetchSchedule.isEnabled) 1f else 0.72f
    }

    private fun showFetchError(message: String) {
        activeRequestToken = null
        setImportStatus(false, getString(R.string.academic_waiting_login))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.academic_fetch_failed)
            .setMessage(message.take(240).ifBlank { getString(R.string.academic_fetch_failed_detail) })
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private inner class ImportBridge {
        @JavascriptInterface
        fun onStage(token: String?, stage: String?) {
            binding.webView.post {
                if (!isActiveTrustedRequest(token)) return@post
                if (stage == "schedule") {
                    setImportStatus(true, getString(R.string.academic_fetching_schedule))
                }
            }
        }

        @JavascriptInterface
        fun onScheduleJson(token: String?, json: String?) {
            binding.webView.post {
                if (!isActiveTrustedRequest(token)) return@post
                val scheduleJson = json ?: return@post
                if (scheduleJson.length > MAX_SCHEDULE_JSON_CHARS) {
                    showFetchError(getString(R.string.academic_response_too_large))
                    return@post
                }
                setImportStatus(true, getString(R.string.academic_parsing_courses))
                lifecycleScope.launch {
                    val parsingStartedAt = SystemClock.elapsedRealtime()
                    val result = runCatching {
                        withContext(Dispatchers.Default) {
                            WiseduScheduleParser(totalWeeks).parse(scheduleJson).courses
                                .map { it.courseName.trim() }
                                .distinct()
                                .size
                            }
                    }
                    val remainingDisplayTime = MIN_PARSING_DISPLAY_MILLIS -
                        (SystemClock.elapsedRealtime() - parsingStartedAt)
                    if (remainingDisplayTime > 0L) delay(remainingDisplayTime)
                    if (!isActiveTrustedRequest(token)) return@launch
                    result.onSuccess { courseCount ->
                        setImportStatus(
                            loading = false,
                            status = getString(R.string.academic_courses_ready, courseCount),
                            success = true
                        )
                        delay(SUCCESS_DISPLAY_MILLIS)
                        if (!isActiveTrustedRequest(token)) return@onSuccess
                        activeRequestToken = null
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_SCHEDULE_JSON, scheduleJson)
                        )
                        finish()
                    }.onFailure { error ->
                        showFetchError(
                            error.message ?: getString(R.string.academic_fetch_failed_detail)
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun onImportError(token: String?, message: String?) {
            binding.webView.post {
                if (!isActiveTrustedRequest(token)) return@post
                showFetchError(message.orEmpty())
            }
        }
    }

    override fun onDestroy() {
        activeRequestToken = null
        binding.webView.removeJavascriptInterface(BRIDGE_NAME)
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    private object ScriptHolder {
        fun termProbeScript(): String = TERM_PROBE_SCRIPT

        fun fetchScript(requestToken: String, termHint: String): String = FETCH_SCRIPT
            .replace("__REQUEST_TOKEN__", JSONObject.quote(requestToken))
            .replace("__TERM_HINT__", JSONObject.quote(termHint))

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

        private val FETCH_SCRIPT = """
            (function () {
              const bridge = window.CourseScheduleBridge;
              const requestToken = __REQUEST_TOKEN__;
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
                    return item && typeof item === 'object' && item.KCM;
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
                let lastResponse = null;
                let lastError = null;
                for (const body of bodies) {
                  try {
                    lastResponse = await readJson(
                      '/jwapp/sys/wdkb/modules/xskcb/xskcb.do',
                      { method: 'POST', headers: formHeaders, body: body }
                    );
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
                    await fetch('/jwapp/sys/wdkb/*default/index.do', { credentials: 'include' });
                  } catch (_) {}

                  let term = normalizeTerm(termHint) || selectedTermFromPage();
                  if (!term) {
                    try {
                      const termData = await readJson(
                        '/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do',
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
