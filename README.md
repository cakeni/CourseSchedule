# CourseSchedule 课程表

[![Android](https://img.shields.io/badge/Android-8.0%2B-176B5B)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.10-7F52FF)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-D29436)](LICENSE)
[![Android CI](https://github.com/cakeni/CourseSchedule/actions/workflows/android-ci.yml/badge.svg)](https://github.com/cakeni/CourseSchedule/actions/workflows/android-ci.yml)

一款专注于日常查看、导入和管理课程的 Android 应用。课程数据保存在本机，不依赖账号或自建服务器。

<p align="center">
  <img src="docs/screenshots/schedule-week.png" width="360" alt="CourseSchedule 周课表界面">
</p>

## 功能

- 左右跟手切换周次，每周保留独立的纵向滚动位置。
- 周视图展示课程名称、教师和完整地点，支持隐藏周末与调整课程块高度。
- 手动添加、编辑和删除课程，支持单双周、课程颜色、备注和冲突检测。
- 导入 `.xlsx`、JSON、CSV、HTML 和纯文本，导入前检查无效项、重复项与时间冲突。
- 支持导入覆盖后的撤销，以及包含学期和显示设置的 JSON 完整备份。
- 管理学期起始日期、总周数和当前学期。
- 自定义每节课开始时间，并通过系统通知设置课前提醒。

## 环境要求

- Android 8.0（API 26）或更高版本
- JDK 17
- Android SDK 34
- Android Studio 或命令行 Gradle

## 构建

克隆仓库后，在 Android Studio 中打开项目根目录并等待 Gradle 同步：

```bash
git clone https://github.com/cakeni/CourseSchedule.git
cd CourseSchedule
```

也可以使用命令行完成检查和构建：

```bash
# macOS / Linux
./gradlew lintDebug testDebugUnitTest assembleDebug
```

```powershell
# Windows PowerShell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 只保存本机 Android SDK 路径，不应提交到版本库。首次使用 Android Studio 打开项目时会自动生成。

## 导入课程

应用支持结构化课程表和常见的周课表网格。推荐的字段包括：

```text
课程名称,教师,教室,星期,开始节次,结束节次,开始周,结束周,单双周
```

导入前会显示课程数量、冲突、重复、无效数据，以及地点和教师字段的完整度。

- 旧版 `.xls` 暂不支持，请先在 Excel 中另存为 `.xlsx`。
- 应用只能导入源文件中实际存在的信息。若学校导出的文件没有教师姓名，导入预览会显示教师完整度为 `0/N`，可在课程详情中手动补充。

更完整的格式示例见 [导入说明](docs/IMPORTING.md)。

## 技术结构

- Kotlin + Android View System
- MVVM、ViewModel、LiveData 与 Coroutines
- Room 本地数据库
- Material Components
- ViewPager2 周分页与自定义 `CourseTableView`
- 基于 SAX/ZIP 的轻量 `.xlsx` 解析，不需要存储权限

```text
app/src/main/java/com/courseschedule/
|-- data/          Room 实体、DAO、仓库和备份模型
|-- domain/        排课规则与提醒时间计算
|-- ui/            主界面、课程编辑、导入和设置
|-- utils/         偏好设置、提醒与开机恢复
|-- view/          自定义课程表视图
`-- viewmodel/     页面状态与数据协调
```

## 隐私与权限

应用没有声明网络权限，也没有统计或广告 SDK。课程数据存储在本机 Room 数据库中；Android 系统备份可能按设备设置备份应用数据。权限用途和数据边界见 [隐私说明](docs/PRIVACY.md)。

## 参与贡献

提交问题前请先搜索现有 Issue。代码贡献流程、分支命名和检查命令见 [CONTRIBUTING.md](CONTRIBUTING.md)。参与本项目即表示同意遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 许可证

本项目使用 [MIT License](LICENSE)。
