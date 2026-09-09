package com.courseschedule.ui.importdata

import java.util.Locale
import java.net.URI

internal data class GenericAcademicProfile(
    val id: String,
    val label: String,
    val system: AcademicSystem,
    val instructions: String
)

/** User-confirmed addresses are scoped to one import session, never added to the school registry. */
internal object GenericAcademicImport {
    const val SCHOOL_ID = "generic"
    val profiles = listOf(
        GenericAcademicProfile("wisedu", "金智教务（jwapp / gsapp）", AcademicSystem.WISEDU,
            "进入信息查询 → 学生个人课表，并切换到学期课表后读取。"),
        GenericAcademicProfile("aic", "AIC 教务", AcademicSystem.AIC_HTML,
            "进入学期课表；不要停留在只显示一周的周课表。"),
        GenericAcademicProfile("urp_new", "URP 新版系统", AcademicSystem.URP_NEW,
            "进入本学期课表，等课程完全加载后读取。"),
        GenericAcademicProfile("urp", "URP 系统", AcademicSystem.URP_HTML,
            "进入本学期课表；当前页面必须显示课程、星期、节次和周次。"),
        GenericAcademicProfile("vatuu", "为途教务", AcademicSystem.VATUU_HTML,
            "打开不显示学分的完整学期课表后读取。"),
        GenericAcademicProfile("chengfang", "乘方教务", AcademicSystem.CHENGFANG,
            "进入课表查询 → 我的课表，周次选择全部并查询。"),
        GenericAcademicProfile("umooc", "优慕课在线", AcademicSystem.UMOOC_HTML,
            "选择按小节显示的课表（第 1、2 节分开），再读取。"),
        GenericAcademicProfile("xbell", "凌展教务", AcademicSystem.XBELL,
            "进入包含完整周次与节次的个人课表，等页面加载完成后读取。"),
        GenericAcademicProfile("eams", "EAMS 教务", AcademicSystem.EAMS,
            "进入完整学期课表，等课程加载完成后读取。"),
        GenericAcademicProfile("south_soft", "南软教务", AcademicSystem.SOUTH_SOFT_HTML,
            "进入培养管理 → 学生课表查询，选择学期并查询。"),
        GenericAcademicProfile("yilian", "奕联教务", AcademicSystem.YILIAN_HTML,
            "进入个人课表，确认页面显示周次、节次、教师和地点。"),
        GenericAcademicProfile("qiangzhi", "强智教务", AcademicSystem.QIANGZHI_HTML,
            "进入我的课表 → 学期理论课表并查询完整学期。"),
        GenericAcademicProfile("topology", "拓扑教务", AcademicSystem.STRUCTURED_HTML,
            "打开包含课程、星期、节次和周次列的完整课表。"),
        GenericAcademicProfile("structured", "通用 HTML 课表", AcademicSystem.STRUCTURED_HTML,
            "打开包含课程、星期、节次和周次的完整课表；页面必须显示这些字段。"),
        GenericAcademicProfile("shuwei_new", "新树维教务", AcademicSystem.SHUWEI,
            "进入我的课表，等课表卡片全部加载后读取。"),
        GenericAcademicProfile("kingosoft_new", "新青果教学安排表", AcademicSystem.KINGOSOFT_HTML,
            "进入主控 → 教学安排，或班级课表 → 格式二，点击检索后读取。"),
        GenericAcademicProfile("qiangzhi_legacy", "旧强智教务", AcademicSystem.QIANGZHI_HTML,
            "进入学期理论课表；页面中必须能看到每门课的周次和节次。"),
        GenericAcademicProfile("shuwei_mobile", "树维手机导入", AcademicSystem.SHUWEI,
            "在手机课表页面等待课程加载完成后读取。"),
        GenericAcademicProfile("shuwei", "树维教务", AcademicSystem.SHUWEI,
            "进入我的课表并等待完整学期数据加载。"),
        GenericAcademicProfile("shuwei_easy", "树维教务（简易导入）", AcademicSystem.SHUWEI,
            "进入我的课表；若网页只显示当前周，请先切换到完整学期。"),
        GenericAcademicProfile("zhengfang", "正方教务", AcademicSystem.ZHENGFANG_HTML,
            "打开个人课表，并在网页设置中显示上课时间、教师和教室。"),
        GenericAcademicProfile("chaoxing_share", "超星分享课表导入", AcademicSystem.CHAOXING_SHARE,
            "打开无需登录即可查看的超星课表分享链接后读取。"),
        GenericAcademicProfile("chaoxing", "超星教务", AcademicSystem.CHAOXING,
            "登录超星教务并进入完整课表页面后读取。"),
        GenericAcademicProfile("kingosoft_selected", "青果教务（正选结果导入）", AcademicSystem.KINGOSOFT_HTML,
            "进入网上选课 → 正选结果，确认有课程数据后读取。")
    )
    val systems = profiles.map(GenericAcademicProfile::system).distinct()

