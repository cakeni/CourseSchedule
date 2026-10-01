# 晴课表 · HarmonyOS NEXT

以本仓库 Android **1.0.13** 的界面、资源、交互和数据格式为对照的原生 ArkTS/ArkUI 工程。开发版 **0.1.0**，Stage 模型，最低 API **17**；当前使用 DevEco Studio 26 和 HarmonyOS SDK 26 构建。

## 已实现

- 周课表、日期和周次、左右切周、进度拖动、回到当前周、各周滚动位置；显示周末、节次时间及非本周课程。
- 原版深浅色、16 色课程卡片、底部导航和图标；变化数字滚动、导航图标动作、导入卡片错峰上浮、设置项错峰滑入、弹窗及详情过渡、按压缩放。
- 原 Rive 吉祥物的 60 帧/秒采样资源，300 帧约 5 秒待机循环。应用内用原生 ImageAnimator 播放；导出过程关闭自动时钟，每帧手动推进 1/60 秒并刷新画布。
- 手动添加、编辑、删除、颜色、教师、地点、备注、单双周、节次、周次、提前提醒及冲突检查；长按空白格、拖动选择连续节次后快速添加。
- 多学期创建、切换、编辑与删除；12 节开始和结束时间、默认提醒及批量应用；本地 Preferences 持久化。
- 学校目录、中文/拼音搜索和分类筛选；教务网页登录页可直接修改网址并打开，支持手机/桌面模式、横屏、读取课表和脱敏诊断。
- JSON、CSV、HTML、XLSX、文本导入；预览、无效项/重复/冲突检查、追加/替换、可选学期及设置恢复、导入撤销；系统文件选择器导入及 Android v2 格式导出。
- 原版可选 DeepSeek/OpenAI 网页识别入口。使用者在界面中自行输入 Key，仅用于当次请求，不写入课表或本地设置。
- 原生系统通知授权与代理提醒调度：提前量、单双周排除、学期起止、临近课程补发、重新排程与测试提醒。

## 验证范围与待验收项

本地 HarmonyOS **7.0.0 / API 26** 手机模拟器已验证课程增改删、学期管理、备份实际导出与重导、导入撤销、教务登录页、系统文件读取、XLSX 原生解压和 ArkWeb 采集。纯规则校验覆盖导入、周次、冲突、提醒时间和原版 12 种教务报表样例。

仍需单独验收：

- 当前未签名模拟器构建发布代理提醒返回 **1700002**；通知授权和调度代码已实现，**提醒送达尚未通过**。需要在具备相应应用能力和签名配置的环境中复验。
- 没有使用学校账号或真实 API Key，因此各校登录后导入、真实识别请求及真机后台运行尚未验证。目录条目数量不代表每所学校都经过登录实测。
- ArkUI 原生输入、下拉框、文件选择器等系统控件与 Android 系统控件存在平台差异；目前没有逐像素一致或所有设备恒定 60 FPS 的测量结论。

详细记录见 [完整移植验收记录](docs/full-port-20260930/README.md)。早期 [UI 对齐记录](docs/ui-alignment-20260930/README.md) 保留作为历史记录。

## 打开和构建

DevEco Studio 打开 **本目录 `harmonyos`**，构建 `entry` 模块，选中本地手机模拟器运行。当前产物：

`entry/build/default/outputs/default/entry-default-unsigned.hap`

本机命令（从本目录运行）：

```powershell
$env:DEVECO_SDK_HOME='D:/devco/DevEco Studio/sdk'
& 'D:/devco/DevEco Studio/tools/node/node.exe' 'D:/devco/DevEco Studio/tools/hvigor/bin/hvigorw.js' assembleHap --mode module -p module=entry -p product=default --no-daemon --no-incremental
node tests/FullPort.cjs
node --test tests/ScheduleCore.test.ts
```

模拟器已允许安装上述未签名 HAP；真机调试和发布需配置签名。核心校验脚本使用 DevEco 随附 TypeScript，其他安装路径可通过 `HARMONY_TYPESCRIPT` 指定。
