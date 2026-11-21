# 正式页面的照片与计划浏览接入

Refs #50, #52, #15。集成负责人：BRMRs；原组件作者：Iris-wly。

本次从 #110 待评审提交 `4db15b775a85b143fa79cc6aeb29fc9d95f25800` 创建独立分支 `feature/52-photo-plan-formal-host`。所有实现、测试和修复都在新分支；不更新 #110 的 head，不把这次验证当成 #110 已经获得的新证据或批准。新 PR 以 #110 分支为临时 base，父 PR 合入后需要对实际 main 重新核对差异和验证。

## 复用与职责

| 来源 | 精确版本 | 本次实际使用 |
|---|---|---|
| #55 | 源 head `4b04e64519ff7a7097eb60a2e8253757867565a2`；已通过 main `999e1826ebca07998eaa9e150c0e47b5b05921fa` 合入 | 原 `CaregiverPhotoViewModel` 的照片集合、选择、加载、重试、撤销锁定和观察失败处理 |
| #107 | `1638f116b8e55c7968b3b5287a8b5c3b3a25d8cc`，通过正常 merge 保留历史 | 原 `PlanBrowserViewModel` 的集合、计划选择/详情、缺失/空/陈旧/错误、观察重试和预览匹配状态 |
| #110 | `4db15b7` | #59/#92 正式入口导航、#84/#85 会话与原操作恢复、#82 拍照、#86 原编辑器、#100 可信长辈上下文及真实 HTTP/Room 适配 |

#55/#107 两个原 ViewModel 的生产代码保持原样。宿主保存它们的状态对象，不另存照片/计划/Medication 列表，也不另写内容选择、加载或详情选择状态机。`ChainViewModel` 保留认证、配对、网络刷新和可信上下文装配；实际字节解码与是否打开编辑器属于宿主呈现状态。

```text
#92 CaregiverHome / Plans
  trusted session + selected relationship
    #55 PhotoViewModel → actual private content → bounded bitmap decoding
      photo preview → explicit create action → original #86 editor
    #107 PlanBrowserViewModel → list → selected details → original photo reference
  source Repository observations ← private Room ← actual HTTP
```

原组件实例按环境、安装、账号、会话代际、长辈和关系 ID 保留。切换/退出会清空观察和图片；过期回调仍需通过当前可信上下文检查。同一已撤销实例继续保持锁定，新会话或新接受的关系使用不同实例，不能靠重绑原实例解除撤销。Activity 重建保留同一宿主及当前计划选择。

## 页面行为

- 照护端“计划”显示照片集合、照片加载/失败/重试和实际私有原图。单纯看图不自动打开创建表单；点击“用这张照片建立计划”才打开原 #86 编辑器。
- 计划行打开原 #107 选中状态对应的详情，显示确认的药名、用量、时间、执行时区、日期范围、启用状态和说明；有已授权照片引用时可打开原图。浏览不会发送计划写命令。
- 无本地数据、已确认空集合、授权未知、撤销和观察失败分别显示。观察重试只重新读取本地来源，不冒充网络刷新。原有“刷新共享资料”仍执行真实 HTTP 后更新 Room。
- 字节解码失败有独立重试，保留原照片选择；重试前后都检查可信上下文。原 #55 继续负责内容获取及其重试，不因解码失败另造一套下载状态。
- 长辈端自动使用原计划浏览状态，显示简短加载/空/暂不可用反馈，不出现同步管理控件或技术同步状态。返回前台时刷新并重新打开已终止的本地观察。

## 不伪造提醒预览

当前适配层没有发生项/冻结覆盖缓存。预览的目标授权状态跟随实际计划读取，但 `hasLocalData` 始终为 false；页面显示“暂时无法提供预览”，不显示成功空日程。不会调用 Server 注册来伪造预览，也不创建任何实际发生项。

原 #107 接口接收七个本地日期。当前宿主使用设备 Clock 生成这个未提供数据的查询范围，且不展示时间候选。真正启用预览前，必须接可信执行时区日期及实际 occurrence-overlay 适配，验证跨时区/DST/冻结排除；本轮不声称这项已完成，也不猜测长辈时区。

## 冷启动语言修正

实际截图检查复现了 API35 边界：在没有 Activity delegate 时保存 `SIMPLIFIED_CHINESE` 后，平台 app locales 仍为 `[]`，首次页面显示英文。仅传测试的 `--locale zh-CN` 并通过业务断言不足以证明中文 UI。

本分支为语言应用方法提供显式 Context，在 API33+ 通过平台 LocaleManager 应用选择，旧版本仍走 AppCompat；Application 冷启动和两个正式切换入口都提供 Context。测试现在断言 Activity 的真实语言。双端脚本还会在保存偏好后清除平台 locale 并 force-stop，再启动页面，验证真正恢复，而不是依靠仍存活的 Activity。这个修正没有推回冻结的 #110。

## 复现

使用父集成的[本地真实服务说明](DEBUG_ONLINE_CHAIN.md#run-the-actual-local-service)。服务仍是内存业务存储和私有本地照片；无 PostgreSQL/云/Server 重启持久化的新验收。

```sh
./gradlew gate1Check test :android-app:assembleRelease :android-app:assembleDebugAndroidTest --console=plain
ANDROID_HOME=/path/to/sdk python3 scripts/run_debug_online_chain.py \
  --locale en-US --output /tmp/photo-plan-en --reset-test-apps
ANDROID_HOME=/path/to/sdk python3 scripts/run_debug_online_chain.py \
  --locale zh-CN --output /tmp/photo-plan-zh --reset-test-apps
```

脚本只允许两台不同的虚构数据模拟器，默认 `emulator-5560` / `emulator-5562`。十五个阶段在原十四阶段上增加真实计划详情、页面重建、原图浏览和不自动打开编辑器的检查；每次页面启动核对实际语言。码、凭据和内部桥接 ID 不打印或提交。

运行结果、截图和具体限制见[本次证据](evidence/PHOTO_PLAN_FORMAL_HOST.md)。

## 后续边界

#108 编辑冲突处理、修改/停用、真实提醒调度和服药动作、C3 总览/记录、正式手机号绑定、云环境、物理设备/OEM、完整 TalkBack 验收均没有由本次接入完成。保留父 PR 和组件的真实独立评审要求；合并代码和通过自动审查不算成员批准，不关闭 #50/#52/#15。