    fun profile(id: String?): GenericAcademicProfile? = profiles.firstOrNull { it.id == id }

    fun create(profile: GenericAcademicProfile, address: String): AcademicSchool =
        create(profile.system, address, profile.id)

    fun create(system: AcademicSystem, address: String): AcademicSchool {
        return create(system, address, profiles.firstOrNull { it.system == system }?.id)
    }

    fun create(profileId: String, address: String): AcademicSchool {
        val profile = profile(profileId) ?: throw ImportFormatException("不支持的教务系统类型")
        return create(profile.system, address, profile.id)
    }

    /** Bundled catalog entries may use a reviewed HTTP host or a legacy non-default port. */
    fun createCatalog(
        profile: GenericAcademicProfile,
        address: String,
        allowCleartext: Boolean,
        authenticationUrls: List<String> = emptyList(),
        timetableUrls: List<String> = emptyList(),
        adapterId: String = AcademicAdapterRegistry.defaultAdapterId(profile.id),
        loginUrls: List<String> = emptyList()
    ): AcademicSchool {
        val school = create(profile.system, address, profile.id, allowCleartext, allowNonDefaultPort = true)
        val authenticationUris = authenticationUrls.map {
            checkedAddress(it, allowCleartext, allowNonDefaultPort = true)
        }
        val timetableUris = timetableUrls.map {
            checkedAddress(it, allowCleartext, allowNonDefaultPort = true)
        }
        val timetableScopes = timetableUris.map {
            academicUrlScope(it.toString(), allowCleartext, allowNonDefaultPort = true)
                ?: throw ImportFormatException("课表读取地址范围无效")
        }
        val loginUris = loginUrls.map {
            checkedAddress(it, allowCleartext, allowNonDefaultPort = true)
        }
        val loginScopes = loginUris.map {
            academicUrlScope(it.toString(), allowCleartext, allowNonDefaultPort = true)
                ?: throw ImportFormatException("登录地址范围无效")
        }
        if (!AcademicAdapterRegistry.isCompatible(adapterId, profile.system)) {
            throw ImportFormatException("不支持的本地解析适配器")
        }
        return school.copy(
            trustedHosts = school.trustedHosts +
                authenticationUris.map { it.host.lowercase(Locale.ROOT) } +
                timetableUris.map { it.host.lowercase(Locale.ROOT) } +
                loginUris.map { it.host.lowercase(Locale.ROOT) },
            authenticationPrefixes = authenticationUris.map {
                academicUrlScope(it.toString(), allowCleartext, allowNonDefaultPort = true)
                    ?: throw ImportFormatException("认证地址范围无效")
            },
            timetablePrefixes = timetableScopes.ifEmpty { school.timetablePrefixes },
            loginPrefixes = (school.loginPrefixes + loginScopes).distinct(),
            adapterId = adapterId.ifBlank { AcademicAdapterRegistry.defaultAdapterId(profile.id) },
            cleartextHosts = school.cleartextHosts + (authenticationUris + timetableUris + loginUris)
                .filter { it.scheme.equals("http", ignoreCase = true) }
                .map { it.host.lowercase(Locale.ROOT) }
        )
    }

