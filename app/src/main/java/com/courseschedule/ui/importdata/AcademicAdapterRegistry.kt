package com.courseschedule.ui.importdata

import com.courseschedule.data.entity.Course
import org.jsoup.Jsoup
import java.net.URI

internal enum class AcademicCaptureMode {
    DOM,
    PAGE_GLOBAL,
    PAGE_FETCH,
    NETWORK_REPLAY
}

internal data class AcademicAdapterSpec(
    val id: String,
    val system: AcademicSystem,
    val selectors: List<String> = emptyList(),
    val globals: List<String> = emptyList(),
    val requestPathPrefixes: List<String> = emptyList(),
    val captureMode: AcademicCaptureMode
)

internal sealed interface AcademicAdapterParseResult {
    data object NoMatch : AcademicAdapterParseResult
    data class Success(val parsed: ParsedImport) : AcademicAdapterParseResult
    data class Failure(
        val code: AcademicImportErrorCode,
        val message: String
    ) : AcademicAdapterParseResult
}

/**
 * Local adapter identities are deliberately independent from upstream source labels.
 * A source type is provenance; an adapter id selects CourseSchedule-owned capture and
 * parsing behavior.
 */
internal object AcademicAdapterRegistry {
    const val ZHENGFANG_AUTO = "zhengfang_auto"
    const val ZHENGFANG_LEGACY = "zhengfang_legacy"
    const val ZHENGFANG_JWGLXT = "zhengfang_jwglxt"
    const val QIANGZHI_AUTO = "qiangzhi_auto"
    const val QIANGZHI_STANDARD = "qiangzhi_standard"
    const val QIANGZHI_2017 = "qiangzhi_2017"
    const val QIANGZHI_2024 = "qiangzhi_2024"
    const val QIANGZHI_BR = "qiangzhi_br"
    const val QIANGZHI_NODE = "qiangzhi_node"
    const val QIANGZHI_CRAZY = "qiangzhi_crazy"
    const val WISEDU_AUTO = "wisedu_auto"
    const val SHUWEI_LOCAL = "shuwei_local"
    const val KINGOSOFT_NEW = "kingosoft_new"
    const val KINGOSOFT_SELECTED = "kingosoft_selected"
    const val SOUTH_SOFT = "south_soft"
    const val EAMS_TABLE0 = "eams_table0"
    const val SUDA_POST_GRID = "suda_post_grid"
    const val ZJU_POST_GRID = "zju_post_grid"
    const val XJU_POST_GRID = "xju_post_grid"
    const val CUPL_POST_GRID = "cupl_post_grid"
    const val SCAU_PRINT_GRID = "scau_print_grid"
    const val HITSZ_CARD_GRID = "hitsz_card_grid"
    const val HIT_PRINT_GRID = "hit_print_grid"
    const val XHTD_BLOCK_GRID = "xhtd_block_grid"
    const val UESTC_POST_GRID = "uestc_post_grid"
    const val GDEI_NESTED_GRID = "gdei_nested_grid"

