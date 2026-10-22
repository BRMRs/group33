# PR #22 首次开发基线发布检查

日期：2026-10-17（Asia/Shanghai）。范围是首次 Stage A 工程基线，不是产品发布、V2协议冻结或Gate 1完成。负责人本轮明确授权免人工审批、最终检查后squash合入并恢复常规审批；唯一权威例外在[ADR-0009](../adr/0009-two-stage-baseline-and-gate1-acceptance.md#one-time-pr-22-publication-exception--2026-10-17)。

## 审查输入与方法

- 固定基点：main `2544d9711584c3d10a78d9d78f5fecde28d9281c`；首次审查head：`a669bf3d92d0c76990a9cf27781558ad87110a9c`。
- 对比命令：`git diff 2544d9711584c3d10a78d9d78f5fecde28d9281c...HEAD`。
- 提交列表：6238f42（共同工程/契约）、0fd7848（SDK安装修复）、a7f27a2（保护规则记录）、a669bf3（两阶段交付/六角色导航）。
- 按code-review分别启动独立上下文：Standards Pascal / `01a145a7-f565-73b0-bf7e-adb7f77cd7c2`；Spec Sartre / `01a145a7-f51b-7c20-97fb-d2db4246b291`，均 `fork_context=false`。两者只读，没有写代码、提交review或冒充成员。
- Standards读取AGENTS、CONTRIBUTING、playbook/roster、ADR-0007/0009、settings、入门/角色卡等；附带Fowler十二项smell启发式，仓库规范优先、工具已覆盖的格式不重复报错。Spec读取#12最新正文/评论、Stage A边界、#23/#24最新决定及PR #25；不把明示未实现的业务当本轮缺陷。
- 当前产品依据是#23最新负责人时序澄清及[PR #25 revision 5dc2683的ADR-0008](https://github.com/BRMRs/group33/blob/5dc2683045a0c12393c60949d4143cd1575087e1/docs/adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md)，不是演练时期的7104c8c。PR #25本次不导入、不合并；后续仍走正常审批。

## Standards

独立报告：**2项P1新人开工阻塞，未发现其他有证据的发布阻塞或Fowler smell。**

1. `ROLE_REVIEW_HANDOFF.md:29`写“唯一响应窗口三十分钟”，违反ADR-0009当前产品优先、消除导航缺陷的要求。当前#23是三十分钟仅通知、两小时截止；T−5/T+5两次探测的时点已定。
2. `ADR-0009:11`将旧7104c8c的ADR链接作为当前权威；该版本要求三十分钟退出和独立短检测，与最新已接受决定冲突，违反playbook §1权威层级。

处置：修正角色卡及同一路径的时长提示；ADR-0009和ADR-0005指向已核验的5dc2683。历史演练仍保留当时输入，并就地标注已被后续澄清。没有为消除导航问题改业务代码/测试/JSON，也没有把两小时r1值当缺陷删除。

## Spec

独立报告：**1项P1复合导航阻塞；无另行证实的范围蔓延。**

`ROLE_REVIEW_HANDOFF.md:29,60`的“唯一响应窗口三十分钟”“V2的三十分钟截止”，加上ADR-0009/0005的旧权威链接，向同一新人流程给出互斥规范。#23要求“T+30分钟未确认服药只通知照护者，不撤下长者提醒/操作、不变Missed”和“长者响应窗口保留至T+2小时”；#24要求T−5/T+5两次探测各自等待后都无有效响应才通知，而非持续失联监控。它使Stage A的“resolved navigation defects”未满足。

处置：同一组有界导航修正；整组动作、补登记、探测协议和真实适配器继续由原Issue完成。两个轴独立保留，不将一轴通过掩盖另一轴的问题。

**分轴汇总：Standards 2项（最严重为P1错误时序/旧权威入口）；Spec 1项（最严重为P1不一致的当前开工规范）。**

### 修正后的独立复核

两轴复核同一parent a669bf3＋dirty输入，118项逐项摘要均通过，manifest SHA-256为`7c7f27d41fb7df9383b536c16dac48b6c9be21af3c1afa5d0b82cc23ebfaa125`。Standards原2项P1消除、未解决/新增阻塞0、smell0；Spec原1项P1消除、未解决/新增阻塞0。两者均核对当前#23/#24、例外范围和合入后main路径。本段仅追加已发生的复核结果与非阻塞后续，不改变被复核规则；最终提交另重算摘要，不能把dirty输入伪称已提交版本。

## writing-for-agents修正边界

- 入门先判断main实际是否已有工程，再选择正常main基底；将“仍三文件/一直Draft”改为状态查询分支，避免合入后所有新人继续被旧状态挡住。
- 权威规则留在ADR-0009，playbook/CONTRIBUTING/settings用短指针指向本次例外；开工单本身不授予发布权。保留普通DoR、明确角色/真实reviewer和人的实施指令。
- 当前时序仅修正误导性提示与来源：三十分钟通知、两小时退出，T−5/T+5两次检查；探测等待/协议仍OPEN。不复制PR #25整套产品文档，不虚构新技术参数。
- 新增报告与历史六agent演练分别标识；角色卡全部正式记录仍PENDING，贡献日志空行不代填。已存在的干净检出证据a669bf3与最终版本重跑证据分开。
- 未来新人的正常PR继续需要非作者/专业审批；本次例外不能被开工agent复用。

## 最终版本的可检查完成条件

发布执行者必须逐项取得事实，再在[PR #22](https://github.com/BRMRs/group33/pull/22)和[#12](https://github.com/BRMRs/group33/issues/12)记录；本文件不预填未来成功：

1. 两个独立轴复核修正后的输入，确认原阻塞消除，并保留未执行的设备/生产限制。
2. 更新manifest，验证其目标集合恰好为全部发布文件减manifest本身；原型、照片、私密配置/生成物不在提交中。当前业务Kotlin、资源、测试、JSON、Gradle/CI保持不变。
3. 精确记录最终head、manifest和fixture摘要。本地及干净检出通过policy/相对链接、摘要、完整 `./gradlew gate1Check test --rerun-tasks --console=plain`；核对XML失败/错误/跳过为零、lint、APK/bootJar及无测试probe。
4. 同一最终head真实远端build-and-test成功、当前main未前进、无未解决阻塞thread；将PR转Ready，合并API带预期head SHA和squash方式。
5. 仅在合并窗口调整ADR规定的三项approval参数；其他规则不变。无论合并成功或失败都finally恢复完整原ruleset，独立读回有效规则、branch protected和squash-only。
6. 合入后核对main的squash提交与已验head文件树一致，检出该main在独立目录重跑摘要/policy/构建；普通main push CI记录为该squash SHA自身的检查，不借用PR旧绿灯。
7. #12仍OPEN、原正式未勾待办保留；真人review数和实际成员贡献不增加。PR #25仍独立OPEN，不替它批准/合入；若GitHub自动调整stacked base，核验并记录，后续作者更新分支和重跑检查。

## 归入后续的任务（不是本次阻塞）

| 任务 / 验收 | 跟踪与建议职责 |
|---|---|
| PR #25完整产品文档正常审查、更新到新main、处理本次指针/例外的并行冲突并重跑检查 | #23/#25，文档负责人；不扩大本次免审批范围 |
| C2卡“旧T01时长不能当V2参数”的残留措辞随完整文档统一为“两小时时长一致但不证明完整V2” | Spec R2标为非阻塞；当前角色卡§2已明确现行窗口，不为该措辞扩大本次改动，交#25统一 |
| V2协议版本/组动作/照护者审计/两次探测等待与投递等六项收口，同版真实审查和协调测试迁移 | #24/#12，S1/S2及受影响功能角色；当前两小时时长本身无需推翻 |
| Room/HTTP/照片/计划/配对/同步/提醒/Storage/断云备用的真实实现与故障证据 | #10、#13–#21；各功能作者交自己的接入和测试，S1提供基础设施，S2交主要Server业务代码 |
| 六人实际代码测试PR、职责审查、Android/Server同版签署及成员理解/设备双语无障碍验收 | #12/#19/#21，实际成员亲自填写；均不由agent代签 |
| CI现有Node action运行时、setup-java弃用提示和ubuntu-latest迁移风险 | 后续单独CI维护任务，建议S2/构建backup；升级需正常PR、验证相同构建/摘要/测试，不混入本次 |
| 公开开发JWT占位和安装身份登录不适合共享部署 | #16/#20，S2在真实共享部署前明确配置/认证威胁模型和密钥验收；当前仅虚构本地预览，不批准部署 |

回退：应用基线可用正常受保护PR回退；GitHub管理规则不是代码revert的一部分，必须另行核验恢复。发布完成只意味着main有可开发骨架，不意味着任何提醒/服药结果已被真实验证。
