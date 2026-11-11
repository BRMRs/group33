# 照片与计划正式页面接入证据 — 2026-11-05

范围与复现见[宿主说明](../PHOTO_PLAN_FORMAL_HOST.md)。父 #110 冻结于 `4db15b775a85b143fa79cc6aeb29fc9d95f25800`；本分支验证不代表父 PR 已修复或获批。

## 原组件与构建

#55 `CaregiverPhotoViewModel.kt` 与原 head `4b04e645` 逐字节相同；#107 `PlanBrowserViewModel.kt` 与最新修复 head `1638f116` 逐字节相同。生产组件未复制或重写；已实际用于正式角色页面。

`gate1Check test :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --console=plain --no-daemon --max-workers=1` 通过。普通 XML 报告实例：Shared18、Server627、Android Debug131、Release131，失败/错误/跳过均为0。相对父集成新增 #107 的12项和宿主6项回归；重复变体不冒充不同业务场景。执行、缓存和 UP-TO-DATE 状态保留在本地日志。

一次增量并行 lint 遇到 Kotlin FIR 分析器内部异常，未屏蔽检查或改规则；新的单次 Gradle 进程串行执行完整 gate 后通过。失败日志保留，不计为成功或产品反例。

Release 的 manifest/resources/DEX 检查通过：仍为统一入口与普通 Application，匿名控件/模型/端点、Debug HTTP/cache/Application、INTERNET 与 cleartext 配置没有进入 Release。共享照片/计划组件在 main 中存在不等于 Release 已配置真实身份或业务 Provider。

## 实际 HTTP 双端与 UI

两台独立 API35 arm64 AOSP 模拟器，实际本地 Spring HTTP/领域服务，内存业务存储＋私有本地照片。生成照片、名称、剂量均为虚构。没有 SQL 手改、共享进程对象或假 HTTP 代替关键行为。

| 检查 | 结果 |
|---|---|
| 英文15阶段：登录/请求/同意/照片/原编辑器保存/进程恢复/长辈读计划/浏览/导航/丢回复恢复/旧401/503/撤销拒绝 | PASS；每次启动断言实际 Activity 语言 |
| 简中同样15阶段，照护端320dp宽、200%字号 | PASS；每次启动断言实际为中文 |
| 保存语言偏好→清除平台 app locale→force-stop→真正冷启动恢复 | PASS；英、中两轮均执行 |
| #55 实际内容选择与下载→有界解码→看图，未点击创建时不显示编辑器 | PASS |
| #107 实际计划/Medication详情、照片引用、Activity重建保留选择 | PASS |
| 没有预览缓存时显示不可用，不报成功空日程 | PASS |
| 既有67项设备回归，包含加强的实际语言断言 | PASS；测试后已恢复模拟器显示/字号配置 |
| 中文正常尺寸8阶段前缀补拍，直接点击计划行并滚动查看详情 | PASS；单独截图补证，不重复算完整15阶段 |

浏览阶段以真实服务返回的计划 ID、照片 ID 和当前长辈关联为断言来源。旧401、503、响应丢失仍只使用包裹真实 HTTP 的 instrumentation fault transport；正常 Debug 注册实际 transport。

最初一轮传入 `--locale zh-CN` 后业务断言虽通过，截图实际仍是英文；该轮不作为中文证据。冷启动复现确认偏好已保存 `SIMPLIFIED_CHINESE`、平台 app locales 却仍为 `[]`。本分支通过显式 Context/平台 LocaleManager 修复 API33+ 无 Activity delegate 的应用边界，并加强实际语言与冷恢复断言后重新跑出上表结果。#110 的同源语言代码没有被此分支静默替换。

## 自动审查发现及修复

Standards 审查发现1项：已获取内容在字节解码失败后不能重试。Spec 审查发现2项：同一解码重试缺口，以及长辈空/错误/恢复反馈缺口。均已修复并经过只读复核；没有剩余已确认的该轮阻塞发现。自动审查不算真实成员批准。

新增宿主回归覆盖原照片模型防重复加载/显式重试、计划详情与不可用预览、终止观察后的清空/重试、新关系与已撤销实例隔离、解码重试不重复下载且撤销后不重试、长辈返回前台自动恢复终止观察。父集成的9项上下文/恢复回归继续保留。

## 截图

| 场景 | 证据 |
|---|---|
| 英文计划详情 | [计划浏览](photo-plan/en-browser.png) |
| 英文实际私有图片，显式创建入口 | [照片浏览](photo-plan/en-photo.png) |
| 简中正常字号，实际点击/滚动查看计划详情 | [中文详情](photo-plan/zh-browser.png) |
| 简中320dp、200%字号实际照片和创建入口 | [大字号照片](photo-plan/zh-photo-320dp-200font.png) |
| 简中320dp、200%字号正式宿主/当前长辈/导航 | [大字号宿主](photo-plan/zh-header-320dp-200font.png) |

截图仅含虚构数据，无配对码/凭据。原始阶段日志、验证汇总、APK与失败记录位于本地 `reviews/photo-plan-formal-evidence`，不提交私有运行材料。

## 未验收

物理设备/OEM、API23实际设备、完整 TalkBack、真实提醒/服药动作、可信执行时区七日预览/冻结覆盖、#108编辑冲突、C3记录、正式手机号和云持久化仍单独推进。原生模拟器快门的父集成证据不作为本分支新执行的相机结果；当前完整路径用生成的实际私有源并经过原组件和真实上传。
