# WakeUp 教务适配思路拆解手册

来源：WakeUp 课程表 6.1.20（2025-11 快照）与早期构建（2025-09，majorVersion 53）
的拆包分析。本手册只记录**行为事实**（页面路径、接口地址、参数名、数据形态、
操作步骤），不含任何 WakeUp 代码；CourseSchedule 的实现均为独立编写。
当前核对的拆包产物在本地 `apk_unpack_20260907_182908`，反编译审计副本在
`analysis/unpacked-adapter-audit-20260907`（不随应用打包）。

## WakeUp 的整体架构（三条导入通道）

| 通道 | 规模 | 机制 | 可迁移性 |
|---|---|---|---|
| 非云端配置（通用类型为主） | 2478 条非 ziyan 入口 | 按 type 注入抓取脚本、抓 DOM/JSON，少量再分派专属流程 | ✅ 思路可学 |
| 其中专属原生实现（login_school 包） | 9 校（jlu/hust/suda/nwpu/sustech/jxau/nau/hfu/jjvu） | 不走通用 WebView 抓取，原生 OkHttp 登录 + 请求接口 + 解析 | ✅ 接口事实可参考 |
| 智研云解析（ziyan） | 当前目录 1088 条 | 上游会把网页源码上传到远端解析，APK 内无解析逻辑 | ❌ 云端实现不可迁移；本项目为 984 条另配本地实验入口 |

当前目录的最大类型是 `ziyan` 1088、`zf` 969、`qz` 362、`kingo_new` 173、
`jz` 128、`south_soft` 114；这进一步确认它靠少量类型路线覆盖学校。历史上
2025-09 → 2025-11 两个版本间新增的 1136 所学校也几乎全是 ziyan，
即**新学校主要靠云服务兜底，本地适配库没有膨胀**。本地解析器类在两个版本间
完全一致（schedule_parser 包 diff 为空）。

## 家族 × 技术对照表（核心事实）

### 强智 QiangZhi（qz，约 360 所）
- 数据形态：服务端渲染 HTML 表格，页面为「学期理论课表」。
- WakeUp 不构造跳转 URL，靠**操作指引**让用户自己进入正确页面（已移植到
  WakeUpImportCatalog）。
- 变体极多（qz / qz_2017 / qz_2024 / qz_crazy / qz_br / qz_with_node 等 6+
  种），DOM 结构因年代和二开而不同；WakeUp 的做法是在 UI 上让用户选变体
  单选框，再走对应解析分支。
- 对我们的启示：目录里 qz 变体已映射到同一 qiangzhi profile；若解析失败率
  高，下一步是在导入失败提示里按特征（如表格 id、字段拼写）建议变体重试。

### 正方 ZhengFang（zf，约 970 所）
- 数据形态：服务端渲染 HTML 表格；必须在教务设置里开启「显示上课时间、
  教室、老师」，否则列缺失。
- 旧版正方（如苏州大学 suda 专用流程）用 `xskbcx.aspx?xh=<学号>&xm=<姓名>
  &gnmkdm=N121603`（gb2312 编码）直接取课表页，参数 xnd/xm 为学年学期。
- 新版正方（jwglxt）常见接口路径 `kbcx/xskbcx_cxXskbcxIndex.html`（功能码
  gnmkdm=N2151 一类），多为 JSON 返回。
- 对我们的启示：zhengfang profile 先读取 `veInitDefaultJson`，再在页面明确给出
  `xnm/xqm` 时尝试同源 JSON 接口，最后回退到严格 DOM 表格解析（已实现）。

### 金智 Wisedu（jz，约 128 所；另有研究生 gsapp）
- 本科 jwapp：接口 `sys/xkjglapp/modules/xskcb/xsjxrwcx.do?XNXQDM=<学期>`
  返回 JSON（CourseSchedule 已实现原生 bridge 抓取）。
- 研究生 gsapp：`gsapp/sys/wdkbapp/modules/xskcb/xsjxrwcx.do?XNXQDM=<学期>`。
- 移动端 homeapp：`sys/homeapp/api/home/student/getMyScheduleDetail.do`，
  body `termCode=<2024-2025-2>&campusCode=&type=term`，从页面 JS 上下文内
  fetch（带 cookie）。
- 学期码正则：`20\d\d-20\d\d-\d`；页面文本兜底「20xx-20xx学年 第x学期」。

### 树维 ShuWei / 新树维（shuwei* + sues + xatu，约 100 所）
- 新树维（for-std 架构，含 sues、西工大本科等）核心接口链：
  1. `for-std/course-table/get-data?bizTypeId=<2|23|...>&semesterId=<id>&dataId=<id>`
     → 返回 lessonIds
  2. `for-std/course-table/semester/<semesterId>/print-data/<dataId>?hasExperiment=true`
     → 返回完整课表 JSON（activities 数组）
