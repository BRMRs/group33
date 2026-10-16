# 给新组员和 agent 的开工入口

目标：让每个人知道自己负责什么、第一步改哪里、怎样验证、何时交给别人。本文是任务导航，不是新的产品规范或自动实施许可。职责依据[六人分工](TEAM_BRIEFING.md)与[贡献矩阵](contracts/SUBMISSION_MATRIX.md)，流程依据[工程手册](ENGINEERING_PLAYBOOK.md)。

2026-10-17 负责人已批准[两阶段验收 ADR-0009](adr/0009-two-stage-baseline-and-gate1-acceptance.md)：Stage A 发布可开发基线，Stage B 才完成六人真实贡献/正式审查并关闭 Gate 1。PR #22 的当前状态从 GitHub 核验；其一次性发布例外仅适用于本次负责人的发布授权，不适用于新人任务。先看[现行产品 #23](https://github.com/BRMRs/group33/issues/23)、[独立文档 PR #25](https://github.com/BRMRs/group33/pull/25) 与[技术收口 #24](https://github.com/BRMRs/group33/issues/24)。三十分钟通知、两小时退出与T−5/T+5检查已经确定；具体探测等待/协议仍待收口。r1的两小时字段与最新窗口一致，但旧代码/JSON/测试不能证明完整V2。

## 1. 组员只需要先给 agent 这一段

把下面的角色替换成自己的 E/C1/C2/C3/S1/S2；不知道角色时先问负责人，agent 不能按账号或代码位置猜：

```text
我是本项目的 <角色>，没有开发经验。
请先阅读 AGENTS.md 和 docs/AGENT_START_HERE.md，再读
docs/contracts/ROLE_REVIEW_HANDOFF.md 中我的角色卡。
这次只做只读开工检查，不改代码、不提交、不推送、不代我审查或签字。
核对当前分支/commit、main是否已有完整工程、我的Issue/依赖/验收和实际代码。
用中文告诉我：
1. 我负责什么、别人负责什么；
2. 已有代码与尚未实现的能力，各列出实际文件证据；
3. 我的第一份小PR：一个可观察结果、建议修改路径、一个失败反例和测试命令；
4. 哪些条件已满足、哪些需要负责人或其他角色先完成；
5. 我下一句可以发给你的明确指令，以及我必须亲自核对的结果。
不用重新讨论已确定的产品决策，也不要把fake当真实业务。
```

**完成条件**：agent 输出第 3 节的完整开工单，不只回复“已了解”。这不是六人正式签署，也不会把 Issue 自动变成 Ready。

## 2. agent 的只读检查顺序

1. **工作区**：读 AGENTS/工程手册/roster；检查 Git 分支、HEAD、未提交改动，保护他人内容。角色只来自本次人的明确输入；未知时只问角色，不索要全部 GitHub 账号。
2. **基线**：读取当前 main 和 PR #22 的实际状态。先确认 origin/main ref，再用 `git cat-file -e origin/main:settings.gradle.kts` 判断 main 是否已有工程；网络允许时只读核对远端 SHA。main 已有工程时，以最新 main 为正常开发基底，先完成本次只读开工单，实施另等人的指令与DoR。若仍只有三文件或本地ref落后，报告实际差异并请人确认同步，不在空 main 运行 Gradle、不自动改用旧 PR 分支。负责人提供的 PR/工作区快照仅用于明确授权的理解；未提交输入用parent SHA+dirty摘要标识。改基底、切换工作区、stacked PR 先由负责人确认。
3. **范围**：读自己的[角色卡](contracts/ROLE_REVIEW_HANDOFF.md)，再读角色卡链接的 API/模型/测试和相关 ADR。到 GitHub 读取现有 Issue 的最新正文、依赖、reviewer、其他重叠 PR，优先核对 #23/#24 的版本差异。新产品决定已接受不等于 V2 技术协议已冻结；受影响的组动作/照护者补登记/告警/迁移先交 #24，不擅自加 wire 字段。网络不可用则标明来源版本和无法核验项，不假设已 Ready。
4. **现状**：在源码查入口、调用接口、既有测试；对候选反例先确认是否已覆盖。每个“已有/没有/建议新增”结论附路径或命令证据。
5. **证据**：环境齐备时运行适用的只读/构建检查；实际缺 JDK/SDK/网络/设备就记录原因，不抹掉测试、不放宽授权。本地构建会生成被忽略的产物，不属于功能源码改动。
6. **交接**：输出下面的开工单，然后等待人明确要求实现某个有边界的小任务。涉及产品/共享行为冲突只暂停受影响部分，按手册记录，不自行改契约。

