# 学期切换后的周次状态修复

## 复现与原因

使用现有两个学期切换后，查看“形势与政策”课程详情，记录的上课范围为第 6–8 周、每周。

旧版实测：

1. 从第 5 周进入第 6 周，该课程仍显示“非本周”。
2. 进入第 9 周，让已结束的课程退出课表；再从第 8 周返回第 5 周，该课程错误地保持本周状态。

课表的 `ForEach` 原来仅以课程 ID 为键。同一课程在周次切换中继续显示时，节点被保留，而 `courseCard` 的普通 Builder 参数 `active` 保留初次生成的值。是否先经过有课或无课的周次，影响后续显示。

这符合 [OpenHarmony ForEach 的组件复用规则](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/ui/rendering-control/arkts-rendering-control-foreach.md)：保留相同键时复用已有组件。

## 修复

课程渲染键包含当前学期 ID、完整课程数据和当前周的上课状态。状态或课程内容改变时刷新对应卡片；上课状态相同的相邻周继续复用。此处为手动导入、教务系统导入和 AI 识别导入共同使用的课表渲染路径。

保存的课程、上课周次和 Key 无需重置或重新导入。

## 验证

- DevEco Studio 26 `assembleHap` 成功，修复 HAP 已覆盖安装至 HarmonyOS 7 / API 26 模拟器。
- 使用两个现有学期分别检查第 1–20 周正向、第 19–1 周反向，共 78 次检查。两门对应课程均只在第 6、7、8 周显示本周状态；之前显示非本周，结束后退出课表。全部通过，包含真实左右滑动及学期切换。
- `node tests/FullPort.cjs` 与 `node tests/AiRecognition.cjs` 均通过。

可运行的模拟器回归检查：

```powershell
cd harmonyos
python docs/semester-week-20261001/check_week_ui.py first 15 6 8
```

参数依次为记录名、课程 ID、开始周、结束周；可使用 `--week-type 1` / `2` 检查单双周。测试前选择对应学期并打开课表，启用显示周末与非本周课程。目标课程应在首屏且独占时间段；课程 ID 和周次须与测试数据一致。

脚本切换周次并比较卡片状态，记录写入已被 Git 忽略的 `entry/build/validation/semester-week-20261001/`，控制台仅输出校验数量和结果。
