# 项目公告卡片按压反馈

参照 Android 1.0.13 原版 APK 的浅色、深色按压录屏，以及 `activity_settings.xml`、`ViewMotion.kt` 和 Android 的 `RippleShader` / `RippleAnimationSession` 实现。

鸿蒙 `ProjectNotice.ets` 使用柔边径向高亮、扩散边缘颗粒和卡片缩放。按下从触点扩散并向卡片中心过渡，450 ms 展开；松手后按原版的最短展开时长等待，再用 375 ms 消退。缩放仍为按下 0.98 / 115 ms、释放 1 / 245 ms。

高亮按卡片实际尺寸绘制，裁剪在 20 vp 圆角内。原有点击打开项目和长按复制继续使用 `Index.ets` 的回调。离开设置页会取消动画和待执行的释放回调。

## 验证

- `assembleHap` 编译成功，已安装到本地 HarmonyOS 模拟器。
- 原版 APK 的浅色、深色按压效果已实际运行并录屏查看。
- 鸿蒙按住时高亮可见，松手后卡片截图与按下前一致。
- 移出卡片释放、滚动手势取消后，高亮与缩放均复原。
- 长按后系统界面出现“项目地址已复制”。
- 没有新增运行崩溃记录。

本次录屏、截图、比较结果及编译日志保存在本地 `entry/build/validation/project-press-20261001/`。临时安卓模拟器已经关闭。

## 颗粒资源

在 `harmonyos` 目录运行：

```powershell
python docs/project-press-20261001/prepare_grain.py
```

该脚本仅使用 Python 标准库，生成 `press_grain.png`。颗粒采用固定采样，扩散遮罩逐帧变化；安卓着色器中每个颗粒随噪声相位变化的细节尚未逐像素复现。
