package com.courseschedule.ui.importdata

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * WakeUp-style network capture for the new ShuWei "for-std" architecture:
 * the timetable JSON is fetched by the page from /print-data endpoints, so the
 * WebView response for that request is transparently replayed with the session
 * cookie and stashed for the import step. Only behavioral facts (the endpoint
 * shape and JSON field names) are reused; the implementation is independent.
 */
internal object ShuweiPrintDataCapture {

    /** Hard ceiling; anything larger is abandoned and the page still renders normally. */
    const val MAX_STASH_BYTES = AcademicSchools.MAX_PAYLOAD_CHARS

    /** Matches only the timetable print-data JSON endpoint of for-std based deployments. */
    fun isCapturableUrl(url: String, allowCleartext: Boolean = false): Boolean {
        val uri = academicWebUri(url, allowCleartext) ?: return false
        return uri.rawPath.orEmpty().contains("/print-data", ignoreCase = true)
    }

    /**
     * Wraps the stashed JSON in the snapshot payload the import pipeline expects
     * ({html, data, term, sourceUrl}). Returns null when the body is not JSON or
     * exceeds the shared payload limit.
     */
    fun wrapPayload(stashJson: String, sourceUrl: String, maxPayloadChars: Int): String? {
        if (stashJson.isBlank() || stashJson.length > maxPayloadChars) return null
        val data = runCatching { JsonParser.parseString(stashJson) }.getOrNull() ?: return null
        if (!data.isJsonObject && !data.isJsonArray) return null
        val wrapper = JsonObject().apply {
            addProperty("html", "")
            add("data", data)
            addProperty("term", "")
            addProperty("sourceUrl", sourceUrl)
        }
        val payload = wrapper.toString()
        if (payload.length > maxPayloadChars) return null
        return payload
    }

    /** Splits an upstream Content-Type header into mimeType/encoding for pass-through. */
    fun splitContentType(contentType: String?): Pair<String, String> {
        if (contentType.isNullOrBlank()) return "application/json" to "utf-8"
        val parts = contentType.split(';', limit = 2)
        val mime = parts[0].trim().ifBlank { "application/json" }
        val charset = parts.getOrNull(1)
            ?.substringAfter("charset=", "")
            ?.trim(' ', '"', '\'')
            ?.takeIf { it.isNotBlank() && it.length <= 40 && !it.contains('\n') }
            ?: "utf-8"
        return mime to charset.lowercase()
    }

    fun trimToLimit(bytes: ByteArray, limit: Int = MAX_STASH_BYTES): ByteArray? =
        if (bytes.size > limit) null else bytes
}
