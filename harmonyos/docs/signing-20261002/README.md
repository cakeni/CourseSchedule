# 鸿蒙签名准备

当前已加入可选本地签名接入和“必须签名”的构建检查。已完成开发者认证、APP ID、发布证书和发布 Profile 配置，并通过 Windows 本机脚本生成发布签名 HAP；官方签名校验通过。代理提醒服务申请已提交，仍在审核中；没有连接鸿蒙真机，未验证真机安装或后台提醒送达。现有未签名模拟器构建可继续用于界面调试。

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

## Windows 发布签名

没有鸿蒙手机也可以申请普通发布证书和发布 Profile，再用本机私钥签名。当前发布 Profile 的分发类型为 `app_gallery`，对应应用市场发布；生成签名包不代表已经上架或通过真机安装验证。

仓库提供 `scripts/sign-release.ps1` 和 `scripts/ReleaseSigner.java`，使用已安装的 JDK 17+ 与 DevEco 官方 `hap-sign-tool.jar` 的签名 API。密码通过 Windows DPAPI 加密保存在仓库外，运行时仅通过子进程标准输入传给签名工具；不进入命令行参数、JSON 配置或工具日志。现有 DevEco 加密密码配置入口保持兼容。

1. 复制 `release-signing.example.json` 到仓库外，填写 `.p12`、发布 `.cer`、发布 `.p7b`、密码文件、别名、JDK 和官方签名工具路径。相对路径以该 JSON 所在目录为基准；也支持绝对路径。
2. 如需为已有私钥保存密码，在创建该私钥的 Windows 用户下执行 `Read-Host '发布密钥库密码' -AsSecureString | ConvertFrom-SecureString | Set-Content -Encoding utf8 'D:/private/course-schedule-signing/release-password.dpapi'`。当前本机已生成并保存密码，无需再次创建密钥或 CSR。
3. 完成下方 Release 构建后，从 `harmonyos` 目录执行：

```powershell
pwsh -File scripts/sign-release.ps1 -SigningConfig 'D:/private/course-schedule-signing/release-signing.json' -UnsignedHap 'entry/build/default/outputs/default/entry-default-unsigned.hap' -OutputHap 'entry/build/default/outputs/default/entry-default-signed.hap'
```

脚本在读取密码前校验华为 Profile 的签名、发布类型及包名，并核对 HAP 包名；签名时保留 Profile 和代码签名校验，随后自动执行官方 `verify-app`。签名先写入临时文件，校验成功后才替换目标文件，失败时保留此前的签名包并清理临时文件。验证材料保存在输出目录的 `signing-verification` 子目录。DPAPI 文件只允许创建它的 Windows 用户解密；私钥和密码仍需自行备份保管。

当前代理提醒尚未获批，Profile 的 ACL 列表为空。能力审批通过并开启后，需要重新生成 Profile，再运行签名脚本；当前签名成功不代表后台代理提醒可用。

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

包名修正后，2026-10-02 再次完成无签名 Release 构建和 `node tests/FullPort.cjs` 核心检查；直接读取生成 HAP 的 `module.json`，确认包名为 `com.courseschedule.qingke`、版本为 `0.2.0`。编译仍有原有 ArkTS 警告。本次没有重新安装模拟器或验证提醒送达。

实际发布签名已完成以下验证：

- 发布私钥与华为证书的公钥一致；Profile 包名及发布证书匹配，官方 `verify-profile` 通过。
- 新包名 Release HAP 已签名，官方 `verify-app` 通过；包内三层证书链及 Profile 与源文件匹配。
- 本机脚本拒绝包名不匹配的 Profile、损坏的 Profile 和错误密码；错误密码没有进入输出，失败后保留既有签名包并清理临时文件。HAP ZIP 内未包含私钥、DPAPI 文件或本机配置。
- 当前签名 HAP 为 3,737,098 字节，SHA-256：`418b6facf4b71fe10635649f8b9527534b94fcb33112105675c7ec7b8db6db77`。

仅脚本、示例和说明同步到 GitHub；签名材料与 HAP 保留在本机。无鸿蒙真机安装验证，代理提醒仍需在服务审批通过后更新 Profile 并复验。
