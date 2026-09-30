# 晴课表 · HarmonyOS NEXT

这是以 Android `1.0.13` 数据格式为基线的原生 ArkTS/ArkUI 工程，位于独立的 `harmonyos/` 目录。原生预览版本为 `0.1.0`，采用 Stage 模型，最低按 HarmonyOS `5.0.5(17)` 配置。

## 当前可用流程

- 按 Android 版布局查看整周课表，支持周次按钮、左右滑动、进度条拖动和回到当前周；可显示 / 隐藏周末、节次时间和非本周课程。
- 沿用 Android 浅色 / 深色配色、16 色课程卡片、日期标题和“课表 / 导入课程 / 设置”底部导航；支持深色模式和课程块高度设置。
- 手动添加、编辑、删除课程；支持单双周、跨节次、地点、教师、备注和颜色，保存时检查时间冲突。
- 修改学期名称、开学日期和总周数；课程和设置保存在设备本地 Preferences 中。
- 通过系统文件选择器导入 Android 版导出的 v2 JSON 完整备份，预览学期和课程数量后确认替换；导出同格式 JSON。

## 与 Android 版的差异

教务网页登录/学校目录、`.xlsx`/CSV/HTML/文本导入、提醒通知、完整设置选项、课程块拖拽操作和原版动画尚未移植。顶部吉祥物使用原 Rive 资源渲染的透明图片；原生输入控件和课程详情对话框仍采用 HarmonyOS 控件。JSON 备份中的提醒字段会保留，但提醒不会在鸿蒙版触发。当前仍是原生预览版本。

界面截图、验证范围和差异见 [UI 对齐记录](docs/ui-alignment-20260930/README.md)。

## 构建

用 DevEco Studio 打开本目录，构建 `entry` 模块。本机已用 DevEco Studio 26.0.0 和随附的 HarmonyOS SDK 26 成功编译，产物是 `entry/build/default/outputs/default/entry-default-unsigned.hap`。已安装到本机 HarmonyOS 7.0.0 (API 26) 手机模拟器，验证课程增改删、重启持久化、周次切换、周末显示及深色模式。这个本地模拟器允许安装未签名 HAP；真机调试 / 发布仍需签名。文件选择器实际导入导出及真机运行尚未验证。

纯规则测试：

```powershell
node --test .\tests\ScheduleCore.test.ts
```

工程配置参考了[华为官方应用示例](https://gitee.com/harmonyos_samples/media-provider)，ArkTS/ArkUI 与系统文件选择器用法参考[华为开发者文档](https://developer.huawei.com/consumer/cn/app/planning)和[DocumentViewPicker API](https://developer.huawei.com/consumer/en/doc/harmonyos-references/js-apis-file-picker)。