    private fun create(
        system: AcademicSystem,
        address: String,
        profileId: String?,
        allowCleartext: Boolean = false,
        allowNonDefaultPort: Boolean = false
    ): AcademicSchool {
        val uri = checkedAddress(address, allowCleartext, allowNonDefaultPort)
        val host = uri.host.lowercase(Locale.ROOT)
        val scope = academicUrlScope(uri.toString(), allowCleartext, allowNonDefaultPort)
            ?: throw ImportFormatException("无法确认教务地址的访问范围")
        val origin = "${uri.scheme.lowercase(Locale.ROOT)}://${academicAuthority(uri)}/"
        if (host.contains("vpn") && scope == origin) {
            throw ImportFormatException("请粘贴 WebVPN 中具体教务资源的地址，而不是 VPN 门户登录地址；已列出的学校可直接选择学校")
        }
        // Do not retain pasted tickets, query parameters or fragments in Activity extras/state.
        val cleanPath = uri.rawPath.orEmpty()
            .replace(Regex(";jsessionid=[^/]*", RegexOption.IGNORE_CASE), "")
            .ifBlank { "/" }
        val cleanAddress = "${uri.scheme.lowercase(Locale.ROOT)}://${academicAuthority(uri)}" +
            cleanPath
        val timetableScopes = buildList {
            add(scope)
            if (uri.scheme.equals("http", ignoreCase = true)) {
                add("https://$host/")
                uri.port.takeIf { it != -1 && it != 80 }?.let { add("https://$host:$it/") }
            }
        }.distinct()
        return AcademicSchool(
            id = SCHOOL_ID, name = host, system = system, loginUrl = cleanAddress,
            trustedHosts = setOf(host), verified = false, loginPrefixes = listOf(scope),
            timetablePrefixes = timetableScopes,
            genericProfileId = profileId,
            adapterId = AcademicAdapterRegistry.defaultAdapterId(profileId),
            allowCleartext = allowCleartext,
            cleartextHosts = if (allowCleartext) setOf(host) else emptySet(),
            allowNonDefaultPort = allowNonDefaultPort
        )
    }

    private fun checkedAddress(
        address: String,
        allowCleartext: Boolean = false,
        allowNonDefaultPort: Boolean = false
    ): URI {
        val entered = address.trim().let { if ("://" in it) it else "https://$it" }
        val uri = academicWebUri(entered, allowCleartext, allowNonDefaultPort)
            ?: throw ImportFormatException("请输入学校的 HTTPS 网址，不支持明文 HTTP、特殊端口、IP 地址或含账号的地址；也可使用 HTML 文件导入")
        val host = uri.host.lowercase(Locale.ROOT)
        if ('.' !in host || host.endsWith('.') || host.matches(Regex("[0-9.]+")) ||
            ':' in host || host.endsWith(".local") || host.endsWith(".localhost")) {
            throw ImportFormatException("请填写学校官方域名，不能使用本机或 IP 地址")
        }
        return uri
    }

    fun authenticationScope(school: AcademicSchool, url: String): String? {
        val uri = runCatching { checkedAddress(url) }.getOrNull() ?: return null
        return academicUrlScope(uri.toString()).takeUnless { school.allowsNavigation(url) }
    }

    fun allowAuthentication(school: AcademicSchool, url: String): AcademicSchool {
        val scope = authenticationScope(school, url)
            ?: throw ImportFormatException("无法授权此认证地址")
        val host = academicHttpsUri(url)!!.host.lowercase(Locale.ROOT)
        return school.copy(
            trustedHosts = school.trustedHosts + host,
            authenticationPrefixes = school.authenticationPrefixes + scope
        )
    }

    fun wiseduBasePath(url: String): String {
        val uri = academicWebUri(url, allowCleartext = true, allowNonDefaultPort = true)
            ?: throw ImportFormatException("教务网址无效")
        val path = uri.rawPath.orEmpty()
        val gsapp = Regex("/gsapp(?:/|$)").find(path)
        if (gsapp != null) return path.substring(0, gsapp.range.first) + "/gsapp"
        val jwapp = Regex("/jwapp(?:/|$)").find(path)
        if (jwapp != null) return path.substring(0, jwapp.range.first) + "/jwapp"
        val scope = academicUrlScope(url, allowCleartext = true, allowNonDefaultPort = true)
            ?: throw ImportFormatException("教务网址无效")
        return academicWebUri(scope, allowCleartext = true, allowNonDefaultPort = true)!!
            .rawPath.trimEnd('/') + "/jwapp"
    }
}
