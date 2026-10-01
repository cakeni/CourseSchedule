# 课程助手

在“导入课程”页选择“AI 对话导入”，或从课表页菜单打开“课程助手”。填写服务商提供的 HTTPS API 地址、支持 Chat Completions 的模型名称和 API Key。地址支持基础路径或完整的 `/chat/completions` 地址；密钥默认仅在当前页面使用，也可选择在本机加密保存并随时清除。

## 示例

```text
帮我添加高等数学：周三第 1–2 节，第 1–16 周，每周上课，教室 A101。
周三有哪些课？
把周三的高等数学教室改成 B201，提前 15 分钟提醒。
删除周三的高等数学。
撤销刚才的操作。
```

支持按实际节次或具体钟点描述课程。信息不足、目标不明确时会追问；重复、时间冲突或课程已在其他页面修改时整批拒绝。新增课程通过本地校验后保存，修改和删除须先确认具体方案。实际执行结果与课程修改在同一个数据库事务中保存，撤销会检查后续更改。

## 聊天记录

右上角历史记录可切换本学期的对话，也可新建或删除对话。聊天、草稿、待确认方案和本对话最近一次成功操作的撤销记录在本地保留，重启后可恢复。长对话可加载更早消息。删除聊天不会删除课程；删除学期会删除对应聊天。

请求失败可以重试，正在生成时可以停止。重启后不会自动重发请求或执行待确认方案，失效方案会取消。早期版本未保存的聊天无法补回。

每次发送会将近期对话与当前学期课程信息直接提交给所配置的 API，完整数据范围见 [隐私说明](PRIVACY.md)。课程 JSON 备份不包含聊天，Android 系统备份可能包含本地数据库与偏好设置；加密保存的密钥不参与系统备份或迁移。

## 升级兼容

1.0.18 使用数据库版本 3，支持原数据库版本 1、主分支的版本 2，以及已分发的课程助手版本 2。升级会保留学期、课程、聊天及操作状态，不清空数据库。主分支版本 2 中用户自行修改的课程颜色不会再次归一化；早期课程助手版本 2 补齐尚未执行的导入课程颜色迁移。

## 流程验证

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
```

模拟器测试覆盖数据库升级、首次启动、加课/查询/修改/删除/撤销、历史切换、草稿、重试、停止、分页、冲突及过期方案。重启测试必须分两阶段执行，在两阶段之间强制停止应用；也可在此期间覆盖安装新版 APK 验证真实升级：

```text
adb shell am instrument -w -r -e class com.courseschedule.ui.assistant.AssistantProcessRestartTest#prepareBeforeProcessKill com.courseschedule.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.courseschedule
adb shell am instrument -w -r -e class com.courseschedule.ui.assistant.AssistantProcessRestartTest#verifyAfterProcessKill com.courseschedule.test/androidx.test.runner.AndroidJUnitRunner
```

这些测试使用虚构课程与本地模拟响应，无须真实 API Key。

<p>
  <img src="screenshots/course-conversations-20261002/pending-light.png" width="300" alt="课程助手恢复待确认课程方案">
  <img src="screenshots/course-conversations-20261002/history-dark.png" width="300" alt="课程助手深色模式聊天历史">
</p>

截图来自模拟器，课程信息为虚构示例。
