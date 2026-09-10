package com.courseschedule.ui.importdata

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** Builds a preview-safe diagnostic. Raw page content is never copied into the result. */
internal object AcademicImportDiagnostics {
    const val SCHEMA_VERSION = 1
    private const val MAX_TABLES = 12
    private const val MAX_JSON_DEPTH = 6

    fun create(
        appVersion: String,
        school: AcademicSchool,
        payload: String?,
        error: Throwable?,
        currentUrl: String? = null,
        captureMode: String? = null
    ): String {
        val placeholders = PlaceholderMap()
        val parsed = payload?.takeIf { it.length <= AcademicSchools.MAX_PAYLOAD_CHARS }
            ?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
        val wrapper = parsed?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val html = wrapper?.get("html")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString.orEmpty()
        val data = wrapper?.get("data")?.takeUnless(JsonElement::isJsonNull)
            ?: parsed?.takeUnless { wrapper?.has("html") == true }
        val sourceUrl = wrapper?.get("sourceUrl")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString ?: currentUrl
        val capture = wrapper?.get("diagnostics")?.takeIf(JsonElement::isJsonObject)?.asJsonObject

        val root = JsonObject().apply {
            addProperty("schemaVersion", SCHEMA_VERSION)
            addProperty("appVersion", safeVersion(appVersion))
            addProperty("schoolDirectoryId", safeIdentifier(school.id))
            addProperty("family", school.system.name)
            addProperty("adapterId", safeIdentifier(school.adapterId.ifBlank {
                AcademicAdapterRegistry.defaultAdapterId(school.genericProfileId)
            }))
            addProperty("verification", if (school.verified) "verified" else "experimental")
            addProperty("source", safeUrl(sourceUrl))
            addProperty(
                "captureMode",
                captureMode?.takeIf(::safeEnumToken)
                    ?: capture?.safeString("captureMode")?.takeIf(::safeEnumToken)
                    ?: AcademicAdapterRegistry.captureModeFor(school).name
            )
            addProperty("errorCode", errorCode(error).name)
            add("capture", captureShape(school, capture))
            add("tables", tableShapes(html))
            add("jsonShape", jsonShape(data, 0, placeholders))
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    private fun captureShape(school: AcademicSchool, capture: JsonObject?): JsonObject = JsonObject().apply {
        val registered = AcademicAdapterRegistry.selectorsFor(school).toSet()
        val hits = capture?.getAsJsonArray("selectorHits")?.mapNotNull { item ->
            item.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf(registered::contains)
        }.orEmpty().distinct().take(16)
        add("selectorHits", JsonArray().also { array -> hits.forEach(array::add) })
        val frame = capture?.safeString("iframeState")
            ?.takeIf { it in setOf("none", "same_origin", "blocked") } ?: "unknown"
        addProperty("iframeState", frame)
        val visited = capture?.get("documentsVisited")?.takeIf(JsonElement::isJsonPrimitive)
            ?.asString?.toIntOrNull()?.coerceIn(0, 24)
        visited?.let { addProperty("documentsVisited", it) }
    }

    private fun tableShapes(html: String): JsonArray {
        val result = JsonArray()
        if (html.isBlank()) return result
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return result
        document.select("script,style,link,form,input,textarea,select,button,iframe,frame,object,embed")
            .remove()
        document.select("table").take(MAX_TABLES).forEach { table ->
            val rows = table.select("tr").toList().filter { it.closest("table") === table }.take(240)
            var cells = 0
            var maxColumns = 0
            var rowSpans = 0
            var columnSpans = 0
            val headers = linkedSetOf<String>()
            rows.forEach { row ->
                val direct = row.children().toList().filter { it.tagName() == "td" || it.tagName() == "th" }
                cells += direct.size
                maxColumns = maxOf(maxColumns, direct.sumOf {
                    it.attr("colspan").toIntOrNull()?.coerceIn(1, 32) ?: 1
                })
                rowSpans += direct.count { (it.attr("rowspan").toIntOrNull() ?: 1) > 1 }
                columnSpans += direct.count { (it.attr("colspan").toIntOrNull() ?: 1) > 1 }
                direct.filter { it.tagName() == "th" || row === rows.firstOrNull() }.forEach { cell ->
                    canonicalHeader(cell.text())?.let(headers::add)
                }
            }
            result.add(JsonObject().apply {
                addProperty("rows", rows.size)
                addProperty("maxColumns", maxColumns)
                addProperty("cells", cells.coerceAtMost(18_000))
                addProperty("rowspanCells", rowSpans)
                addProperty("colspanCells", columnSpans)
                add("headers", JsonArray().also { array -> headers.take(32).forEach(array::add) })
            })
        }
        return result
    }

    private fun jsonShape(
        value: JsonElement?,
        depth: Int,
        placeholders: PlaceholderMap
    ): JsonElement {
        if (value == null || value.isJsonNull) return JsonObject().apply { addProperty("type", "null") }
        if (depth >= MAX_JSON_DEPTH) return JsonObject().apply { addProperty("type", typeOf(value)) }
        return when {
            value.isJsonObject -> JsonObject().apply {
                addProperty("type", "object")
                val fields = JsonArray()
                value.asJsonObject.entrySet().asSequence()
                    .filterNot { (rawKey, _) -> isSensitiveKey(rawKey) }
                    .take(160).forEach { (rawKey, child) ->
                    fields.add(JsonObject().apply {
                        addProperty("name", safeFieldName(rawKey, placeholders))
                        addProperty("type", typeOf(child))
                        if (child.isJsonObject || child.isJsonArray) {
                            add("shape", jsonShape(child, depth + 1, placeholders))
                        } else if (child.isJsonPrimitive) {
                            addProperty("sample", safeSample(rawKey, child, placeholders))
                        }
                    })
                }
                add("fields", fields)
            }
            value.isJsonArray -> JsonObject().apply {
                val array = value.asJsonArray
                addProperty("type", "array")
                addProperty("length", array.size().coerceAtMost(10_000))
                add("itemTypes", JsonArray().also { types ->
                    array.take(100).map(::typeOf).distinct().forEach(types::add)
                })
                array.firstOrNull()?.let { add("itemShape", jsonShape(it, depth + 1, placeholders)) }
            }
            else -> JsonObject().apply {
                addProperty("type", typeOf(value))
                addProperty("sample", safeSample("", value, placeholders))
            }
        }
    }

    private fun safeSample(key: String, value: JsonElement, placeholders: PlaceholderMap): String {
        if (!value.isJsonPrimitive) return typeOf(value)
        val primitive = value.asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean.toString()
        if (primitive.isNumber) {
            return if (key.lowercase(Locale.ROOT) in NUMERIC_SCHEDULE_FIELDS) {
                primitive.asString.take(4)
            } else "<number>"
        }
        val raw = primitive.asString.take(512)
        if (key.lowercase(Locale.ROOT) in FORMAT_FIELDS && SAFE_SCHEDULE_FORMAT.matches(raw)) {
            return raw
        }
        return placeholders.text(raw)
    }

    private fun safeUrl(raw: String?): String {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return "unavailable"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "https" || it == "http" }
            ?: return "unavailable"
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf {
            it.matches(Regex("[a-z0-9.-]{1,253}"))
        } ?: return "unavailable"
        val path = uri.rawPath.orEmpty().split('/').joinToString("/") { segment ->
            when {
                segment.isEmpty() -> ""
                segment.length > 40 || segment.matches(Regex("(?=.*\\d{4})[A-Za-z0-9._~-]+")) -> ":segment"
                segment.matches(Regex("[A-Za-z0-9._~-]{1,40}")) -> segment
                else -> ":segment"
            }
        }.ifBlank { "/" }
        return "$scheme://$host$path"
    }

    private fun safeFieldName(raw: String, placeholders: PlaceholderMap): String =
        raw.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")) }
            ?: canonicalHeader(raw)
            ?: placeholders.field(raw)

    private fun canonicalHeader(raw: String): String? {
        val normalized = raw.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_/-]"), "")
        return HEADER_TERMS[normalized]
    }