    private val specsById = listOf(
        AcademicAdapterSpec(
            ZHENGFANG_AUTO, AcademicSystem.ZHENGFANG_HTML,
            selectors = listOf("#Table1", "#kbgrid_table", "#table1", "#sycjlrtabGrid"),
            globals = listOf("veInitDefaultJson", "__INITIAL_STATE__"),
            requestPathPrefixes = listOf("/kbcx/xskbcx_cxXskbcxIndex.html"),
            captureMode = AcademicCaptureMode.PAGE_FETCH
        ),
        AcademicAdapterSpec(
            ZHENGFANG_LEGACY, AcademicSystem.ZHENGFANG_HTML,
            selectors = listOf("#Table1", "#table1"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            ZHENGFANG_JWGLXT, AcademicSystem.ZHENGFANG_HTML,
            selectors = listOf("#kbgrid_table", "#sycjlrtabGrid"),
            globals = listOf("veInitDefaultJson", "__INITIAL_STATE__"),
            requestPathPrefixes = listOf("/kbcx/xskbcx_cxXskbcxIndex.html"),
            captureMode = AcademicCaptureMode.PAGE_FETCH
        ),
        AcademicAdapterSpec(
            QIANGZHI_AUTO, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf("#kbtable", ".el-table__body", "[name=kbDataTd]"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            QIANGZHI_STANDARD, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf("#kbtable .kbcontent", "#kbtable"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            QIANGZHI_2017, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf(".el-table__header", ".el-table__body"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            QIANGZHI_2024, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf("[name=kbDataTd]", ".qz-toolitiplists"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            QIANGZHI_BR, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf("#kbtable .kbcontent", "#kbtable"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            QIANGZHI_NODE, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf("#kbtable .kbcontent", "#kbtable"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            QIANGZHI_CRAZY, AcademicSystem.QIANGZHI_HTML,
            selectors = listOf("#kbtable .kbcontent1", "#kbtable"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            WISEDU_AUTO, AcademicSystem.WISEDU,
            globals = listOf("XNXQDM"),
            requestPathPrefixes = listOf(
                "/jwapp/sys/wdkb/", "/jwapp/sys/xkjglapp/", "/gsapp/sys/wdkbapp/",
                "/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do"
            ),
            captureMode = AcademicCaptureMode.PAGE_FETCH
        ),
        AcademicAdapterSpec(
            SHUWEI_LOCAL, AcademicSystem.SHUWEI,
            selectors = listOf("#kbDiv", ".courseInfo", "table"),
            globals = listOf("activities", "unitCount", "courseUnits", "table0"),
            requestPathPrefixes = listOf("/print-data"),
            captureMode = AcademicCaptureMode.NETWORK_REPLAY
        ),
        AcademicAdapterSpec(
            KINGOSOFT_NEW, AcademicSystem.KINGOSOFT_HTML,
            selectors = listOf(".pageRpt", "#reportArea", "#mytable"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            KINGOSOFT_SELECTED, AcademicSystem.KINGOSOFT_HTML,
            selectors = listOf("#kbDiv", "#mytable", "table"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            SOUTH_SOFT, AcademicSystem.SOUTH_SOFT_HTML,
            selectors = listOf("table.tb_kcb", "#kbtable", "#kb", "table.kb", ".kb"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            SUDA_POST_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("#DataGrid1", "#MainWork_DataGrid1"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            ZJU_POST_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("#kcbForm table"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            XJU_POST_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("#ctl00_contentParent_dgData", "#contentParent_dgData"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            CUPL_POST_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("#tabCT"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            SCAU_PRINT_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("table[border=1]"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            HITSZ_CARD_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf(".ivu-table-tbody"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            HIT_PRINT_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf(".xfyq_con", "#xszp"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            XHTD_BLOCK_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("#kbtable div[id]"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            UESTC_POST_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("#tbl"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            GDEI_NESTED_GRID, AcademicSystem.STRUCTURED_HTML,
            selectors = listOf("tbody"),
            captureMode = AcademicCaptureMode.DOM
        ),
        AcademicAdapterSpec(
            EAMS_TABLE0, AcademicSystem.EAMS,
            globals = listOf("table0", "unitCount"),
            captureMode = AcademicCaptureMode.PAGE_GLOBAL
        )
    ).associateBy(AcademicAdapterSpec::id)

    private val sourceTypeAdapters = mapOf(
        "zf" to ZHENGFANG_AUTO,
        "zf_1" to ZHENGFANG_LEGACY,
        "zf_new" to ZHENGFANG_JWGLXT,
        "qz" to QIANGZHI_STANDARD,
        "qz_2017" to QIANGZHI_2017,
        "qz_2024" to QIANGZHI_2024,
        "qz_br" to QIANGZHI_BR,
        "qz_with_node" to QIANGZHI_NODE,
        "qz_crazy" to QIANGZHI_CRAZY,
        "jz" to WISEDU_AUTO,
        "jz_1" to WISEDU_AUTO,
        "jz_x" to WISEDU_AUTO,
        "kingo_new" to KINGOSOFT_NEW,
        "kg_zx" to KINGOSOFT_SELECTED,
        "qingguo" to KINGOSOFT_SELECTED,
        "south_soft" to SOUTH_SOFT,
        "suda_post" to SUDA_POST_GRID,
        "zju_post" to ZJU_POST_GRID,
        "xju_post" to XJU_POST_GRID,
        "cupl_post" to CUPL_POST_GRID,
        "scau" to SCAU_PRINT_GRID,
        "hitsz" to HITSZ_CARD_GRID,
        "hit" to HIT_PRINT_GRID,
        "xhtd" to XHTD_BLOCK_GRID,
        "uestc_post" to UESTC_POST_GRID,
        "gdei" to GDEI_NESTED_GRID,
        "swjtu_post" to WISEDU_AUTO,
        "jlict_qz_old" to QIANGZHI_STANDARD
    )

    private val profileAdapters = mapOf(
        "zhengfang" to ZHENGFANG_AUTO,
        "qiangzhi" to QIANGZHI_AUTO,
        "qiangzhi_legacy" to QIANGZHI_STANDARD,
        "wisedu" to WISEDU_AUTO,
        "shuwei_new" to SHUWEI_LOCAL,
        "shuwei_mobile" to SHUWEI_LOCAL,
        "shuwei" to SHUWEI_LOCAL,
        "shuwei_easy" to SHUWEI_LOCAL,
        "kingosoft_new" to KINGOSOFT_NEW,
        "kingosoft_selected" to KINGOSOFT_SELECTED,
        "south_soft" to SOUTH_SOFT,
        "eams" to EAMS_TABLE0
    )

    fun spec(id: String?): AcademicAdapterSpec? = id?.let(specsById::get)

    fun isKnownAdapterId(id: String?): Boolean = id.isNullOrBlank() ||
        specsById.containsKey(id) || GenericAcademicImport.profile(id) != null

    fun isCompatible(id: String?, system: AcademicSystem): Boolean =
        spec(id)?.system?.let { it == system } ?: isKnownAdapterId(id)

    fun adapterIdFor(sourceType: String?, profileId: String?): String =
        sourceType?.let(sourceTypeAdapters::get)
            ?: profileId?.let(profileAdapters::get)
            ?: profileId.orEmpty()

    fun defaultAdapterId(profileId: String?): String =
        profileId?.let(profileAdapters::get) ?: profileId.orEmpty()

    fun selectorsFor(school: AcademicSchool): List<String> =
        spec(school.adapterId)?.selectors.orEmpty()

    fun globalsFor(school: AcademicSchool): List<String> =
        spec(school.adapterId)?.globals.orEmpty()

    fun captureModeFor(school: AcademicSchool): AcademicCaptureMode =
        spec(school.adapterId)?.captureMode ?: AcademicCaptureMode.DOM

    fun allowsReplay(school: AcademicSchool, method: String?, url: String?): Boolean {
        if (!method.equals("GET", ignoreCase = true) || !school.allowsTimetable(url)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return spec(school.adapterId)?.requestPathPrefixes.orEmpty().any { prefix ->
            uri.rawPath.orEmpty().contains(prefix, ignoreCase = true)
        }
    }

    /** Returns null only for legacy profiles that are not yet registry-managed. */
    fun parse(school: AcademicSchool, payload: String, totalWeeks: Int): ParsedImport? {
        val adapterId = school.adapterId.ifBlank {
            defaultAdapterId(school.genericProfileId)
        }
        if (spec(adapterId) == null) return null
        val result = when (adapterId) {
            WISEDU_AUTO -> attempt {
                WiseduScheduleParser(totalWeeks, requireExplicitTimes = true).parse(payload)
            }
            SHUWEI_LOCAL -> withSnapshot(school, payload) { snapshot ->
                attempt {
                    GenericAcademicScheduleParser(totalWeeks).parse(
                        AcademicSystem.SHUWEI, snapshot.html, snapshot.data
                    )
                }
            }
            EAMS_TABLE0 -> withSnapshot(school, payload) { snapshot ->
                attempt { EamsScheduleParser().parse(snapshot.data) }
            }
            SOUTH_SOFT -> withHtmlSnapshot(school, payload, listOf("#kb", "table.kb", ".kb")) { html ->
                SouthSoftScheduleParser(totalWeeks).parse(html)
            }
            KINGOSOFT_NEW -> withHtmlSnapshot(
                school, payload, listOf(".pageRpt", "#reportArea", "#mytable")
            ) { html -> KingosoftScheduleParser(totalWeeks, selectedResults = false).parse(html) }
            KINGOSOFT_SELECTED -> withHtmlSnapshot(
                school, payload, listOf("#kbDiv", "#mytable", ".pageRpt", "#reportArea")
            ) { html -> KingosoftScheduleParser(totalWeeks, selectedResults = true).parse(html) }
            SUDA_POST_GRID -> withHtmlSnapshot(
                school, payload, listOf("#DataGrid1", "#MainWork_DataGrid1")
            ) { html ->
                StrictTimetableTableParser(
                    totalWeeks, "苏大研究生教务", listOf("#DataGrid1", "#MainWork_DataGrid1"),
                    orderedRowsAreSections = true, splitDoubleBreaks = true
                ).parse(html)
            }
            ZJU_POST_GRID -> withHtmlSnapshot(school, payload, listOf("#kcbForm table")) { html ->
                StrictTimetableTableParser(
                    totalWeeks, "浙大研究生教务", listOf("#kcbForm table"),
                    orderedRowsAreSections = true,
                    splitLeafCourseDivs = true
                ).parse(html)
            }
            XJU_POST_GRID -> withHtmlSnapshot(
                school, payload, listOf("#ctl00_contentParent_dgData", "#contentParent_dgData")
            ) { html ->
                StrictTimetableTableParser(
                    totalWeeks, "新疆大学研究生教务",
                    listOf("#ctl00_contentParent_dgData", "#contentParent_dgData"),
                    orderedRowsAreSections = true,
                    compactBraceFormat = true,
                    defaultAllWeeks = true
                ).parse(html)
            }
            CUPL_POST_GRID -> withHtmlSnapshot(school, payload, listOf("#tabCT")) { html ->
                StrictTimetableTableParser(
                    totalWeeks, "法大研究生教务", listOf("#tabCT"),
                    orderedRowsAreSections = true,
                    splitBoldBlocks = true
                ).parse(html)
            }
            SCAU_PRINT_GRID -> withHtmlSnapshot(school, payload, listOf("table[border=1]")) { html ->
                ScauScheduleParser(totalWeeks).parse(html)
            }
            HITSZ_CARD_GRID -> withHtmlSnapshot(school, payload, listOf(".ivu-table-tbody")) { html ->
                HitszScheduleParser(totalWeeks).parse(html)
            }
            HIT_PRINT_GRID -> withHtmlSnapshot(school, payload, listOf(".xfyq_con", "#xszp")) { html ->
                HitScheduleParser(totalWeeks).parse(html)
            }
            XHTD_BLOCK_GRID -> withHtmlSnapshot(school, payload, listOf("#kbtable div[id]")) { html ->
                XhtdScheduleParser(totalWeeks).parse(html)
            }
            UESTC_POST_GRID -> withHtmlSnapshot(school, payload, listOf("#tbl")) { html ->
                UestcPostScheduleParser(totalWeeks).parse(html)
            }
            GDEI_NESTED_GRID -> withHtmlSnapshot(school, payload, listOf("tbody")) { html ->
                GdeiScheduleParser(totalWeeks).parse(html)
            }
            ZHENGFANG_LEGACY -> parseZhengfang(school, payload, totalWeeks, legacy = true)
            ZHENGFANG_JWGLXT -> parseZhengfang(school, payload, totalWeeks, legacy = false)
            ZHENGFANG_AUTO -> parseZhengfangAuto(school, payload, totalWeeks)
            QIANGZHI_AUTO -> parseQiangzhiAuto(school, payload, totalWeeks)
            QIANGZHI_STANDARD, QIANGZHI_2017, QIANGZHI_2024,
            QIANGZHI_BR, QIANGZHI_NODE, QIANGZHI_CRAZY ->
                parseQiangzhi(school, payload, totalWeeks, adapterId)
            else -> AcademicAdapterParseResult.NoMatch
        }
        return when (result) {
            AcademicAdapterParseResult.NoMatch -> throw ImportFormatException(
                "当前页面不属于已登记的本地解析变体",
                AcademicImportErrorCode.UNSUPPORTED_VARIANT
            )
            is AcademicAdapterParseResult.Failure -> throw ImportFormatException(result.message, result.code)
            is AcademicAdapterParseResult.Success -> validate(result.parsed).copy(
                sourceLabel = sourceLabel(school, result.parsed.sourceLabel)
            )
        }
    }

    private fun parseZhengfang(
        school: AcademicSchool,
        payload: String,
        totalWeeks: Int,
        legacy: Boolean
    ): AcademicAdapterParseResult = withSnapshot(school, payload) { snapshot ->
        val document = Jsoup.parse(snapshot.html)
        val matched = if (legacy) {
            document.selectFirst("#Table1,#table1") != null
        } else {
            snapshot.data != null || document.selectFirst("#kbgrid_table,#sycjlrtabGrid") != null
        }
        if (!matched) return@withSnapshot AcademicAdapterParseResult.NoMatch
        attempt {
            GenericAcademicScheduleParser(totalWeeks).parse(
                AcademicSystem.ZHENGFANG_HTML,
                snapshot.html,
                if (legacy) null else snapshot.data
            )
        }
    }

    private fun parseZhengfangAuto(
        school: AcademicSchool,
        payload: String,
        totalWeeks: Int
    ): AcademicAdapterParseResult = withSnapshot(school, payload) { snapshot ->
        val document = Jsoup.parse(snapshot.html)
        val candidates = buildList {
            if (snapshot.data != null || document.selectFirst("#kbgrid_table,#sycjlrtabGrid") != null) {
                add(false)
            }
            if (document.selectFirst("#Table1,#table1") != null) add(true)
        }
        resolveVariants(candidates.map { legacy ->
            attempt {
                GenericAcademicScheduleParser(totalWeeks).parse(
                    AcademicSystem.ZHENGFANG_HTML,
                    snapshot.html,
                    if (legacy) null else snapshot.data
                )
            }
        })
    }

    private fun parseQiangzhi(
        school: AcademicSchool,
        payload: String,
        totalWeeks: Int,
        variantId: String
    ): AcademicAdapterParseResult = withSnapshot(school, payload) { snapshot ->
        val parser = QiangzhiScheduleParser(totalWeeks, variantId)
        val document = Jsoup.parse(snapshot.html)
        if (!parser.matches(document)) AcademicAdapterParseResult.NoMatch
        else attempt {
            parser.parseDocument(document).copy(sourceLabel = qiangzhiSourceLabel(school, snapshot.term))
        }
    }

    private fun parseQiangzhiAuto(
        school: AcademicSchool,
        payload: String,
        totalWeeks: Int
    ): AcademicAdapterParseResult = withSnapshot(school, payload) { snapshot ->
        val document = Jsoup.parse(snapshot.html)
        val variants = when {
            QiangzhiScheduleParser(totalWeeks, QIANGZHI_2024).matches(document) -> listOf(QIANGZHI_2024)
            QiangzhiScheduleParser(totalWeeks, QIANGZHI_2017).matches(document) -> listOf(QIANGZHI_2017)
            QiangzhiScheduleParser(totalWeeks, QIANGZHI_CRAZY).matches(document) -> listOf(QIANGZHI_CRAZY)
            else -> listOf(QIANGZHI_BR, QIANGZHI_NODE, QIANGZHI_STANDARD).filter { variant ->
                QiangzhiScheduleParser(totalWeeks, variant).matches(document)
            }
        }
        resolveVariants(variants.map { variant ->
            attempt {
                QiangzhiScheduleParser(totalWeeks, variant).parseDocument(document).copy(
                    sourceLabel = qiangzhiSourceLabel(school, snapshot.term)
                )
            }
        })
    }

    private inline fun withHtmlSnapshot(
        school: AcademicSchool,
        payload: String,
        selectors: List<String>,
        parse: (String) -> ParsedImport
    ): AcademicAdapterParseResult = withSnapshot(school, payload) { snapshot ->
        val document = Jsoup.parse(snapshot.html)
        if (selectors.none { document.selectFirst(it) != null }) AcademicAdapterParseResult.NoMatch
        else attempt { parse(snapshot.html) }
    }

    private inline fun withSnapshot(
        school: AcademicSchool,
        payload: String,
        block: (AcademicPageSnapshot) -> AcademicAdapterParseResult
    ): AcademicAdapterParseResult = try {
        block(AcademicPageSnapshot.parse(school, payload))
    } catch (error: ImportFormatException) {
        AcademicAdapterParseResult.Failure(
            error.errorCode ?: inferErrorCode(error.message),
            error.message ?: "课表页面数据不完整"
        )
    }

    private inline fun attempt(block: () -> ParsedImport): AcademicAdapterParseResult = try {
        AcademicAdapterParseResult.Success(validate(block()))
    } catch (error: ImportFormatException) {
        AcademicAdapterParseResult.Failure(
            error.errorCode ?: inferErrorCode(error.message),
            error.message ?: "课表字段不完整"
        )
    } catch (_: RuntimeException) {
        AcademicAdapterParseResult.Failure(
            AcademicImportErrorCode.PARTIAL_PARSE,
            "课表结构已匹配，但内容无法完整解析"
        )
    }

    internal fun resolveVariants(results: List<AcademicAdapterParseResult>): AcademicAdapterParseResult {
        if (results.isEmpty()) return AcademicAdapterParseResult.NoMatch
        results.filterIsInstance<AcademicAdapterParseResult.Failure>().firstOrNull()?.let { return it }
        val successes = results.filterIsInstance<AcademicAdapterParseResult.Success>()
        if (successes.isEmpty()) return AcademicAdapterParseResult.NoMatch
        val distinct = successes.distinctBy { canonical(it.parsed.courses) }
        return if (distinct.size == 1) distinct.first() else AcademicAdapterParseResult.Failure(
            AcademicImportErrorCode.AMBIGUOUS_VARIANT,
            "多个教务解析变体得到不同结果，请导出脱敏诊断后反馈"
        )
    }

    private fun validate(parsed: ParsedImport): ParsedImport {
        if (parsed.courses.isEmpty()) {
            throw ImportFormatException("当前页面没有课程", AcademicImportErrorCode.NO_TIMETABLE)
        }
        if (parsed.courses.size > AcademicSchools.MAX_COURSES) {
            throw ImportFormatException(
                "课表课程数量异常",
                AcademicImportErrorCode.PAYLOAD_TOO_LARGE
            )
        }
        parsed.courses.forEach { course ->
            if (course.dayOfWeek !in 1..7) {
                throw ImportFormatException("课程星期缺失", AcademicImportErrorCode.MISSING_DAY)
            }
            if (course.startSection !in 1..30 || course.endSection !in course.startSection..30) {
                throw ImportFormatException("课程节次缺失", AcademicImportErrorCode.MISSING_SECTION)
            }
            if (course.startWeek !in 1..52 || course.endWeek !in course.startWeek..52) {
                throw ImportFormatException("课程周次缺失", AcademicImportErrorCode.MISSING_WEEK)
            }
        }
        return parsed
    }

    private fun canonical(courses: List<Course>): List<String> = courses.map { course ->
        listOf(
            course.courseName.trim(), course.teacher.trim(), course.classroom.trim(),
            course.dayOfWeek, course.startSection, course.endSection,
            course.startWeek, course.endWeek, course.weekType
        ).joinToString("\u001f")
    }.sorted()

    private fun inferErrorCode(message: String?): AcademicImportErrorCode = when {
        message.orEmpty().contains("星期") -> AcademicImportErrorCode.MISSING_DAY
        message.orEmpty().contains("节次") -> AcademicImportErrorCode.MISSING_SECTION
        message.orEmpty().contains("周次") || message.orEmpty().contains("周次位图") ->
            AcademicImportErrorCode.MISSING_WEEK
        message.orEmpty().contains("过大") || message.orEmpty().contains("过多") ->
            AcademicImportErrorCode.PAYLOAD_TOO_LARGE
        message.orEmpty().contains("iframe", true) || message.orEmpty().contains("框架") ->
            AcademicImportErrorCode.FRAME_BLOCKED
        message.orEmpty().contains("未找到") || message.orEmpty().contains("没有课程") ->
            AcademicImportErrorCode.NO_TIMETABLE
        else -> AcademicImportErrorCode.PARTIAL_PARSE
    }

    private fun sourceLabel(school: AcademicSchool, parsedLabel: String): String {
        if (school.system == AcademicSystem.QIANGZHI_HTML) return parsedLabel
        if (school.system == AcademicSystem.WISEDU) {
            return if (school.isGeneric) "通用金智 · ${school.name}" else parsedLabel
        }
        val family = GenericAcademicImport.profile(school.genericProfileId)?.label
            ?: parsedLabel.ifBlank { school.system.name }
        return "$family · ${school.name}"
    }

    private fun qiangzhiSourceLabel(school: AcademicSchool, term: String): String =
        (if (school.isGeneric) "通用强智 · ${school.name}" else "${school.name}教务系统") +
            (normalizeWiseduTerm(term)?.let { " · $it" } ?: " · 页面所选学期")
}
