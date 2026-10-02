# 鸿蒙签名准备

当前已加入可选本地签名接入和“必须签名”的构建检查。已完成开发者认证，发布证书已在 AGC 生效；应用 APP ID、发布 Profile 和本机签名配置仍在办理中，没有连接鸿蒙真机，因此当前交付 HAP 仍为未签名包；未进行真机安装或发布签名验证。模拟器可以运行现有未签名 HAP。

## 应用标识

AGC 注册时使用应用名称“晴课表”、应用类型“HarmonyOS应用”和包名 `com.courseschedule.qingke`。2026-10-02 注册页面拒绝了旧包名中的保留字，因此工程包名、课程提醒跳转和模拟器验证脚本已同步修改。APP ID、发布 Profile 和构建包中的包名必须一致；此前生成的发布私钥和 CSR 可以继续使用。

更换包名后，系统会将其视为新的应用，旧模拟器应用中的本地数据不会自动迁移；需要保留的数据应先通过课表备份导出。

## 有设备后生成调试签名

1. DevEco Studio 打开仓库的 `harmonyos` 目录。登录华为开发者账号；按平台要求完成开发者认证。
2. 鸿蒙手机开启开发者模式和 USB 调试，连接电脑，在手机上允许调试。
3. `File > Project Structure > Project > Signing Configs` 中使用 `Automatically generate signature`，应用包名为 `com.courseschedule.qingke`。DevEco 申请调试证书和包含目标设备的 Profile。
4. 将 DevEco 在工程级 `build-profile.json5` 中生成的一个完整 `signingConfigs` 条目复制到本机 `signing.local.json`；仅复制该对象。`storePassword` 和 `keyPassword` 保留 DevEco 的加密值。然后从跟踪的 `build-profile.json5` 移除签名条目和产品的 `signingConfig`，保留仓库原有构建配置。

可先复制 `signing.example.json` 为 `signing.local.json`。填入实际 `.p12`、`.cer`、`.p7b` 路径、别名和加密密码；示例中的占位内容不能用于签名。文件路径相对本地配置文件解析，也接受绝对路径。

推荐将凭证全部保存在仓库外，通过 `HARMONY_SIGNING_CONFIG` 指向完整 JSON 配置。仓库内的 `signing.local.json` 和 `signing/` 文件夹已忽略，`.p12`、`.p7b`、`.cer`、`.csr` 也被忽略；不得将自动生成的签名密码放入跟踪的构建配置。

官方操作参考：[自动签名](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-signing-auto)、[手动签名](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-signing-manual)。调试证书和 Profile 用于指定设备调试；发布上架需另外按华为流程申请发布证书及 Profile。

## 构建

从 `harmonyos` 目录运行：

```powershell
$env:DEVECO_SDK_HOME = 'D:/devco/DevEco Studio/sdk'
$env:HARMONY_SIGNING_CONFIG = 'D:/private/course-schedule-signing/config.json'
$env:HARMONY_REQUIRE_SIGNING = '1'
& 'D:/devco/DevEco Studio/tools/node/node.exe' 'D:/devco/DevEco Studio/tools/hvigor/bin/hvigorw.js' assembleHap --mode module -p module=entry -p product=default --no-daemon --no-incremental
```

本地配置只在 Hvigor 构建上下文中合入，不会改写 `build-profile.json5`。指定配置不存在、材料缺失或要求签名却未配置签名时，构建立即失败，避免把未签名包误当作已签名交付物。实际证书链、Profile 与包名/设备匹配仍由官方签名工具和设备安装校验。

成功签名后检查 `entry/build/default/outputs/default/entry-default-signed.hap`，再连接目标设备安装验证。没有签名材料时，清除签名环境变量即可继续使用模拟器：

```powershell
Remove-Item Env:HARMONY_SIGNING_CONFIG -ErrorAction SilentlyContinue
Remove-Item Env:HARMONY_REQUIRE_SIGNING -ErrorAction SilentlyContinue
```

## 本轮检查

`node --test tests/SigningConfig.cjs` 的 6 项检查通过，覆盖模拟器无签名构建、要求签名时失败、外部配置路径解析、保留原构建文件、选定产品、错误脱敏和拒绝明文密码。实际 Hvigor 无签名 Debug 构建通过；设置 `HARMONY_REQUIRE_SIGNING=1` 后，在任务执行前按预期失败，没有生成新的未签名交付物。这些配置检查不代表实际签名成功。

包名修正后，2026-10-02 再次完成无签名 Release 构建和 `node tests/FullPort.cjs` 核心检查；直接读取生成 HAP 的 `module.json`，确认包名为 `com.courseschedule.qingke`、版本为 `0.2.0`。编译仍有原有 ArkTS 警告。本次没有重新安装模拟器或验证提醒送达，实际发布签名仍待 Profile 和本机配置完成。
