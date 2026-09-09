package com.courseschedule.ui.importdata

import com.google.gson.JsonParser
import java.net.URI
import java.net.InetAddress
import java.util.Locale

enum class AcademicSystem {
    WISEDU,
    QIANGZHI_HTML,
    ZHENGFANG_HTML,
    URP_NEW,
    URP_HTML,
    AIC_HTML,
    CHENGFANG,
    VATUU_HTML,
    UMOOC_HTML,
    XBELL,
    EAMS,
    SHUWEI,
    SOUTH_SOFT_HTML,
    YILIAN_HTML,
    KINGOSOFT_HTML,
    CHAOXING,
    CHAOXING_SHARE,
    STRUCTURED_HTML
}

/** Audited, bundled school entries. A URL supplied by a webpage never extends this allowlist. */
data class AcademicSchool(
    val id: String,
    val name: String,
    val system: AcademicSystem,
    val loginUrl: String,
    val trustedHosts: Set<String>,
    val verified: Boolean,
    val timetableUrl: String = loginUrl,
    val loginPrefixes: List<String> = emptyList(),
    val timetablePrefixes: List<String> = emptyList(),
    val authenticationPrefixes: List<String> = emptyList(),
    val webVpnHost: String? = null,
    val genericProfileId: String? = null,
    /** Local parser/capture variant. sourceType remains catalog provenance only. */
    val adapterId: String = "",
    val allowCleartext: Boolean = false,
    val cleartextHosts: Set<String> = emptySet(),
    val allowNonDefaultPort: Boolean = false
) {
    val isGeneric: Boolean get() = id == GenericAcademicImport.SCHOOL_ID

    fun allowsNavigation(url: String?): Boolean {
        val uri = academicWebUri(url, allowCleartext, allowNonDefaultPort) ?: return false
        val host = uri.host.lowercase(Locale.ROOT)
        if (uri.scheme.equals("http", ignoreCase = true) && host !in cleartextHosts) return false
        if (host !in trustedHosts) {
            // School pages commonly hand authentication to another HTTPS subdomain
            // owned by the same university. It may navigate, but allowsTimetable()
            // still limits capture to the original exact scope.
            return uri.scheme.equals("https", ignoreCase = true) &&
                trustedHosts.any { sameEduCnInstitution(it, host) }
        }
        if (isGeneric) {
            val scope = academicUrlScope(url, allowCleartext, allowNonDefaultPort) ?: return false
            return scope in loginPrefixes || scope in timetablePrefixes || scope in authenticationPrefixes
        }
        if (host != webVpnHost) return true

        // WebVPN shares one origin between many upstream sites. Do not trust arbitrary proxy paths.
        val path = uri.rawPath.orEmpty()
        if (Regex("^/(?:https?|wss?|tcp|udp|ftp)(?:[-/]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
            return (timetablePrefixes + authenticationPrefixes).any { pageUrl(uri).startsWith(it) }
        }
        return true
    }

    fun allowsTimetable(url: String?): Boolean {
        val uri = academicWebUri(url, allowCleartext, allowNonDefaultPort) ?: return false
        if (isGeneric) return allowsNavigation(url) &&
            academicUrlScope(url, allowCleartext, allowNonDefaultPort) in timetablePrefixes
        return allowsNavigation(url) && timetablePrefixes.any { pageUrl(uri).startsWith(it) }
    }

    private fun pageUrl(uri: URI) = "${uri.scheme.lowercase(Locale.ROOT)}://${academicAuthority(uri)}" +
        uri.rawPath.orEmpty().ifBlank { "/" }

}

internal fun academicWebUri(
    url: String?,
    allowCleartext: Boolean = false,
    allowNonDefaultPort: Boolean = false
): URI? {
    if (url.isNullOrBlank() || url.length > 8192 || '\\' in url) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "https" && !(allowCleartext && scheme == "http")) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (uri.rawUserInfo != null || host.endsWith('.') || '.' !in host || isIpAddress(host)) return null
    val defaultPort = if (scheme == "https") 443 else 80
    if (uri.port != -1 && (!allowNonDefaultPort && uri.port != defaultPort || uri.port !in 1..65535)) return null
    val path = uri.rawPath.orEmpty()
    if (uri.normalize().rawPath != path || "//" in path ||
        Regex("%(?:2e|2f|5c|00)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
    return uri
}

internal fun academicHttpsUri(url: String?): URI? = academicWebUri(url)

/** A WebVPN upstream is its own scope, even when it shares a host with the portal. */
internal fun academicUrlScope(
    url: String?,
    allowCleartext: Boolean = false,
    allowNonDefaultPort: Boolean = false
): String? {
    val uri = academicWebUri(url, allowCleartext, allowNonDefaultPort) ?: return null
    val origin = "${uri.scheme.lowercase(Locale.ROOT)}://${academicAuthority(uri)}"
    val path = uri.rawPath.orEmpty()
    val proxy = Regex("^/(https?(?:-\\d+)?)/([^/]+)(?:/|$)", RegexOption.IGNORE_CASE).find(path)
    if (proxy != null) return "$origin/${proxy.groupValues[1]}/${proxy.groupValues[2]}/"
    if (Regex("^/(?:https?|wss?|tcp|udp|ftp)(?:[-/]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
    return "$origin/"
}

internal fun academicAuthority(uri: URI): String {
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val defaultPort = if (scheme == "https") 443 else 80
    val port = uri.port.takeIf { it != -1 && it != defaultPort }?.let { ":$it" }.orEmpty()
    return uri.host.lowercase(Locale.ROOT) + port
}

/** Upgrade an insecure redirect only when its HTTPS equivalent is already trusted. */
internal fun upgradedAcademicHttpsUrl(school: AcademicSchool, url: String?): String? {
    val uri = academicWebUri(url, allowCleartext = true) ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true)) return null
    val upgraded = buildString {
        append("https://")
        append(uri.host.lowercase(Locale.ROOT))
        append(uri.rawPath.orEmpty().ifBlank { "/" })
        uri.rawQuery?.let { append('?').append(it) }
    }
    return upgraded.takeIf(school::allowsNavigation)
}

private fun isIpAddress(host: String): Boolean = runCatching {
    // Avoid DNS resolution: only values composed like IP literals reach InetAddress.
    if (':' !in host && !host.matches(Regex("[0-9]+(?:\\.[0-9]+){3}"))) return@runCatching false
    InetAddress.getByName(host)
    true
}.getOrDefault(false)

private fun sameEduCnInstitution(first: String, second: String): Boolean {
    fun root(host: String): String? {
        val labels = host.lowercase(Locale.ROOT).split('.')
        if (labels.size < 3 || labels.takeLast(2) != listOf("edu", "cn")) return null
        return labels.takeLast(3).joinToString(".")
    }
    return root(first)?.let { it == root(second) } == true
}

object AcademicSchools {
    const val MAX_PAYLOAD_CHARS = 400_000
    const val MAX_COURSES = 1_500

    val SWPU = AcademicSchool(
        id = "swpu",
        name = "西南石油大学",
        system = AcademicSystem.WISEDU,
        loginUrl = "https://deanservices.swpu.edu.cn/jwapp/sys/jwauthapp/login/index.html",
        trustedHosts = setOf("swpu.edu.cn", "deanservices.swpu.edu.cn"),
        verified = true,
        adapterId = AcademicAdapterRegistry.WISEDU_AUTO,
        loginPrefixes = listOf("https://deanservices.swpu.edu.cn/"),
        timetablePrefixes = listOf("https://deanservices.swpu.edu.cn/jwapp/")
    )

    // Wengine's public URL encoding for jw.sdufe.edu.cn; this is not a session token.
    // Keep the two upstreams separate: the identity provider may navigate, but may never be captured.
    private const val SDUFE_JW_ROUTE = "77726476706e69737468656265737421fae00f8f23256e55300d8db9d6562d"
    private const val SDUFE_AUTH_ROUTE = "77726476706e69737468656265737421f9f352d234347d567b468ca88d1b203b"
    val SDUFE = AcademicSchool(
        id = "sdufe",
        name = "山东财经大学",
        system = AcademicSystem.QIANGZHI_HTML,
        loginUrl = "https://webvpn.sdufe.edu.cn/login",
        trustedHosts = setOf("webvpn.sdufe.edu.cn", "jw.sdufe.edu.cn", "ids.sdufe.edu.cn"),
        webVpnHost = "webvpn.sdufe.edu.cn",
        verified = false,
        adapterId = AcademicAdapterRegistry.QIANGZHI_STANDARD,
        loginPrefixes = listOf("https://webvpn.sdufe.edu.cn/"),
        timetableUrl = "https://webvpn.sdufe.edu.cn/http/$SDUFE_JW_ROUTE/",
        timetablePrefixes = listOf(
            "https://jw.sdufe.edu.cn/",
            "https://webvpn.sdufe.edu.cn/http/$SDUFE_JW_ROUTE/",
            "https://webvpn.sdufe.edu.cn/https/$SDUFE_JW_ROUTE/"
        ),
        // CAS first redirects through /http/ and then /https/ for this same identity
        // upstream. Both browser connections still use HTTPS to the school WebVPN.
        authenticationPrefixes = listOf(
            "https://webvpn.sdufe.edu.cn/http/$SDUFE_AUTH_ROUTE/",
            "https://webvpn.sdufe.edu.cn/https/$SDUFE_AUTH_ROUTE/"
        )
    )

    val NUAA = AcademicSchool(
        id = "nuaa",
        name = "南京航空航天大学",
        system = AcademicSystem.EAMS,
        loginUrl = "https://aao-eas.nuaa.edu.cn/eams/login.action",
        trustedHosts = setOf("aao-eas.nuaa.edu.cn", "authserver.nuaa.edu.cn"),
        verified = false,
        adapterId = AcademicAdapterRegistry.EAMS_TABLE0,
        loginPrefixes = listOf("https://aao-eas.nuaa.edu.cn/"),
        timetablePrefixes = listOf("https://aao-eas.nuaa.edu.cn/eams/"),
        authenticationPrefixes = listOf("https://authserver.nuaa.edu.cn/authserver/")
    )

    val NUAA_GRADUATE = AcademicSchool(
        id = "nuaa_graduate",
        name = "南京航空航天大学 - 研究生",
        system = AcademicSystem.SOUTH_SOFT_HTML,
        loginUrl = "https://graduate.nuaa.edu.cn/gmis5/home/stulogin",
        trustedHosts = setOf("graduate.nuaa.edu.cn", "authserver.nuaa.edu.cn"),
        verified = false,
        adapterId = AcademicAdapterRegistry.SOUTH_SOFT,
        loginPrefixes = listOf("https://graduate.nuaa.edu.cn/"),
        timetablePrefixes = listOf("https://graduate.nuaa.edu.cn/gmis5/"),
        authenticationPrefixes = listOf("https://authserver.nuaa.edu.cn/authserver/")
    )

    val all: List<AcademicSchool> = listOf(SWPU, SDUFE, NUAA, NUAA_GRADUATE)
    fun find(id: String?): AcademicSchool? = all.firstOrNull { it.id == id }

    fun parse(school: AcademicSchool, payload: String, totalWeeks: Int): ParsedImport {
        if (payload.length > MAX_PAYLOAD_CHARS) throw ImportFormatException(
            "教务数据过大，无法安全导入",
            AcademicImportErrorCode.PAYLOAD_TOO_LARGE
        )
        AcademicAdapterRegistry.parse(school, payload, totalWeeks)?.let { return it }
        val parsed = when (school.system) {
            AcademicSystem.WISEDU -> WiseduScheduleParser(totalWeeks, requireExplicitTimes = school.isGeneric).parse(payload).let { parsed ->
                if (school.isGeneric) parsed.copy(sourceLabel = "通用金智 · ${school.name}") else parsed
            }
            AcademicSystem.QIANGZHI_HTML -> {
                val snapshot = AcademicPageSnapshot.parse(school, payload)
                QiangzhiScheduleParser(totalWeeks).parse(snapshot.html).copy(
                    sourceLabel = (if (school.isGeneric) "通用强智 · ${school.name}" else "${school.name}教务系统") +
                        (normalizeWiseduTerm(snapshot.term)?.let { " · $it" } ?: " · 页面所选学期")
                )
            }
            else -> {
                val snapshot = AcademicPageSnapshot.parse(school, payload)
                val profile = GenericAcademicImport.profile(school.genericProfileId)
                val parsed = GenericAcademicScheduleParser(totalWeeks).parse(
                    system = school.system,
                    html = snapshot.html,
                    data = snapshot.data
                )
                val source = profile?.label ?: school.system.name
                parsed.copy(sourceLabel = "$source · ${school.name}" +
                    (normalizeWiseduTerm(snapshot.term)?.let { " · $it" } ?: " · 页面所选学期"))
            }
        }
        if (parsed.courses.size > MAX_COURSES) {
            throw ImportFormatException(
                "课表课程数量异常",
                AcademicImportErrorCode.PAYLOAD_TOO_LARGE
            )
        }
        return parsed
    }
}

internal data class AcademicPageSnapshot(
    val html: String,
    val term: String,
    val data: com.google.gson.JsonElement?
) {
    companion object {
        fun parse(school: AcademicSchool, payload: String): AcademicPageSnapshot {
            val root = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                ?: throw ImportFormatException("课表页面数据格式不正确，请重新读取")
            val url = root.get("sourceUrl")?.takeIf { it.isJsonPrimitive }?.asString
            if (!school.allowsTimetable(url)) {
                throw ImportFormatException(
                    "只能读取本次确认的教务域名和路径",
                    AcademicImportErrorCode.UNTRUSTED_SOURCE
                )
            }
            return AcademicPageSnapshot(
                html = root.get("html")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                term = root.get("term")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                data = root.get("data")?.takeUnless { it.isJsonNull }
            )
        }
    }
}