    private fun safeIdentifier(raw: String): String = raw.take(80).takeIf {
        it.matches(Regex("[A-Za-z0-9_-]+"))
    } ?: "unknown"

    private fun safeVersion(raw: String): String = raw.take(32).takeIf {
        it.matches(Regex("[A-Za-z0-9.+_-]+"))
    } ?: "unknown"

    private fun safeEnumToken(raw: String): Boolean = raw.matches(Regex("[A-Z_]{1,32}"))

    private fun isSensitiveKey(raw: String): Boolean {
        val key = raw.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return SENSITIVE_KEY_PARTS.any(key::contains)
    }

    private fun errorCode(error: Throwable?): AcademicImportErrorCode =
        (error as? ImportFormatException)?.errorCode ?: when {
            error?.message.orEmpty().contains("星期") -> AcademicImportErrorCode.MISSING_DAY
            error?.message.orEmpty().contains("节次") -> AcademicImportErrorCode.MISSING_SECTION
            error?.message.orEmpty().contains("周次") -> AcademicImportErrorCode.MISSING_WEEK
            error?.message.orEmpty().contains("过大") -> AcademicImportErrorCode.PAYLOAD_TOO_LARGE
            error?.message.orEmpty().contains("框架") -> AcademicImportErrorCode.FRAME_BLOCKED
            else -> AcademicImportErrorCode.CAPTURE_MISS
        }