- print-data JSON 的 activities 形态：`{courseName, weekday(1-7), startUnit,
  endUnit, weekIndexes:[..], teachers:[..], room}`（GenericAcademicScheduleParser
  的 parseShuwei 已支持该形态）。
- WakeUp 的抓法：WebView 里 shouldInterceptRequest 用 OkHttp 带 cookie 重放
  GET 请求，把响应体截下来（对 shuwei*/cumtb/sues 开启）；页面照常渲染，
  数据同时被截获。
- 老树维（jsxsd 架构）：整页 HTML 表格 + 页面全局变量（unitCount 矩阵）。

### EAMS（南航 aao-eas 一类）
- 课表数据在页面 JS 全局 `table0`（数组）+ `unitCount`（节次数）里，
  存在于主文档或 iframe 的 contentWindow。
- WakeUp 注入 save2json 遍历 iframe 找 `window.table0`，JSON.stringify 后
  截取特定前缀。

### 青果 KingoSoft（kingo_new/kg_zx，约 175 所）
- kingo_new：「主控 → 教学安排」或「班级课表 → 格式二」，点检索后读 DOM
  （.pageRpt 等报表容器）；图片化课表不可导。
- kg_zx（正选结果）：「网上选课 → 正选结果」页面 DOM。
- json 形态（KingoInfo bean）：课程含 `kcmc/jsmc/xqj/djj/zc` 一类字段。

### 其他家族速记
- 乘方 cf/cf_new：页面 `window.kbxx` 数组，字段 `kcmc/xq/jcdm2/zcs/jxcdmcs/teaxms`
  （已实现）。
- 凌展 lz：`kcmc/xqj/djj/qmz/dsz/jsxm/skdd`（已实现）。
- URP：新 URP JSON `dateList[].selectCourseList[].timeAndPlaceList[]`
  （classDay/classSessions/continuingSession/classWeek）（已实现）。
- 超星：`kcmc/xq/djc/zc`；分享课表 `name/dayOfWeek/beginNumber/length/weeks`（已实现）。

## 跨家族通用技巧（WakeUp 全在用的）

1. **操作指引驱动**：每类型一段「进哪个菜单、点什么按钮、选什么学期」的
   固定文案（已移植 WakeUpImportCatalog.hintsByType / hintsBySchool）。
2. **iframe 穿透**：教务页面大量嵌 iframe，抓取时遍历 contentDocument /
   contentWindow（AcademicCaptureScript 已实现）。
3. **网络层截流**：对数据走 XHR 的站点，在 WebViewClient.shouldInterceptRequest
   里带 cookie 重放 GET 截获响应体（本次已为树维 print-data 实现）。
4. **页面内 fetch**：已在登录态的页面上下文里直接 fetch 已知接口，天然带
   cookie、跨域限制更宽松（金智 jwapp bridge 已用此法）。
5. **学期码自动识别**：正则 `20\d\d-20\d\d-\d` + 「学年/学期」中文兜底
   （normalizeWiseduTerm 已实现）。
6. **PC 模式视口**：注入 viewport 强制桌面宽度，避免移动版页面出现
   （set_meta 思路；CourseSchedule 用普通 WebView 未做，暂不需要）。
7. **失败文案分层**：解析失败时按「页面没有课程信息 / 选错页面 / 教务未
   适配」三种提示（导入页已有类似文案，可再细化）。

## 专属原生实现清单（login_school 包，第二阶段候选）

| 学校 | 包内文件数 | 特征 |
|---|---|---|
| 吉林大学 jlu | 25 | 最复杂，多校区多学制 |
| 华中科技大学 hust | 12 | 按课程视图解析 |
| 苏州大学 suda | 6 | 旧正方 gnmkdm=N121603 + gb2312 |
| 南科大 sustech | 3 | — |
| 合肥大学 hfu / 九江职业 jjvu / 江西农业 jxau / 南京审计 nau / 西工大 nwpu | 各 2-6 | nwpu 用 for-std 接口（jwxt.nwpu.edu.cn/student/...） |

这些流程的关键事实（登录端点、表单字段、接口顺序）都在反编译源码里，
移植时逐校阅读后用 OkHttp/HttpURLConnection 独立实现。

## 落地路线（按性价比）

1. ✅ 树维 print-data 网络截流（本次实现，shuwei_new/sues/xatu_shuwei ~70 所受益）
2. ✅ 正方常见 JSON 字段、`veInitDefaultJson` 与同源接口兜底（仍需真机样本扩充字段）
3. ✅ 强智常见表格标识与结构特征识别（仍需各二开版本样本扩充）
4. ⬜ 专属学校逐校移植（suda_post 32 所优先，需样本+登录账号验证）
5. 🟨 智研 1088 条：不迁移云端实现；984 条已用复核入口接入本地通用解析，104 条继续通过入口核验和脱敏样本逐校本地化
