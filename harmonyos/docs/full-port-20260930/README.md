# Android 1.0.13 → HarmonyOS NEXT 移植验收记录

验证日期：2026-09-30 至 2026-10-01。环境：Windows、DevEco Studio 26、HarmonyOS SDK 26；本地手机模拟器 HarmonyOS 7.0.0.107 / API 26。应用兼容配置 API 17。分支 `feat/harmonyos-native`。

## 功能与证据

| 项目 | 实现和验证结果 |
| --- | --- |
| 周课表 | 布局、周末、节次、非本周筛选、切周、进度拖动、滚动位置、课程详情；核心规则和模拟器流程通过 |
| 快速添加 | 长按第 5 节预填 5–5，拖动第 5–7 节预填 5–7 和当前周；模拟器通过 |
| 课程表单 | 原版字段、单双周、16 色、备注、提前量、冲突检查；名称修改/保存、单周、备注、5 分钟提醒、详情回显及删除通过 |
| 多学期 | 创建验证学期、切回当前学期、删除验证学期；本地存储往返和跨学期 ID 校验通过 |
| 节次设置 | 12 节时间编辑、校验、恢复默认；模拟器输入 25:00 被拒绝，错误完整显示，恢复默认并保存通过 |
| 文件导入/导出 | 系统 DocumentPicker 导出备份到 Documents，重选该文件，预览、替换、恢复元数据、确认与撤销通过 |
| XLSX | 模拟器真实 fileIo、原生 zlib、CRC 校验、XML 解析；DEFLATED/STORED 两种 ZIP 条目通过 |
| 教务网页 | 模拟器原生 ArkWeb 运行采集脚本，经字符串解包、来源检查、课程解析通过；南京航空航天大学登录页及预设认证重定向通过 |
| 学校目录 | 3566 条目录的空网址搜索校验通过；模拟器拼音 `nanjinghangkong` 搜索得到三条对应结果 |
| 教务解析 | 原版 12 种报表样例，正方/强智/EAMS 样例，非连续周次、单双周、多个授课安排及不完整字段拒绝通过；这不是各校真实登录验收 |
| AI 网页识别 | 原生提供商/密码 Key/隐私提示/取消界面通过；请求格式和响应模拟样例通过；没有发送真实 API 请求 |
| 提醒 | 提前量、自然日、单双周、补发和学期终止规则通过；原生发布返回 1700002，送达未通过 |
| 动画 | 原 Rive 确定性 60 Hz 导出；导航旋转/翻转/跳跃，导入 540ms+50ms 错峰，设置 620ms+56ms 错峰，日期 340ms 数字滚动，按压 115/245ms，详情 480ms 过渡；构建和页面运行通过，未形成持续 FPS 测量报告 |

临时测试课程和测试学期已删除。测试导出的 `Documents/晴课表备份.json` 是验收产生的文件，保留在模拟器中，可自行删除；它包含测试课程，不应作为真实数据恢复来源。

## 动画校准

初次稀疏采样导致低帧率。加密采样时又发现 Rive 的自动播放/画布刷新不能仅靠 `stopRendering()` 控制：自动时钟可能继续推进，关闭时钟后还必须手动刷新渲染队列。最终流程为：关闭 `startRendering`、重置状态机、固定推进 1/60 秒、调用 `resolveAnimationFrame`、检查每次仅绘制一帧。

已比较 900 帧样本，第 300 帧与第 0 帧对应的姿态重复，确认约 5 秒待机循环；应用最终只携带首个循环的 300 帧。播放时长固定 5000ms，原生播放器负责帧切换。资源采样率与设备实际呈现 FPS 是不同指标。

## 复现校验

从 `harmonyos` 运行：

```powershell
node tests/FullPort.cjs
node --test tests/ScheduleCore.test.ts
```

`prepare_resources.py` 从 Android 源码生成学校目录、适配器配置、网页采集脚本及 SVG；`prepare_fixtures.py` 抽取原版报表测试样例。

平台校验：`python docs/full-port-20260930/native_checks.py prepare` 临时安装验证页面，生成 XLSX 样例及 `entry/build/validation/web-fixture/index.html`。将该目录用本地 HTTP 服务发布到 18766，并用 HDC 反向端口转发提供给模拟器；构建、安装后查看 [平台结果](native-platform-check.png)。完成后运行同一脚本的 `restore`，再构建和安装生产页面。验证页不进入最终 HAP。

动画资源重导：使用 Rive Canvas runtime 2.32.0 的 JS/WASM，通过 `prepare_duck_renderer.py --runtime-js <JS路径> --wasm <WASM路径>` 准备本地渲染页；用 Playwright 打开本地页面，等待 `window.loaded`，执行 `render_duck.js`。应用构建不依赖该浏览器运行时。

## 界面记录

- [课表与课程](schedule-imported.png)
- [快速添加](quick-add-editor.png)
- [课程表单](course-form.png)
- [详情回显](course-details-updated.png)
- [学期对话框](semester-dialog.png)
- [导入预览](backup-preview.png)
- [网页识别入口](academic-ai-dialog.png)

截图包含验收时的临时样例，不能作为真实课表数据。系统字体、输入框、选择器与 Android 存在平台差异，仍需对照实际设备继续做视觉验收。