规则中的 account mapping 暂缓，不妨碍只读学习和已授权的共同 Gate 1 准备；但不能据此绕过 roster/DoR，把未指定负责人/reviewer 的 feature Issue 标 Ready。本文不改变已有这个前提。

## 3. agent 必须交出的开工单

```text
角色 / 本次模式：<角色> / ONBOARDING_READ_ONLY
当前commit / branch / main工程基线：<实际值>
受审输入状态：<已提交SHA，或parent SHA + dirty输入/manifest摘要；不把旧HEAD当新文档版本>
产品/代码版本：<V2现行决定 / r1 legacy代码；受影响技术项OPEN>
来源：<Issue、合同、角色卡、关键源码>
当前可用：<实际文件、类/方法与可运行测试>
尚不可用：<缺的真实适配器/服务/设备能力；不能拿fake充数>
第一份小任务：
  关联Issue：<Gate1贡献Refs #12，或已Ready的具体业务Issue>
  一个可观察结果：<不超过一个小PR>
  可修改路径/拟新增文件：<明确标出现有/新增；范围外保持原样>
  接口输入/输出/错误/状态：<对应既有contract，不能补猜字段>
  失败或边界反例：<可复现输入和期望>
  测试命令与报告：<实际能跑什么；未运行的原因>
依赖：<已满足 / 可用reviewed fake并行 / 集成验收阻塞>
需要联审：<feature、S1、S2的具体边界>
当前不能做：<未获授权或缺前置的工作，不扩成全系统重写>
下一句给agent的指令：<实现这个明确范围；本地验证；先给diff/证据>
人必须核对：<理解的行为、实际运行结果、自己代码归属、审查结论>
```

agent 可以列出候选任务；“负责人已分配”“某成员已批准”“本 PR 可合并”必须有真实记录，不能从模板推断。开发/发布授权由本次人的请求及工程流程决定，不由这张开工单产生。

## 4. 现在的工程能用什么

| 模块 | 可复用基础 | 还需真实实现 |
|---|---|---|
| Android | XML/ViewBinding、Activity/ViewModel、旧 in-memory 预览；P0 九个 Repository 接口、typed results、双语错误映射与 JVM harness | P0 屏幕和功能 mapper/adapter、Room/DAO、session/HTTP、outbox/同步、AlarmManager/WorkManager/重启恢复 |
| Shared | 旧 model 与 P0 contract 并存；DTO/enum/fixture、kotlinx 序列化测试 | 真实调用迁移；由拥有角色按已批准规范实现/验证相应业务规则，DTO本身不是校验器 |
| Server | Boot/security/anonymous login/legacy schedule 骨架；严格 Jackson/错误 envelope 和 test-only MVC probe | P0 pairing/授权/photo/plan/event/receipt/snapshot 业务、事务/数据库、Storage、Render与本地备用 |
| 测试/协作 | gate1Check、CI、PR/Issue模板、main保护、正式审查模板 | 各人机器与设备验收、生产适配器证明、六角色真实贡献和签署 |

真实来源：[README](../README.md)、[review packet](contracts/GATE1_REVIEW_PACKET.md)、[API](API_CONTRACT.md)、[角色卡](contracts/ROLE_REVIEW_HANDOFF.md)。上表说明脚手架边界，不是承诺完整业务可用。旧预览走旧 Repository 是当前状态，不能据此让新 P0 功能继续依赖全局 in-memory 真相。

## 5. 第一个小任务从哪里开始

| 角色 | 先做的共同契约小任务方向（先查重） | 后续业务Issue |
|---|---|---|
| E | 照片源读取失败/typed result的角色定向调用反例；不把legacy报告扩为V2交互 | #13、#14、#18、#19 |
| C1 | pending/terminal配对、恢复context与错误/双语映射反例 | #10、#19 |
| C2 | 现有计划/日期范围类型的未覆盖边界测试；真实表单ViewModel另归#14 | #13、#14、#15 |
| C3 | Elder scope、stale/last-sync/capability和late report反馈反例 | #15、#17、#19、#21 |
| S1 | scope、命令恢复与不自动重放的公共契约反例 | #15、#17、#18、#20 |
| S2 | Shared/Jackson/MVC的缺失字段/错误/header/具体DTO兼容反例 | #10、#13、#14、#16、#20 |

