# DeepSeek 默认配置

2026-10-02：课程助手的默认地址改为 `https://api.deepseek.com`，默认模型改为此前实测通过的 `deepseek-flash`，JSON 格式约束默认开启。配置尚未完成时也显示当前模型名称，配置窗口预填对应地址和模型。

已有保存的自定义配置仍按原值读取；清除配置后使用新的默认值。API Key 由用户在配置窗口填写。

验证：162个单元测试通过；lint无错误；Debug、测试APK及Release构建成功。在独立测试模拟器上，从空配置打开页面并核对预填值，配置界面与加密保存流程两个测试通过。截图：[默认配置](config.png)。

构建命令：`gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`。

测试包 SHA-256：`b23b13b67e6f91db749a4cc7ededdb96e6f0090df0bc37ffd2afb4141e2f0926`。

配置参考：[DeepSeek JSON 模式文档](https://api-docs.deepseek.com/guides/json_mode/)。
