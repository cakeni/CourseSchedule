# 晴课表 · HarmonyOS NEXT

这是以 Android `1.0.13` 数据格式为基线的原生 ArkTS/ArkUI 工程，位于独立的 `harmonyos/` 目录。原生预览版本为 `0.1.0`，采用 Stage 模型，最低按 HarmonyOS `5.0.5(17)` 配置。

## 当前可用流程

- 查看 1–20 周课表，切换周次和显示周末。
- 手动添加、编辑、删除课程；支持单双周、跨节次、地点、教师、备注和颜色，保存时检查时间冲突。
- 修改学期名称、开学日期和总周数；课程和设置保存在设备本地 Preferences 中。
- 通过系统文件选择器导入 Android 版导出的 v2 JSON 完整备份，预览学期和课程数量后确认替换；导出同格式 JSON。

## 与 Android 版的差异

教务网页登录/学校目录、`.xlsx`/CSV/HTML/文本导入、提醒通知、完整设置选项、课程块拖拽操作和动画尚未移植。JSON 备份中的这些字段会保留，但提醒不会在鸿蒙版触发。当前是原生版本的首个可审查实现，不能作为与 Android 功能完全相同的发布包。

## 构建

用 DevEco Studio 打开本目录，配置调试签名，再构建 `entry` 模块。本机已用 DevEco Studio 26.0.0 和随附的 HarmonyOS SDK 26 成功编译，产物是 `entry/build/default/outputs/default/entry-default-unsigned.hap`。它尚未签名，也未在真机或模拟器上运行；文件选择器、持久化和课表布局仍需设备验证。

纯规则测试：

```powershell
node --test .\tests\ScheduleCore.test.ts
```

工程配置参考了[华为官方应用示例](https://gitee.com/harmonyos_samples/media-provider)，ArkTS/ArkUI 与系统文件选择器用法参考[华为开发者文档](https://developer.huawei.com/consumer/cn/app/planning)和[DocumentViewPicker API](https://developer.huawei.com/consumer/en/doc/harmonyos-references/js-apis-file-picker)。
