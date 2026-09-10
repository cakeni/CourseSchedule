# 参与贡献

感谢你愿意改进 CourseSchedule。为了让问题定位和代码审查更高效，请遵循下面的流程。

## 提交问题

- Bug 报告请写明 Android 版本、应用版本、复现步骤、预期结果和实际结果。
- 导入问题请说明文件格式与表头结构，并先移除姓名、学号等隐私信息。
- 功能建议请描述实际使用场景，不要只给出组件或技术名称。
- 安全问题不要提交公开 Issue，请按照 [SECURITY.md](SECURITY.md) 报告。

## 本地开发

需要 JDK 17 和 Android SDK 34。克隆仓库后执行：

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Windows PowerShell 使用：

```powershell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

修改 WebView、导入流程或界面交互时，还应在已连接的模拟器或真机上执行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 分支与提交

- 从最新的 `main` 创建分支。
- 建议使用 `feat/简短说明`、`fix/简短说明` 或 `docs/简短说明`。
- 每个提交只处理一个清晰的问题，避免混入无关格式化或重构。
- 不要提交 `local.properties`、签名文件、真实课程表、个人截图或构建产物。
- APK 解包、JADX 输出和临时克隆应放在项目目录之外；`analysis/` 只保留可复现脚本、脱敏的小型证据和结论。

## 代码要求

- 保持现有 Kotlin、XML 和 MVVM 结构，不为小改动引入新的框架。
- 导入器必须限制输入大小，并使用结构化解析方式处理文件内容。
- 新增排课规则、导入格式或共享逻辑时应补充单元测试。
- UI 改动需检查常见手机尺寸，确保文字不重叠、课程块可点击且纵向滚动正常。

## Pull Request

PR 描述应包含：

1. 解决的问题和实现方式。
2. 行为变化及兼容性影响。
3. 已执行的测试命令。
4. UI 改动的截图或录屏。

维护者可能会要求拆分范围过大的 PR，或补充能够覆盖回归风险的测试。
