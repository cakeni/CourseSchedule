# AI 网页识别与 Key 保存修复

## 用户反馈

鸿蒙版使用 DeepSeek 提示“AI 未返回可解析的课表”；每次打开识别窗口都需要重填 Key。原实现会在打开、关闭弹窗和识别时清空 Key，仅保存在内存中。

## 修改

- 提示词明确要求纯 JSON、课程字段及结构示例，没有课程证据时返回空列表。
- 合并 Responses API 的多段 `output_text`；兼容完整 JSON 代码块和聚合 `output_text`；忽略推理文本，继续校验课程范围。
- 区分空返回、非 JSON、缺失课程列表、截断和服务失败，避免统一显示不可解析。DeepSeek 的空返回或格式错误仅重试一次，第二次使用 JSON object 输出模式。认证失败和网络错误不自动重试。
- 使用鸿蒙 `AssetStoreKit` 保存 Key；设备解锁时可访问，禁止资产同步，按服务商隔离。普通 Preferences 仅存服务商名称，课表导出不包含 Key。新增保存及清除操作，识别前也自动保存。
- 诊断只记录 HTTP 状态、尝试次数、响应结构和文字长度，排除凭据、课程文本和原始响应。

DeepSeek 的端点、模型及 `reasoning.effort: none` 均按[官方 Responses API](https://api-docs.deepseek.com/api/create-response/)配置；输出示例与 JSON 要求参考[官方 JSON 输出指南](https://api-docs.deepseek.com/guides/json_mode/)。

## 验证

```powershell
node tests/AiRecognition.cjs
node tests/FullPort.cjs
```

本次专项检查覆盖多段文本、JSON 代码块、聚合输出、截断、空列表和无效课程；模拟 HTTP 检查 DeepSeek 格式重试、认证失败不重试及对象释放；凭据检查覆盖服务商隔离、覆盖、删除和普通 Preferences 不包含 Key。

DevEco Studio 26 编译成功，修复版安装至 HarmonyOS 7 / API 26 模拟器。真实 DeepSeek 请求仍需使用者输入自己的 Key 后复验；原失败响应没有被记录，不能仅凭旧提示断定是哪一种返回格式。

模拟器使用测试 Key 实测：保存成功；结束进程并重启后填回；切换服务商不串 Key；关闭并重新打开后恢复最后保存的服务商；清除后重新打开为空。全部通过，测试 Key 已清除。输入框使用密码模式，自动化只比较掩码长度及公开状态，没有打印 Key。
