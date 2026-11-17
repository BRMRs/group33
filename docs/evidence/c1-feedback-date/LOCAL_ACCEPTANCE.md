# C1 日期输入：本地人工试用记录

用户反馈“可以了我测试好像没啥问题”。记录为虚构数据场景的总体试用反馈，用户未逐项确认，不能补写逐项通过或正式审批。生产日期来源仍未接通。

## 版本和范围

- 开发基线：main `61140712193b816980045c52cc7276223731c268`。
- 组合基线：#124 `a377a7df0731d48fc052514b61d37955e014f06d` 加本目录 `124-date-consumer.patch`。
- 设备：Mi Pad 4 Plus / API27；独立 applicationId `com.group33.medicationreminder.c1review124`。
- 临时 instrumentation 控制提供虚构日期、会话失效及显式重新进入；未加入四文件补丁。不能仅应用补丁就声称复现了此次人工入口。
- 场景使用空事件列表，控制按钮可能遮住部分头部；不作为长列表、无遮挡布局、读屏、真实换账号或换长者验收。

## 试用记录

当时安装对应 app/test APK 后，运行以下命令。人工窗口现已结束，重新执行前须恢复并核对临时 fixture 及对应 APK；普通自动测试不传 `c1Manual`。

```powershell
adb -s <tablet> shell am instrument -w -r -e c1Manual true -e class com.group33.medicationreminder.integration.CaregiverFeedbackEntryTest#explicitDatesRouteFullPagesAndPreservePairingAndPlanSections com.group33.medicationreminder.c1review124.test/androidx.test.runner.AndroidJUnitRunner
```

| 操作 | 预期 | 人工逐项结果 |
|---|---|---|
| 有效日期 2026-10-30 | 显示 2026-10-01 至 2026-10-30，共 30 个日期 | 未单独确认 |
| 日期缺失 | 清除完整历史，保留既有简面板 | 未单独确认 |
| 新会话保留旧日期 | 拒绝旧会话日期 | 未单独确认 |
| 给新会话提供日期 | 显示 2026-10-18 至 2026-11-16 | 未单独确认 |
| 退出，再恢复新会话和日期 | 退出时清理旧内容；恢复后使用新上下文 | 未单独确认 |
| 切换计划、记录、设置及语言 | 导航可达，语言行为保留 | 未单独确认 |

原始人工模式日志记录正常结束，耗时 751.543 秒，`OK (1 test)`。这证明前置断言及等待/结束流程完成，不证明用户点击了所有控制项。原始日志、临时 fixture 和安装包仅保留在本地恢复资料中，未纳入本交付。

## 剩余验收

日期来源/时区及生命周期决策、C1/C3 宿主接线审查、滚动问题收口、适用人工验收及支持环境中的完整检查仍待完成。同条件原版/组合版对照已补充，详见 [#124 对照记录](124_BASELINE_COMPARISON.md)；它不代替本次人工试用。组合设备失败和 Windows Server 失败详见 [交接说明](../../contracts/C1_FEEDBACK_DATE_INPUT.md)，不得记为通过。本交付仅引用 #15/#19，不关闭整票；人工审批由成员本人完成。