    private fun typeOf(value: JsonElement): String = when {
        value.isJsonNull -> "null"
        value.isJsonObject -> "object"
        value.isJsonArray -> "array"
        value.asJsonPrimitive.isBoolean -> "boolean"
        value.asJsonPrimitive.isNumber -> "number"
        else -> "string"
    }

    private fun JsonObject.safeString(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.take(80)

    private class PlaceholderMap {
        private val texts = linkedMapOf<String, String>()
        private val fields = linkedMapOf<String, String>()
        fun text(value: String): String = texts.getOrPut(value) { "<text-${texts.size + 1}>" }
        fun field(value: String): String = fields.getOrPut(value) { "<field-${fields.size + 1}>" }
    }

    private val SAFE_SCHEDULE_FORMAT = Regex(
        "[0-9一二三四五六日天单双周星期礼拜节第,，、;；:：()（）\\[\\]{}~～\\-—–－至/\\s]{1,128}"
    )
    private val FORMAT_FIELDS = setOf(
        "weeks", "week", "weekindexes", "vaildweeks", "skzc", "zcd", "zc", "qmz",
        "sections", "section", "jcs", "jc", "sksj", "jcdm2"
    )
    private val NUMERIC_SCHEDULE_FIELDS = setOf(
        "weekday", "day", "dayofweek", "xqj", "skxq", "xq", "startunit", "endunit",
        "startsection", "endsection", "skjc", "jsjc", "cxjc", "unitcount"
    )
    private val SENSITIVE_KEY_PARTS = setOf(
        "cookie", "password", "passwd", "pwd", "authorization", "token", "ticket",
        "session", "storage", "script", "href", "src", "form"
    )
    private val HEADER_TERMS = listOf(
        "课程", "课程名", "课程名称", "教师", "老师", "任课教师", "授课教师",
        "教室", "地点", "上课地点", "星期", "周几", "上课日", "周次", "上课周",
        "节次", "上课节次", "开始节次", "结束节次", "单双周",
        "course", "coursename", "teacher", "classroom", "room", "location",
        "weekday", "day", "week", "weeks", "section", "sections"
    ).associateBy { it.lowercase(Locale.ROOT).replace(Regex("[\\s_/-]"), "") }
}
