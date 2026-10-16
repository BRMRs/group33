# Gate 1 六角色只读开工演练记录

日期：2026-10-17（Asia/Shanghai）。依据负责人批准的[两阶段验收 ADR-0009](../adr/0009-two-stage-baseline-and-gate1-acceptance.md)，本记录只验收 Stage A 的可导航性，不是六名成员正式审查、代码贡献、设备验收或 Gate 1 签署。

结论：六个独立 agent 的 R1 均找到具体任务；修正导航提示、查重和 C2 范围后，R2 **6/6 NAVIGATION_PASS**，剩余导航阻塞为零。任务仍是候选，本轮没有实现候选测试或业务代码；人的审批与 Stage B 记录全部保留。

## 1. 方法、版本与限制

- 六个 agent 均以 `fork_context=false` 独立启动，分别只给 E/C1/C2/C3/S1/S2 角色及同一入口；不读取其他 agent 的报告。R2 复用各自上下文，不新增第七个角色或冒充新成员。
- agent 仅读文件、Git/GitHub 状态、现有源码/测试和摘要；禁止写文件、构建、Git 修改、Issue/PR 改写、approval、账号映射或签署。统一构建由主执行者另行完成。
- 六个上下文共享一个仓库/环境，不是六台电脑，也不证明六名新人已经理解。agent 的 PASS 只证明所列导航和任务定位结果。
- 两轮均在 parent HEAD `a7f27a25f745ed2ecd3882e9746530f98d415411` 加未提交文档的工作区执行。新指南、角色卡、ADR 不在该 HEAD，不能把 parent SHA 写作新文档版本。
- R1：116 条输入摘要；manifest SHA-256 `9c68c6ac9a2ac7a4998b4161c2450dfc9d686f122dc705e01e5c435df6d9a216`。
- R2：同样 116 条输入，修正文档后冻结；manifest SHA-256 `1fd117883eaeb3dca873f94c7e9f4e20f6a141d5871de282e4007ca052128950`。六个 agent 均独立逐项核验 116/116 OK。
- 这些是当时 dirty 输入的身份记录，不是可直接 checkout 的 Git commit。R2 后仅归档本报告、补证据链接/导航勾选和重算摘要；最终 manifest 加入本报告。最终可检出的提交、最终摘要、clean-checkout 和新 SHA 的远端 CI 由 [PR #22](https://github.com/BRMRs/group33/pull/22) / [#12](https://github.com/BRMRs/group33/issues/12) 单独记录，不能沿用旧 SHA 的 CI。
- main 实际仍为 `2544d9711584c3d10a78d9d78f5fecde28d9281c`，仅三个初始化文件；#22 与 #25 都是已发布的 Draft，未合入 main。演练经负责人明确授权读取 #22 工作区，并未自行更换开发基底。
- 产品来源：[现行 V2 #23](https://github.com/BRMRs/group33/issues/23)、独立 [PR #25](https://github.com/BRMRs/group33/pull/25) / ADR-0008（其 head `7104c8c122691990ef9ddec1f8bfd608640328c2`）。[#24](https://github.com/BRMRs/group33/issues/24) 技术收口仍 OPEN；#22 的 r1 代码/fixture 只作 legacy 结构与回归证据，不能签成 V2 实现或冻结。

## 2. 六个独立上下文及复核结果

| 角色 | 独立 agent（不是成员身份） | R1 | R2 |
|---|---|---|---|
| E | Meitner / `01a14586-3232-7622-96c9-a47459f6b17a` | 找到任务；有改进建议 | NAVIGATION_PASS |
| C1 | Copernicus / `01a14586-3275-7f01-a884-2a942ecada10` | 找到任务；有改进建议 | NAVIGATION_PASS |
| C2 | Hilbert / `01a14586-32b9-7c22-be34-edb139f10a5f` | 找到任务；有改进建议 | NAVIGATION_PASS |
| C3 | Faraday / `01a14586-3302-7d40-a619-41c4fa70fbe5` | 找到任务；有改进建议 | NAVIGATION_PASS |
| S1 | Schrodinger / `01a14586-334d-7431-8009-b2cff3383275` | 找到任务；有改进建议 | NAVIGATION_PASS |
| S2 | Goodall / `01a14586-33b8-7530-a443-a37b3efd8c1f` | 找到任务；有改进建议 | NAVIGATION_PASS |

共同检查路径：README → AGENTS → [开工指南](../AGENT_START_HERE.md) → [本人角色卡](../contracts/ROLE_REVIEW_HANDOFF.md) → Issue / API / Shared / Repository / 实际源码与测试。六个角色都区分了已有脚手架、test-only fake、缺失适配器，以及导航通过与实施前置未满足。

## 3. R1 发现与修正

| 发现 | 实际修正 | R2 结果 |
|---|---|---|
| 局部 API/模型/Repository、UI、词汇和 ADR 正文容易被单独误读为现行 r1 规范 | 在对应入口加 legacy/V2 状态提示，路由 #23/#25/#24；贡献矩阵标题明确历史 r1，测试计划的两小时 fixture 就地标为历史 | 六角色确认可找到现行来源；未修改旧 wire/JSON/产品决定 |
| accepted/rejected、双语 fallback、Late taken、基本 unknown/cache-pending 等已经有测试 | 角色卡要求先查重，C1/C3 改指未覆盖的关闭恢复/失败刷新链 | 逐项给出现有测试与真正缺口 |
| C2 首个候选扩展到新的生产 PlanEditorViewModel，超出小型契约测试范围 | 真正 ViewModel 留给未来 #14；首个候选收窄为现有 LocalDateRange 的结束端闰日边界 | C2 撤回宽候选，仅建议一个测试文件 |
| 新测试类不存在，定向命令容易被误认为当前可运行 | 指南明确 post-implementation / NOT RUN；演练禁止构建，由主执行者统一跑现有检查 | 六角色未把候选测试伪报通过 |
| scripted fake 预设值容易被误当状态转换、存储或上传证明 | 指南及 E/C1/C3 卡要求实际消费/调用断言，明确真实适配器另验收 | 所有报告保留证明限制 |
| 旧 Issue 的“尚未发布”与实际 Draft 状态不一致 | 指南要求只读核验，区分历史叙述、已发布 Draft、合入 main | agent 未擅自修改历史 Issue 或把 Draft 当 main |

## 4. 可转给成员的首个小 PR 候选

共同规则：`Refs #12`，不是关闭 #12；下面都是**未实现的建议**。未来还要负责人确认开发基底、实施范围和真实 reviewer。不映射账号，不把这些测试计为任何人的既有贡献。

Android 候选目录：`android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/`；Server 候选目录：`server/src/test/kotlin/com/group33/server/contract/`。除 S1 的现有文件外，以下类当前都不存在。

| 角色 | 一个可观察结果 / 路径 | 输入、期望及失败反例 | 联审边界 |
|---|---|---|---|
| E | 新增 `ElderContractReviewTest.kt`：发送前照片源失败有明确结果 | 真正调用 `PhotoSource.openStream()`，抛 IOException → 原 operationId 的 `NotSubmitted(Local(SOURCE_UNAVAILABLE))`、传输 spy 为零；加可读源对照，防止始终返回失败 | E/C2 照片；S1 源/context；S2 接收/READY |
| C1 | 新增 `IdentityContractReviewTest.kt`：关闭错误→refresh 后过期请求不消失且不重提 | 固定 respond context → `PAIRING_REQUEST_CLOSED` / retryable=false；刷新后同请求 EXPIRED/CODE_EXPIRED、relationshipId=null、关系列表空；检查消费顺序/次数。过滤成仅 PENDING 必须失败 | E 同意；S1 恢复；S2 关闭/终态 |
| C2 | 新增 `PlanContractReviewTest.kt`：保护 LocalDateRange 结束端闰日校验 | 2026-02-28..2026-02-29 抛 DateTimeParseException；2024-02-28..2024-02-29 成功且字段不变。若删去结束端解析，新反例应失败 | S1 公共类型；S2 日期含义 |
| C3 | 新增 `DashboardContractReviewTest.kt`：失败刷新与带作用域旧证据可同时表达 | elder-1 / 单日范围、last-success=1000、stale=true、最后已知 AVAILABLE+时间；refresh 返回 OFFLINE。调用 scope/range 正确，旧证据仍可读取；不得将尝试时间2000当成功或因离线把能力改为 UNAVAILABLE | S1 缓存/调用；S2 错误/接收；事件语义加 E |
| S1 | 修改已有 `RepositoryContractsTest.kt`：真实 scope 构造器拒绝任一空白分量 | 合法 cloud-demo/install-1/caregiver-1 原样返回；各字段逐个换空串和空白 `" \t"` 抛 IllegalArgumentException；isNotBlank 误改 isNotEmpty 必须被抓住 | C1 session；S2 环境/身份；真实非作者基础设施 reviewer |
| S2 | 新增 `FeatureTransportContractReviewTest.kt`：配对码保持字符串类型 | `{"code":"00123456"}` 经 Boot/MVC test-only echo 保留零；数字 `{"code":12345678}` 应被 Shared/Jackson 拒绝、MVC 400 MALFORMED_REQUEST、retryable=false、X-Request-ID 与体一致；这是待测覆盖缺口，不是已实测缺陷 | S1 跨端；C1/E 输入；真实 Server 非作者 reviewer |

查重依据：E 现有 PhotoSource 仅成功重复读取、照片 harness 预设成功；C1 现有 harness 基本调用/静态终态，不含错误后恢复链；C2 现有 RepositoryContractsTest 只覆盖同日、反序和非法开始端，另一 LegacyScheduleRules 校验器不能保护本构造器；C3 现有 harness 只有刷新成功/静态状态；S1 现有测试不含空白 scope；S2 现有配对码只有正常字符串往返。可从 [现有 Android harness](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt)、[模型测试](../../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryContractsTest.kt)、[Server 传输测试](../../server/src/test/kotlin/com/group33/server/contract/WireTransportContractTest.kt) 核对。

E/C1/C3 的参考调用测试只增加调用/表达证据，不能证明真实照片适配器、终态转换、Room 保持/隔离或进程恢复。S2 probe 不证明真实配对授权/持久化；若将来测出 converter 问题，仅经授权最小修复并联审，不在本轮改配置。

以下命令全部是 **NOT RUN / post-implementation**，并非六个 agent 已执行的测试：

```bash
# E
./gradlew :android-app:testDebugUnitTest --tests 'com.group33.medicationreminder.core.data.contract.ElderContractReviewTest' --console=plain
# C1
./gradlew :android-app:testDebugUnitTest --tests 'com.group33.medicationreminder.core.data.contract.IdentityContractReviewTest.closedErrorRefreshExposesExpiredRequestWithoutResubmit' --console=plain
# C2
./gradlew :android-app:testDebugUnitTest --tests 'com.group33.medicationreminder.core.data.contract.PlanContractReviewTest' --console=plain
# C3
./gradlew :android-app:testDebugUnitTest --tests 'com.group33.medicationreminder.core.data.contract.DashboardContractReviewTest' --console=plain
# S1：类已存在，拟新增方法尚未实现
./gradlew :android-app:testDebugUnitTest --tests 'com.group33.medicationreminder.core.data.contract.RepositoryContractsTest' --console=plain
# S2
./gradlew :server:test --tests 'com.group33.server.contract.FeatureTransportContractReviewTest' --console=plain
./gradlew :shared:test :server:test --console=plain
```

成员给 agent 的下一句应包含：只实现本人已确认的一个测试候选、范围不扩展、先查重、真实运行并展示 diff/报告、遇到生产或共享行为改动先停下说明。本人仍需解释一个正常/失败反例，核对测试实际执行及代码归属；不能照抄 PASS 作为签署。

## 5. 主执行者实际跑过什么

以下与 agent 的 NOT RUN 候选严格分开，检查的是**现有 legacy 基线**：

- JDK 17.0.18、Android SDK 35；`./gradlew gate1Check test --rerun-tasks --console=plain` 重跑成功，92 个 Gradle task 执行。
- Shared 16、Server 30、Android Debug 23，Release 另 23；测试失败/错误/跳过均零。Android lint 0 errors / 28 warnings。Debug APK、Server bootJar 构建成功。
- Boot JAR 无 ContractProbe / WireTransport 测试类，serialization core/json 1.8.1；不把构建成功当作真实业务可用。
- policy、Markdown 相对链接、manifest 逐项校验、`git diff --check` 通过；发布前按最终归档文件重新计算完整 manifest 并复核。
- `examples.json` 未变，SHA-256 `115b0dcc743973e359d67bef4a5b97d4bc4176a56f7a54ede434a6b2fbb09bc2`。源码、测试、构建/CI 配置未改；原型/照片/构建产物不在提交或审查 manifest。
- 干净检出和新 SHA 远端 CI 的最终结果在 PR/#12 的发布记录更新，不能用本轮脏工作区或旧 a7f27a2 绿灯代替。

## 6. 仍须真人/后续完成

| 项目 | 状态 / 下一步 |
|---|---|
| 新人是否能定位具体任务 | 六次独立角色导航 PASS；不代表六位成员本人已理解 |
| Stage A 发布验收 | 自动化准备与导航证据已提供；真实非作者、适用 Android/Server publication review 和单独合并授权仍缺 |
| 开发基底 | main 尚无工程；本轮不合并、不自行从 Draft 开始业务开发 |
| Stage B / #12 | 六成员实际代码/测试 PR、职责审查、同版本具名 Android/Server 签署均 PENDING；原有正式待办不勾选 |
| V2 技术契约 | #24 六项 OPEN，受影响协议/迁移必须协调；不重开已接受产品决定 |
| 生产能力 | Room/HTTP/outbox/事务/照片/通知/部署/断云与设备验收留在对应普通 Issue |

当前只 commit/push 这些规范与证据到 #22；继续 Draft、未合并、#12 OPEN，不映射账号、不代签。S1 不包办全部 Android 接入；S2 仍须主要 Server 业务代码，不以部署/CI 代替。
