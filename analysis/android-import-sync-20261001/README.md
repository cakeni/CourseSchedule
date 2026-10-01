# Android AI 网页导入同步

## 功能

- 合并 Responses 返回的全部 `output_text`，兼容顶层文本和完整 JSON 代码块；按实际周次生成课程。
- DeepSeek 首次返回为空或格式不正确时，改用 `json_object` 重试一次。鉴权失败、网络错误、内容截断和没有有效课程时不自动重试。
- 分别提示空结果、格式错误、内容截断、拒绝识别、无有效课程和 HTTP 错误；诊断仅记录返回结构和数量，不记录 Key 或 AI 返回正文。
- DeepSeek / OpenAI Key 分别保存、回填和清除；点击识别前自动保存，也可单独点击保存。保存成功后记住服务商。
- 使用 Android Keystore AES-GCM 加密 Key，并将密文写入 `noBackupFilesDir`。普通偏好只记录服务商名称；课表备份模型不包含凭据。分享安装包不会包含安装后输入的 Key。
- 导入弹窗可滚动，适配键盘和较小屏幕；保存、加载和清除期间禁用重复操作。

## 验证

- `testDebugUnitTest`：137 项测试通过，包括多段文本、代码块、周次、非法课程行、DeepSeek 重试上限，以及 OpenAI、鉴权失败和网络错误不重试。
- `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug`：通过。
- Android 15 / API 35 模拟器通过 AndroidJUnitRunner 运行 2 项测试，结果为 `OK (2 tests)`：使用测试凭据验证加密存储、服务商隔离、覆盖保存、清除、弹窗重新打开和 Activity 重建后的回填。
- 对生成的 APK 检查凭据文件和 Key 特征，均未发现；课表备份字段不包含凭据。
- 测试不使用真实 API Key，不发起付费模型请求。真实教务页面的识别效果仍需使用者登录后核对。

## 平台资料

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android Auto Backup：noBackupFilesDir 排除规则](https://developer.android.com/identity/data/autobackup)
- [DeepSeek Responses：结构化输出格式](https://api-docs.deepseek.com/api/create-response/)

构建和设备验证日志位于本机忽略目录 `app/build/validation/android-import-sync-20261001/`。
