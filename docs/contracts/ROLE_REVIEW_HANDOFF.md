# Gate 1 六角色正式审查交接清单（可转发给组员）

更新：2026-10-17。现行产品：ELDER_REMINDER_V2（[#23](https://github.com/BRMRs/group33/issues/23)、[独立文档 PR #25](https://github.com/BRMRs/group33/pull/25)）；技术收口 [#24](https://github.com/BRMRs/group33/issues/24) 仍 OPEN。`T01-T07/2026-10-16-r1` 仅标识当前 legacy 代码/fixture，不是 V2 冻结版本。任务：[Issue #12](https://github.com/BRMRs/group33/issues/12)；共同代码：[Draft PR #22](https://github.com/BRMRs/group33/pull/22)。

交付顺序已由负责人按[ADR-0009](../adr/0009-two-stage-baseline-and-gate1-acceptance.md)批准：阶段 A 验收可开发基线，阶段 B 保留六人真实贡献与正式 Gate 1 签署。下面角色卡首先帮助定位一个小任务；六人未来的代码 PR 不再是获取初始工程的前置，但不能在阶段 A 勾完阶段 B。当前 PR 继续 Draft、不合并。

本清单是既有[贡献矩阵](SUBMISSION_MATRIX.md)的执行入口，不改变产品决策、任务归属或合并条件。E/C1/C2/C3/S1/S2 暂不映射 GitHub 账号，下面所有正式审查均为 **PENDING**。agent 可以辅助解释、跑测试、整理发现；真实成员亲自确认理解和证据之后，才填写本人记录。agent 不代签、不提交 APPROVE，不把共同准备的代码算到六人名下。

## 1. 先认清这轮交付

| 交付层 | 现在已有 | 尚不代表 |
|---|---|---|
| 开发基础 | Android/Server/Shared 可编译工程、旧预览、构建与 CI、受保护 main | 已有可使用的 P0 产品；main 目前仍只有三文件初始化，完整工程在 PR #22 |
| Gate 1 共同契约 | P0 DTO、九个 Repository Interfaces、序列化/HTTP converter/调用 harness | 已有真实 Room、业务 HTTP、幂等事务、照片 Storage 或提醒执行 |
| 六人正式交付 | 已列明每个人要看和要交的内容 | 六个人已经审查/实现，或 Gate 1 已完成 |

先读[agent 开工指南](../AGENT_START_HERE.md)，完成环境和版本检查，再读下面自己的角色卡。新成员不需要先审完整仓库；先能解释自己的输入、输出、错误和一个反例。

## 2. 六个人共同要读、要跑的内容

共同阅读入口：

- [API_CONTRACT.md](../API_CONTRACT.md)：只详细读与自己操作相关的 routes、错误及幂等规则。
- [SHARED_MODELS.md](SHARED_MODELS.md) 与 [REPOSITORY_INTERFACES.md](REPOSITORY_INTERFACES.md)：核对 wire 类型与 Android 调用边界。
- [CONTRACT_TEST_PLAN.md](CONTRACT_TEST_PLAN.md) 与 [examples.json](examples.json)：按角色卡定位 case ID 和夹具；测试计划不是通过报告。
- [GATE1_REVIEW_PACKET.md](GATE1_REVIEW_PACKET.md)：区分真实 JVM 证据、scripted fake 与下游生产验收。
- [SUBMISSION_MATRIX.md](SUBMISSION_MATRIX.md)：角色代码责任的权威矩阵；[工程手册](../ENGINEERING_PLAYBOOK.md)控制 PR/审查流程。

**先做版本检查**：#23/PR #25 中 ADR-0008 已接受的 V2 产品行为高于这里 legacy 示例。2026-10-17确认的三十分钟仅通知照护者、两小时长者响应窗口、T−5/T+5两次无响应才连接告警（无全天监测）、整组确认/十分钟延后/求助、无强制二次确认、照护者独立补登记、独立失联告警、长者不承担同步管理，均不能退回旧规则。V2-T01–T06 的具体探测机制/每次响应等待上限/迟到回复和迁移仍由 #24 协调；当前代码没有这些完整能力。下文 case ID 指 legacy 测试族/待迁移证据，不能直接签成 V2 通过。

所有命令都在仓库根目录运行：

```bash
git rev-parse HEAD
git status --short
shasum -a 256 docs/contracts/GATE1_ARTIFACTS.sha256 docs/contracts/examples.json
shasum -a 256 -c docs/contracts/GATE1_ARTIFACTS.sha256
python3 scripts/check_repository_policy.py
./gradlew gate1Check test --console=plain
```

**完成条件**：记录实际 commit、manifest 和 fixture 摘要、JDK/SDK、命令退出码及报告位置。首次完整构建可能要下载依赖；执行失败写 BLOCKED 和实际错误，不能填“应该通过”。69 项三模块 JVM 检查加 23 次 Release 变体执行，是既有共同基线的结果，不是 33 个 P0 场景全部验收，也不是你的机器已经通过。

## 3. E — 长者端

**要看的文件**

- [DoseContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/DoseContracts.kt)、[IdentityContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/IdentityContracts.kt)、[PlanContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/PlanContracts.kt)：动作/发生项证据、同意配对、照片输入。
- [FeatureRepositories.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt) 中 DoseEvent、MedicationPhoto、Pairing、ReminderCapability；[RepositoryModels.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt) 中 LocalActionReceipt、DoseEventState、PhotoSource。
- [FeatureRepositoryHarnessTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt)、[RepositoryContractsTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryContractsTest.kt)；[ElderHomeViewModel.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderHomeViewModel.kt) 仅作为旧预览入口辨认。

**本人要交的代码／测试**

- Gate 1 首份小 PR：优先找照片源重复读取/失败时 typed result 的真实测试缺口，或“本地保存失败不显示已记录”的调用断言；不把 legacy Late taken 或长者 Pending 显示扩为 V2 UI。按缺口补映射，接口正确时不为凑贡献改字段。
- 审查 photo submit、consent、recordAction 的输入/结果，说明何时只是本地记录、何时 Server 已接受。
- 后续业务：[照片 #13](https://github.com/BRMRs/group33/issues/13)、[动作 #14](https://github.com/BRMRs/group33/issues/14)、[恢复 #18](https://github.com/BRMRs/group33/issues/18)、[无障碍 #19](https://github.com/BRMRs/group33/issues/19)。交自己的 UI/ViewModel、功能映射/Repository 接入和测试，S1 不代做全部接入。

**验收证据**

P01/P04、D03/D04、S03/S04/S07/S09、R04/U01 中实际执行的子断言；至少一个正常与一个失败/边界反例，附测试类/方法、日志和自身 PR diff。涉及旧响应/补报告的向量先作为 migration gap 交 #24；r1的两小时时长与最新窗口一致，但独立三十分钟通知、T−5/T+5两次检查、整组动作与求助不能凭r1签通过。Room 原子写、真实照片上传和设备通知/重启需后续真实适配器证明。配对与 C1/S2、动作与 S1/S2、照片与 C2 联审。

## 4. C1 — 身份、配对、共享语言

**要看的文件**

- [IdentityContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/IdentityContracts.kt)、[UserModels.kt](../../shared/src/main/kotlin/com/group33/shared/model/UserModels.kt)。
- [FeatureRepositories.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt) 中 Auth/Pairing/LanguageSettings；[RepositoryModels.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt) 中 SessionView、PairingDecisionResult、CommandContext。
- [ApiErrorMessages.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/ApiErrorMessages.kt)、[ApiErrorMessagesTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/ApiErrorMessagesTest.kt)、[FeatureRepositoryHarnessTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt)。

**本人要交的代码／测试**

- Gate 1 首份小 PR：accepted/rejected 基本调用与双语 fallback 已有测试；优先找关闭错误→refresh→已知 expired 终态不消失的缺口，保留原命令且不自动重提。需要补映射时按真实断言修正，不重复已有的正常往返。
- 核对 code entry 不授权、终态可恢复、超时保持同一命令上下文；与 S1 共设计 session seam，但不把 token 放到 UI 或 Shared。
- 后续业务：[配对 #10](https://github.com/BRMRs/group33/issues/10)、[语言 #19](https://github.com/BRMRs/group33/issues/19)。交登录/配对/长者列表、关系控制、自己的 Repository 接入和语言持久化代码。

**验收证据**

A01–A05、W02、R01/R04 的实际子断言；pending 不是 active、终态不是空列表、未知错误有安全双语 fallback。代码/测试与真实配对授权分开记录；真实过期、限流、并发、撤销验证在 #10/#16。E/S2 核对同意与授权，S1 核对 session/恢复。

## 5. C2 — 药品、照片查看、Reminder plan

**要看的文件**

- [PlanContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/PlanContracts.kt)、[DoseContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/DoseContracts.kt)。
- [FeatureRepositories.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt) 中 ReminderPlan/MedicationPhoto；[RepositoryModels.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt) 中 PlanPreview、OnlineCommandResult、Cached。
- [WireContractTest.kt](../../shared/src/test/kotlin/com/group33/shared/contract/WireContractTest.kt)、[FeatureRepositoryHarnessTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt)、[CaregiverHomeViewModel.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHomeViewModel.kt)（旧预览，不是已完成表单）。

**本人要交的代码／测试**

- Gate 1 首份小 PR：unknown/confirmed-cache-pending 的基本类型已有测试，优先检查现有计划/日期范围类型的未覆盖输入边界，限定为一个契约测试文件。真正消费 Repository 的 PlanEditorViewModel 及其表单状态测试是后续 #14 功能候选，不把新增生产 ViewModel 混入这次只读演练或伪称 Gate 1 小测试。
- 核对未被替换的照片先决条件、revision、IANA zone/日期/时间和预览/真实事件区分；旧 T01 时长不能当 V2 参数，组成员/迁移等受影响协议先按 #24 收口。
- 后续业务：[照片 #13](https://github.com/BRMRs/group33/issues/13)、[计划 #14](https://github.com/BRMRs/group33/issues/14)、[在线切片 #15](https://github.com/BRMRs/group33/issues/15)。交表单、查看照片、验证/转换和自己的 Repository 接入。

**验收证据**

P02–P04、D01/D02/D05/D06、S05/R02/R04 的实际子断言；至少覆盖失败保留状态、unknown completion 不新建 key、confirmed/cache-pending 不误报未保存。DST/overlap 算法、Server 原子事务、受权照片读取和旧 revision 的真实持久化仍属后续生产验收。与 E/S1/S2 联审照片、缓存和事务边界。

## 6. C3 — 概览、历史、Refresh 与状态反馈

**要看的文件**

- [SyncContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/SyncContracts.kt)、[DoseContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/DoseContracts.kt)。
- [FeatureRepositories.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt) 中 CaregiverDashboard/ReminderCapability/DoseEvent；[RepositoryModels.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt) 中 ElderOverview、Cached、SyncSummary。
- [RepositoryContractsTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryContractsTest.kt)、[FeatureRepositoryHarnessTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt)、[UI_DESIGN_DECISION.md](../UI_DESIGN_DECISION.md) 的状态表达。

**本人要交的代码／测试**

- Gate 1 首份小 PR：Late taken 与基本状态分类已有测试；优先检查刷新失败后仍保留 Elder scope、上次成功同步时间及最后已知 capability 的调用反例。它只证明调用/表达，真实缓存保持另由 Room/同步测试证明。
- 核对每个观察/refresh 的 Elder scope，分别展示 DoseStatus、SyncState、提醒能力，不排序 opaque snapshotVersion。
- 后续业务：[在线切片 #15](https://github.com/BRMRs/group33/issues/15)、[同步 #17](https://github.com/BRMRs/group33/issues/17)、[UI #19](https://github.com/BRMRs/group33/issues/19)、[验收 #21](https://github.com/BRMRs/group33/issues/21)。交概览/历史/Refresh 的实际代码与接入；组织验收不替代代码产出。

**验收证据**

D04、S06/S08/S09、R01/R03/R04、U01 的实际子断言；切换长者不串数据、状态不合并、失败保留 last-successful-sync。真实 Room merge/乱序响应/capability sequence 在 #17 等后续适配器证明。与 E/S1/S2 核对事件关联和 snapshot。

## 7. S1 — Android 数据与后台执行基础

**要看的文件**

- [FeatureRepositories.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt) 全部九个接口、[RepositoryModels.kt](../../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt) 全部公共结果/作用域/恢复类型。
- [SyncContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/SyncContracts.kt)、[DoseContracts.kt](../../shared/src/main/kotlin/com/group33/shared/contract/DoseContracts.kt)、[ContractProtocol.kt](../../shared/src/main/kotlin/com/group33/shared/contract/ContractProtocol.kt)。
- [RepositoryContractsTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryContractsTest.kt)、[FeatureRepositoryHarnessTest.kt](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt)、[build.gradle.kts](../../build.gradle.kts)。

**本人要交的代码／测试**

- Gate 1 首份小 PR：补 scope/command-recovery 的反例，检查 NOT_SUBMITTED/REJECTED 不能自动重放、未知结果保持原 operationId，或 cache-pending 与失败分离。
- 对 E/C1/C2/C3 的接口与测试做公共边界 review，明确谁实现功能 mapper、谁调用公共基础设施；不集中代写四个 feature 的全部集成。
- 后续业务：[Room/HTTP #15](https://github.com/BRMRs/group33/issues/15)、[同步 #17](https://github.com/BRMRs/group33/issues/17)、[恢复 #18](https://github.com/BRMRs/group33/issues/18)、[备用 #20](https://github.com/BRMRs/group33/issues/20)。交 Room/DAO/事务、session/网络基础、journal/outbox、WorkManager/AlarmManager 及恢复代码，按 Issue 分批。

**验收证据**

W01、S01/S03/S04/S06/S07/S08/S09/R04 的实际子断言；标明 test fake/真 Room/HTTP/设备分别证明什么。Room 原子性、进程恢复、重启与 API 23/35 不能靠 JVM harness 签通过。与 S2 核对动作身份、receipt、版本和环境隔离；具备相应能力的真实成员承担 Android 正式签署，不由 agent 顶替。

## 8. S2 — Server 业务与持久化／部署

**要看的文件**

- [contract 目录](../../shared/src/main/kotlin/com/group33/shared/contract/) 全部 wire 类型、[ApiModels.kt](../../shared/src/main/kotlin/com/group33/shared/model/ApiModels.kt)、[WireContractTest.kt](../../shared/src/test/kotlin/com/group33/shared/contract/WireContractTest.kt)。
- [ContractJacksonConfiguration.kt](../../server/src/main/kotlin/com/group33/server/config/ContractJacksonConfiguration.kt)、[ApiErrors.kt](../../server/src/main/kotlin/com/group33/server/web/ApiErrors.kt)、[WireTransportContractTest.kt](../../server/src/test/kotlin/com/group33/server/contract/WireTransportContractTest.kt)。
- [Server build.gradle.kts](../../server/build.gradle.kts)、[API_CONTRACT.md](../API_CONTRACT.md)；[ScheduleController.kt](../../server/src/main/kotlin/com/group33/server/web/ScheduleController.kt) 仅作为 legacy 边界辨认，不当作 P0 API。

**本人要交的代码／测试**

- Gate 1 首份小 PR：补真实 Jackson/MVC 的角色相关 request/response/error 兼容测试，例如 required/null/未知字段、header/错误 envelope 或 terminal pairing DTO 的缺失反例；补必要的 transport/DTO 修正，不伪装成业务授权证明。
- 检查 Shared 与 Server 序列化、null/enum/整数和现有运行时版本一致；probe 只在 test，不能为演示创建假 P0 endpoint。
- 后续业务：[配对 #10](https://github.com/BRMRs/group33/issues/10)、[照片 #13](https://github.com/BRMRs/group33/issues/13)、[计划/事件 #14](https://github.com/BRMRs/group33/issues/14)、[持久化 #16](https://github.com/BRMRs/group33/issues/16)、[备用 #20](https://github.com/BRMRs/group33/issues/20)。必须交主要 Controller/Service/Repository、授权/幂等/事务与持久化测试；Docker/Render/CI 只是附加交付。

**验收证据**

W01、A01–A05、P01–P04、D04/D05、S01/S02/S07/S08/S09 的实际子断言；标明真实 mapper/MVC、test-only probe、真实业务服务/数据库三种不同证据。生产并发授权/receipt 事务、PostgreSQL/Storage 重启和完全断云备用在对应业务 Issue 验收。与 S1 及相关 feature 共同核对；真实具备能力的成员承担 Server 正式签署。

## 9. 怎样提交第一份小 PR

为减少六个人同时改同一个 harness 的冲突，可考虑下列**未创建的候选测试文件**。先确认存在真实缺口，再决定扩展旧测试还是新建；不是要求为六个人复制六套 fake。公共测试工具需要抽取时，先与 S1/S2 协调。

| 角色 | 候选新增测试文件（项目根目录相对路径，不是已有实现） |
|---|---|
| E | `android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/ElderContractReviewTest.kt` |
| C1 | `android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/IdentityContractReviewTest.kt` |
| C2 | `android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/PlanContractReviewTest.kt` |
| C3 | `android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/DashboardContractReviewTest.kt` |
| S1 | `android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/CommandRecoveryContractReviewTest.kt` |
| S2 | `server/src/test/kotlin/com/group33/server/contract/FeatureTransportContractReviewTest.kt` |

1. 人确认自己的角色和任务范围；当前不需要把六角色绑定到 GitHub 账号。负责人的临时分配写在 Issue，但正式 feature Ready 的人选/审查要求仍按团队 roster 与 DoR 执行。
2. 先查现有测试。上面的第一份 PR 是候选，不是指定重复断言；已经覆盖时报告证据，改选一个真实缺口。不为凑个人 diff 重写正确接口或抽一个无人使用的 mapper。
3. Gate 1 本人提交至少有实际 Kotlin 契约/调用/映射或有意义的测试代码；普通业务 Issue 后续还必须有实际功能代码。只有“看过”、文档、截图或 agent 结论不满足成员代码要求。
4. 以一个反例/一个小结果开 PR，列修改文件、输入输出、测试 ID 的具体子断言、错误路径、跨角色 reviewer 和下游未验收项。同一测试案例族可由多人贡献不同断言，不用造重复代码。
5. 始终 `Refs #12`，直到整个 Gate 1 真正验收；其他完整业务任务才按模板决定是否使用关闭关键词。

## 10. 同版本审查与签署格式

负责人先发布一个**已提交、已 push、CI 已通过**的完整 review commit；每个人在独立 checkout 记录同一版本。本文当前若仍未被 Git 跟踪，就只是工作区交接草案，不能将旧 HEAD 冒称为包含本文的正式版本。

同一轮必须一致的字段：完整 commit SHA、技术 baseline、manifest SHA-256、examples.json SHA-256。工作区的受审文件有修改或新的未提交受审文件时，先发布新版本再审；排除的本地原型不属于受审文件。新提交后按新版本确认影响并重签，不能复制旧日期/结果。

每人复制后由**本人**填写；空白保持空白：

```text
Gate 1 role review
角色：E / C1 / C2 / C3 / S1 / S2（选一）
实际审查人：<真实姓名或组内可核验称呼；不强制 GitHub 账号>
日期与时区：<实际时间>
Review commit：<完整40位SHA>
Product baseline：ELDER_REMINDER_V2（#23/ADR-0008）
Contract baseline：<同版已共同确定的协议；#24尚未解决则PENDING，不能用r1冒充>
Legacy code reference：T01-T07/2026-10-16-r1（如本轮仍有legacy）
Manifest SHA-256：<实际摘要>
Fixture examples.json SHA-256：<实际摘要>
已读文件与接口：<具体路径/方法>
本人代码/测试PR与commit：<链接；尚无则写PENDING，不能代填共同代码>
执行命令/环境/退出码/报告：<实际结果；未运行写NOT RUN>
实际覆盖：<case ID + 子断言 + test class/method>
失败/边界反例：<输入、期望、观察>
仍未证明：<真实适配器/事务/设备等；对应Issue>
Blocking / Question / Suggestion：<逐项；无则由本人确认>
结论：PENDING / BLOCKED / REVIEWED_WITH_OPEN_ITEMS / APPROVED_FOR_GATE1_SCOPE
本人确认：<我能解释上述边界，并亲自核对记录；本人填写>
```

有阻塞发现、未运行必须检查、缺失规定代码 PR 时，不能选择 APPROVED_FOR_GATE1_SCOPE；先如实记录 REVIEWED_WITH_OPEN_ITEMS 或 BLOCKED。代码作者可记录自己的职责审查，但不能在 GitHub 批准自己的 PR；角色记录与非作者 approval 是两种证据。

Android/Server 联合签署再各复制一份：

```text
Gate 1 cross-boundary sign-off
视角：ANDROID / SERVER（选一）
实际审查人：<本人>
日期：<实际时间>
Review commit / baseline / manifest SHA / fixture SHA：<与六角色记录完全相同>
已核对：<routes/DTO/null/error/identity/time/Repository/case ID逐项证据>
关联角色记录与代码/测试PR：<链接>
未验收生产行为与跟踪Issue：<明确列表>
阻塞项：<清零证据；未清零则BLOCKED>
结论：PENDING / BLOCKED / APPROVED_FOR_GATE1_SCOPE
本人确认：<本人填写；agent不能代签>
```

| 正式记录 | 当前状态 |
|---|---|
| E / C1 / C2 / C3 / S1 / S2 | 六项均 PENDING |
| Android / Server 联合签署 | 两项均 PENDING |
| Issue #12 / PR #22 | Issue 保持 OPEN；PR 保持 Draft、未合并 |

## 11. 关于“先合入大家能开发的脚手架”

**已批准，2026-10-17，见 ADR-0009**：把“开发基线可以供全组使用”和“Gate 1 正式完成”分开。阶段 A 交付可编译工程、任务入口、真实边界、接口/fixture/测试和六次独立只读开工单；未来发布仍保留 CI、非作者、适用的真实 Android/Server publication review 和单独的合并授权。阶段 B 继续要求六角色真实代码/测试 PR、正式职责审查和同版本联合签署；不能在 baseline merge 时勾完，也不要求这些未来代码先存在才能拿到初始工程。

本轮明确授权只包括规范/PR边界同步、六个独立只读agent演练、修正文档、检查、commit/push至PR #22。不会修改保护规则、转 Ready、合并或关闭 Gate 1。阶段 A 的边界 review 只判断安全发布 legacy foundation，不是阶段 B 对 V2 协议的正式冻结；#24 未解决的相关技术仍不允许页面自行实现/猜字段。

新人先让 agent 给出可核验的小任务和解释，真实成员复现/理解后逐步提交。独立agent演练仅证明角色导航可用，不证明六位成员本人或六台机器已跑通，更不能为成员签署。本轮报告中的角色标签不映射任何账号。
