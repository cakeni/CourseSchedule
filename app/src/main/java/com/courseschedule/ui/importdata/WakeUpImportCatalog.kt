package com.courseschedule.ui.importdata

/**
 * Maps the WakeUp Schedule system-type labels kept in the bundled directory
 * (sourceType) onto the local generic import profiles, and carries the
 * per-type usage notes adapted from WakeUp's public import instructions.
 *
 * Only entries with a bundled, validated login URL are directly openable.
 * Entries with a known local parser but no usable URL ask for a school-official
 * address in the picker.
 */
internal object WakeUpImportCatalog {

    private val familyProfiles: Map<String, String> = mapOf(
        "zf" to "zhengfang",
        "qz" to "qiangzhi",
        "qz_2017" to "qiangzhi",
        "qz_2024" to "qiangzhi",
        "qz_crazy" to "qiangzhi",
        "qz_br" to "qiangzhi",
        "qz_with_node" to "qiangzhi",
        "qz_ahut" to "qiangzhi",
        "qz_bjfu" to "qiangzhi",
        "qz_ustb" to "qiangzhi",
        "qz_fspt" to "qiangzhi",
        "qz_ecust" to "qiangzhi",
        "qz_njust" to "qiangzhi",
        "qz_single_node" to "qiangzhi",
        "qz_old" to "qiangzhi",
        "jlict_qz_old" to "qiangzhi",
        "jz" to "wisedu",
        "kingo_new" to "kingosoft_new",
        "kg_zx" to "kingosoft_selected",
        "qingguo" to "kingosoft_selected",
        "south_soft" to "south_soft",
        "suda_post" to "structured",
        "zju_post" to "structured",
        "xju_post" to "structured",
        "cupl_post" to "structured",
        "scau" to "structured",
        "hitsz" to "structured",
        "hit" to "structured",
        "xhtd" to "structured",
        "uestc_post" to "structured",
        "gdei" to "structured",
        "swjtu_post" to "wisedu",
        "urp_new" to "urp_new",
        "urp" to "urp",
        "yl" to "yilian",
        "aic" to "aic",
        "umooc" to "umooc",
        "cf" to "chengfang",
        "cf_new" to "chengfang",
        "vatuu" to "vatuu",
        "shuwei_json" to "shuwei_easy",
        "shuwei_m" to "shuwei_mobile",
        "shuwei_new" to "shuwei_new",
        "xatu_shuwei" to "shuwei",
        "uestc_shuwei" to "shuwei",
        "xsyu_shuwei" to "shuwei",
        "hunnu_shuwei" to "shuwei",
        "sias_shuwei" to "shuwei",
        "login_chaoxing" to "chaoxing",
        "chaoxing_share" to "chaoxing_share",
        "login_xbellsoft" to "xbell",
        "lz" to "xbell"
    )

    /** Provenance only: a reviewed directory row may provide an independent local fallback. */
    fun isCloudOnly(sourceType: String): Boolean = sourceType == "ziyan"

    /** Only types explicitly mapped to a locally implemented parser are offered. */
    fun profileIdFor(sourceType: String): String? = familyProfiles[sourceType]

    fun profileFor(sourceType: String): GenericAcademicProfile? =
        profileIdFor(sourceType)?.let(GenericAcademicImport::profile)

    /**
     * Usage notes adapted from WakeUp Schedule's published import steps for
     * schools whose timetable page differs from the family default.
     */
    fun hintFor(name: String, sourceType: String): String? =
        hintsBySchool[name] ?: hintsByType[sourceType]

    private val hintsByType: Map<String, String> = mapOf(
        "cf_new" to "登录后进入「课表查询 → 我的课表」，只保留课表查询小窗，周次选择全部，再点击查询课表。",
        "gdbyxy" to "登录后选择左栏「教学安排」→「教学安排表」，学年学期选好，格式选「格式一」，点击检索。",
        "gxnu" to "登录后进入「已选选课列表」页面读取，不是「当前课程表」。",
        "hust" to "登录后把课表切换为「按课程」显示并查询；时间地点为「待定」的课程不会导入，请后续手动添加。",
        "javtc" to "登录系统后停在课表页面，直接读取即可。",
        "jnu" to "登录后进入「选课管理系统 → 课程表及考试表」，需要多次操作并等待页面加载。",
        "jxau" to "登录后进入「课表查询 → 本人课表查询 → 打印传统课表」页面。",
        "bfa" to "登录后进入「修读课程查询 → 学期修读课程」并查询目标学期，不是「本学期分周课表」。",
        "bfa_post" to "登录后进入「修读课程查询 → 学期修读课程」并查询目标学期，不是「本学期分周课表」。",
        "cnu" to "登录后在「主页 → 全校课表」选择自己的专业，查询本学期课表后读取。",
        "cqu" to "登录后点左上角菜单选择「我的课表」，能导入的是「我的课表」，不是选课管理。",
        "kg_zx" to "登录后进入「网上选课 → 正选结果」；打不开或无数据说明是暂不支持的青果教务。",
        "kingo_new" to "登录后进入「主控 → 教学安排」或「班级课表 → 格式二」，点击教务上的「检索」按钮，不要用导出或打印；图片格式的课表无法导入。",
        "nju" to "使用统一身份认证登录后，打开「直观课表」并切换到「学期课表」再读取。",
        "nwpu_post" to "翱翔门户登录后进入【研究生教育】应用，依次选择【课程与成绩】→【选课结果查询】，等页面完全加载后再读取。",
        "xauat_post" to "登录后进入「教学与培养 → 课表查询」页面读取。",
        "ruc" to "只适用于微人大「我的课程表（本+研）」页面，不要在选课系统页面使用；第 13–14 节的时间与教务系统不同，导入后请自行核对。",
        "shtu_post" to "研究生请打开「我的培养 → 查看课表」再读取；本科生请改用树维教务导入。",
        "shtu_post_2024" to "研究生请打开「我的培养 → 查看课表」再读取；本科生请改用树维教务导入。",
        "shuwei_new" to "登录后进入「我的课表」页面，课表卡片可能加载较慢，等加载完成后再读取。",
        "shuwei_m" to "在手机版课表页面等待课程全部加载后再读取。",
        "sysu" to "可能需要校园网或校园 VPN；登录后首页课表不可导入，请打开类似「查询课表」的含全部周课程的页面。",
        "hnjm" to "请把微信端课表页面的链接粘贴到地址栏，等页面加载完成后读取。"
    )

    private val hintsBySchool: Map<String, String> = mapOf(
        "西北农林科技大学" to "如果一直登录不上：在网址末尾斜杠后加上 hhh，登录跳到错误页后再删掉 hhh，按回车即可进入；随后打开「个人课表」（信息查询 → 学生个人课表）读取。",
        "赣南医学院" to "选择个人课表后，周次选「全部周数」，切换到「图形」模式并勾选「放大」，否则可能只能导入某一周的课表。"
    )
}