这不是六个已分配任务；具体文件、测试ID和代码责任以[角色卡](contracts/ROLE_REVIEW_HANDOFF.md)和现有 Issue 为准。生产功能只在具备相应 Ready/owner/reviewer 和明确实施授权后进入；依赖限制的是集成/验收，不是让所有 UI/映射工作都等 S1/S2 完工。

候选新增测试类还不存在时，`--tests <新类>` 仅是实现后的验收命令，不是当前就应通过的命令。开工演练用现有源码证明缺口，标明 NOT RUN；统一构建由主执行者运行。只有脚本返回预设值的 fake 不能证明真实读取/状态转换/存储保持。

Issue 里“尚未发布”一类旧叙述与当前状态冲突时，区分历史记录、已发布 Draft PR 和已合入 main：通过只读查询核验，不把 Draft 当未发布或已合入，也不据历史文字擅自改任务状态。

## 6. 真正开发时怎样避免相互堵住

- E/C1/C2/C3 自己交 UI/ViewModel、功能 Repository 接入、模型/状态映射和测试；共同的 scope/session/数据库/网络约束找 S1/S2 联审。S1 提供基础设施，不承包所有 feature 集成。
- S2 必须拆出实际业务 Controller/Service/Repository/事务测试任务，配对/授权/计划/事件是主要代码产出；部署文件不是该角色的全部贡献。
- 缺生产 adapter 时，可用已经 review 的接口与 test-only fake 做调用和映射测试，并显式跟踪真实接入。不要把 fake 注册到正式执行路径或靠它宣称数据库/通知验收。
- 改共享 DTO/API、错误/time/sync、根构建、公共数据 schema 前，先在已有 Issue 记录变更范围及所需 Android/Server reviewer；不是各自新建一套契约或越过已定决策。
- 一次 PR 交一个能验证的小结果。复用现有 Issue，不因角色不同克隆六份大而全任务；必要的新子任务先给负责人确认，保留原依赖图。
- 人的下一步是复现命令、看一遍 diff、能解释一个反例。agent 输出的绿色结论不能替代人的 GitHub approval 或六角色签署。

## 7. 开发基线“可以交给新人”的阶段 A 验收

已按 ADR-0009 接受；以下是阶段 A 的证据要求，不是当前的合并许可。负责人在考虑发布前，至少能展示：

- [x] 干净检出构建/测试已跑通（a669bf3证据见PR #22）；最终发布版本仍须重跑并记录其自身SHA/CI，命令/环境/失败提示明确。
- [x] README → AGENTS/开工指南 → 自己角色卡 → Issue/源码/测试，六条路径都找得到。
- [x] 六个角色的只读开工单各得到一个有价值的小任务：可改文件、接口、输入输出、失败反例、测试、依赖、reviewer，不靠未实现的能力假装可运行。
- [x] 已实现、旧预览、接口/test fake、待实现生产适配器明确分层；JVM检查与设备/生产验收分开。
- [x] 共享文件 owner/协作方式及阻塞升级路径清楚；角色/负责人未确定的任务如实停在准备状态。
- [ ] 后续成员本人能解释自己的小任务并确认正式记录；这不是获取初始工程的循环前置，也不能把“agent可读”当成“六名成员都已上手”。

六次独立 agent 演练的结果由主执行者统一记录为独立证据；它们不代表六台成员机器现场验收或六个人已理解。阶段 A 的只读演练能通过，同时阶段 B 的成员/签署仍全部 PENDING。

2026-10-17 [演练记录与六份小任务](evidence/GATE1_ONBOARDING_DRY_RUN.md)：R1 后修正文档，R2 六项 NAVIGATION_PASS。这里的勾选仅覆盖导航准备；最终提交的干净检出/远端 CI 证据在 PR #22 更新，人理解/真实审查仍未勾选。

已批准的两阶段边界见[交接清单第11节](contracts/ROLE_REVIEW_HANDOFF.md#11-关于先合入大家能开发的脚手架)。六个成员未来的业务 PR 和完整 Gate 1 签署不再阻止获得基础工程。仅PR #22本次按[一次性发布例外](adr/0009-two-stage-baseline-and-gate1-acceptance.md#one-time-pr-22-publication-exception--2026-10-17)在检查通过后发布并立即恢复常规审批；其余PR按正常CI、非作者/专业审查和发布授权执行。开工单不授予合并权，Gate 1保持OPEN。
