# 初始提交范围与发布记录

日期：2026-10-16（Asia/Shanghai）。范围：`group33/`。项目负责人已明确批准：纳入新增协作规范；补忽略规则和 Gradle checksum；更新清单并重跑检查，通过后按三文件初始化 main，再提交工程/Gate 1、push 并创建 PR。排除原型和照片，保留六人正式审查，不关闭 Gate 1。

## 当前批准的发布范围（覆盖下文历史快照数字）

| 范围 | 文件数 | 执行边界 |
|---|---:|---|
| main 初始化 | 3 | 仅 .editorconfig、.gitattributes、.gitignore |
| 首个工程/Gate 1 Draft PR | 111 | 既有工程、Gate 1 增量、审计报告及新增协作规范 |
| 两个提交合计 | 114 | 精确白名单，manifest 覆盖其中除自身之外的 113 个输入 |
| 本地保留、禁止进入本次 PR | 19+ | 整个 android-app/prototypes/，含新增 C_V2_NOTES、c-v2.css/js 和全部照片/预览；该目录仍在并行更新，后续新增也全部排除 |

新增协作文件全部纳入：`.github/CODEOWNERS`、`.github/ISSUE_TEMPLATE/decision.yml`、`docs/ENGINEERING_PLAYBOOK.md`、`docs/GITHUB_SETTINGS.md`、`docs/TEAM_ROSTER.md`、`docs/adr/0007-engineering-governance-and-squash-workflow.md`、`scripts/check_repository_policy.py`。现有 AGENTS、CONTRIBUTING、README、PR 模板、CI、triage-labels 中的协作更新随当前完整版本纳入，不把这些用户更新归作 agent 编写或 Gate 1 功能代码。

### 明确冲突与处理

- 新治理 ADR 原名与发生项 ADR 同为 0005：将未发布的治理 ADR 改为 0007，保留原产品/时间 ADR-0005 和备用 ADR-0006 的身份与内容。
- 手册原本一律要求 PR 关闭主 Issue：改为完整交付使用关闭关键词，部分检查点使用 `Refs #12` 并列出未完成项。本次绝不关闭 #12。
- 手册禁止直推 main：记录负责人此次明确授权的三文件首次初始化例外，不允许后续功能直推或绕过 review。
- 六角色尚未映射账号、feature DoR 尚未满足：本次只允许负责人发布共同审查基线的 Draft PR；不将实现任务标 Ready，不代签、不虚构个人贡献，不将契约标为 ACCEPTED。
- GitHub 设置文件只是管理检查清单：纳入文件不等于已开启规则保护或修改云端设置，本次不擅自变更仓库管理策略。
- 历史原型文件清单改为代码路径而非 Markdown 链接，避免未提交照片/原型成为干净检出中的断链。

### 本轮发布前硬化与校验

已补忽略规则：签名/私钥、SQLite/Room 数据库及其 journal/WAL/SHM、APK/AAB、heap dump、Python cache、构建中实际出现的 `.kotlin/` 编译器 session；保留 wrapper JAR 可提交。已设置 Gradle 8.9 官方 `distributionSha256Sum`，不升级版本；wrapper JAR 也与官方 SHA-256 一致。

`gradlew.bat` 仅按既有 `.gitattributes` 的 `eol=crlf` 规则规范化换行，不改命令内容；manifest 使用检出后的 CRLF 字节，使干净 clone 与本地审查校验一致。

发布前必须重新执行策略/元数据检查、忽略规则正反例、私密路径与基础凭据扫描、manifest 全项核验、`./gradlew gate1Check test --rerun-tasks --console=plain`，并检查生产 bootJar 不含测试 probe。通过后才创建上述两个提交并发布 Draft PR。实际命令结果、commit/PR 链接及 CI 状态补记 Issue #12；没有将未来远端 CI 或团队审查提前记为通过。

## 以下为首次审计的历史快照

以下 123/104/16/81 等数字只描述首次审计时点，不代表最终提交范围；当前范围与 manifest 数量以上表为准。保留其已有/新增/混合分类及证据限制，避免伪造 pre-Gate 1 历史。

## 结论与证据限制

本地 `main` 没有任何提交，index 为空；`origin` 是 `https://github.com/BRMRs/group33.git`。只读 GitHub 检查显示仓库为 public、branches 为空、没有 open PR。因此所有现有文件在 Git 中都是未跟踪，不能拿 Git diff 伪造“Gate 1 前”的历史。

以下“已有／新增／修改”是相对于上一轮 **Gate 1 契约代码实施开始前的工作区**，依据本对话的实际读写记录分类；不是 Git 的 A/M 状态，也不判断文件最初作者。时间戳不用来推断贡献。已有文件内的本轮改动与原内容分开说明，但没有可靠完整的 pre-Gate 1 文件快照，不能声称已重建逐行历史 diff。

| 分类 | 文件数 | 建议 |
|---|---:|---|
| 已有工程／规范／骨架，本轮 Gate 1 未修改 | 74 | 纳入首次工程交付；其中 3 个基础配置可先作仓库初始化 |
| Gate 1 新增代码／测试／审查文档 | 14 | 纳入 Gate 1 PR |
| 已有文件内的 Gate 1 修改 | 18 | 当前完整文件纳入 PR，明确混合归属，不把全文计为新写 |
| 独立浏览器原型与照片 | 16 | 本次不提交，保留本地，后续单独处理 |
| 本次审计报告 | 1 | 如确认范围，随 PR 纳入；不属于 Gate 1 业务或测试代码 |
| 合计（本报告创建后） | 123 | 初始提交 3 + 后续 PR 104 + 本轮排除 16 |

创建本报告前非忽略文件为 122 个，约 7.18 MB；排除原型后工程候选为 106 个、470,531 bytes（不含本报告）。另有 2,536 个已忽略文件，主要是缓存／构建产物，不纳入上述计数。文件数是此次快照；发布前需再次检查，不能用数字替代白名单。

## 首次建议的提交与 PR 顺序（历史）

### 1. 最小仓库初始化：3 个基础配置文件

仅包含：

- [.editorconfig](../.editorconfig)
- [.gitattributes](../.gitattributes)
- [.gitignore](../.gitignore)

这一步只建立 `main` 的共同祖先，不声称建立可运行应用基线。README、CI、Gradle 工程和应用代码在下一步 PR 一起进入，所以初始化这一刻没有应用可构建，也没有缺文件的 CI 被开启。若选择先加简短初始化 README，其临时内容必须另经确认，不能擅自覆盖现有 README。

### 2. 首个工程＋Gate 1 PR：104 个文件（按本次快照）

从上面最小 `main` 建立功能分支，纳入“已有内容去掉 3 个基础配置”71 个、Gate 1 新增 14 个、Gate 1 修改 18 个，以及本报告 1 个。总计 104。实际发布前再次确认分支命名、文件清单与远端状态。

该 PR 会包含原有工程和新契约，因为仓库以前没有公开源码基线；GitHub 中这些文件都会显示为新增。PR 正文应使用本报告区分来源，**不能描述成 104 个文件都是本轮 Gate 1 新写，也不能将通用 agent 代码归给六位成员**。将 Gate 1 源码、规范、构建、CI 和验证证据放在同一个可审查 PR，随后保留六角色正式审查和 Android/Server 同版签署。

另一种做法是先提交“完整旧工程基线”，再单独做 Gate 1 diff，但必须先取得可靠旧文件快照；当前混合文件没有完整 Git 历史，不能靠删除当前代码或猜测恢复来凑该基线。若你希望采用这种分法，应先指定可用的旧快照。当前推荐上面的 3 文件初始化 + 完整首个 PR。

### 3. 另行处理原型，不混入 Gate 1

整个 `android-app/prototypes/due-reminder-prototype/`（16 文件、6,713,532 bytes）排除本轮。它包含独立 A/B/C 浏览器实验、研究说明、6 张药盒照片和 2 张预览。原型 README/C_RESEARCH 本身区分其模拟行为与正式契约；这里只隔离提交范围，不修改或评价它的设计结论。照片来源说明保留在本地原型中，之后独立审查，不因这次 Gate 1 获得发布或产品采纳的默认许可。

排除不等于删除；不用本轮 Git commit 改写它的作者或贡献。

## 敏感信息、产物和例外核对

### 不能提交

- `.git/` 元数据、凭据配置、远端认证资料。
- `.gradle/`、所有 `build/`、`bin/`、IDE 缓存、`.DS_Store`。
- 本地 `.env`/`.env.*`、`local.properties`、真实 JWT/Supabase/Render/数据库凭据。
- 签名材料 `*.jks`、`*.keystore`、`*.p12`、`*.pfx`、私钥 `*.pem`/`*.key`。
- 本地业务数据库、Room/SQLite 文件、`server/data/`、真实用户照片或记录、堆转储。
- APK/AAB/JAR 构建产物、测试报告、运行日志；它们应作为本地验证或后续 CI artifact，不作为源码。
- 本轮原型目录及其照片／预览，按上面的范围排除，而不是把它们统称为秘密文件。

### 可以纳入的明确例外

- `gradle/wrapper/gradle-wrapper.jar` 是构建引导文件，**不是应排除的应用产物**。大小 43,504 bytes，本地 SHA-256 与 [Gradle 8.9 wrapper 官方摘要](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256) 一致：

  `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`

- `gradlew` 当前为可执行模式，提交时保留执行位；`gradlew.bat` 与 wrapper properties 一起保留。
- `docs/contracts/examples.json` 是虚构演示 fixture，已被实际契约测试读取，不等于真实用户数据。
- `AGENTS.md`/`docs/agents/` 是项目协作规则，可以保留；不是个人机器配置。
- `application.yml` 目前通过 `JWT_SECRET` 提供外部配置，fallback 被明确标注为本地开发值。人工复核判定它是公开示例占位，不是发现了实际云端密钥。可保留作开发配置，**不能以此批准共享/正式部署使用默认值**；README 已要求外部设置密钥。没有将占位值复制到本报告。

### 已执行检查与局限

- 对 122 个非忽略文件中的 113 个文本文件做基础模式扫描：私钥块、常见 GitHub/AWS/Supabase token、字面 JWT、带密码 URL、可疑字符串赋值；未发现高置信真实凭据匹配。文件名检查未发现待提交的签名文件／本地数据库；未发现文本中的个人绝对主目录路径。
- 单独人工复核 Server JWT 配置、README、debug 明文 HTTP 配置和 CI。debug 明文网络设置仅在 `src/debug`，不属于泄漏的秘密；没有改动它。CI 的 `permissions.contents` 为 read，未发现本轮需要公开真实凭据的配置。
- `gitleaks` 在当前环境未提供；基础规则与人工抽样不是全面秘密／安全／隐私审计。不得将“无匹配”写成“保证没有秘密”。原型图片没有因本次扫描而被认定为可提交或无隐私风险。
- 发布前保持精确路径白名单；不使用 `git add .`/`git add -A` 扫入整个工作区。遇到新文件或已变化文件需重新归类。

## 首次建议的两项发布前硬化（历史，现已获准并实施）

这两项 **仅提出，未修改文件**；若一起批准，会是本次范围审计后的新改动，不冒充上一轮 Gate 1 原有变更。

1. 在 `.gitignore` 补充签名／私钥、本地数据库、APK/AAB/堆转储等规则。实测现有规则忽略 `.env`、`local.properties`、`server/data/`，但尚不忽略任意位置的示例 `sample.jks`/`sample.keystore`。不新增 blanket `*.jar` 规则，以免误排除 wrapper；未来若需可公开测试 key/db fixture，须显式审查。
2. 在 `gradle/wrapper/gradle-wrapper.properties` 补 `distributionSha256Sum`，固定现有 Gradle 8.9 分发，不升级版本。当前未设置该属性；[官方分发摘要](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256) 为：

   `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`

之后更新受影响的 `GATE1_ARTIFACTS.sha256`，再次运行 `./gradlew gate1Check test`。若更改审查包中的“没有提交／PR”状态，也需重新生成 manifest。当前 81 项旧 manifest 全部通过，不能在修改文件后继续引用旧校验通过结果。

## Gate 1 文件明细

### B. 相对 Gate 1 实施开始前新增：14 个

| 路径 | 本轮交付 |
|---|---|
| [shared/src/main/kotlin/com/group33/shared/contract/ContractProtocol.kt](../shared/src/main/kotlin/com/group33/shared/contract/ContractProtocol.kt) | Shared P0 可序列化契约／协议配置 |
| [shared/src/main/kotlin/com/group33/shared/contract/DoseContracts.kt](../shared/src/main/kotlin/com/group33/shared/contract/DoseContracts.kt) | Shared P0 可序列化契约／协议配置 |
| [shared/src/main/kotlin/com/group33/shared/contract/IdentityContracts.kt](../shared/src/main/kotlin/com/group33/shared/contract/IdentityContracts.kt) | Shared P0 可序列化契约／协议配置 |
| [shared/src/main/kotlin/com/group33/shared/contract/PlanContracts.kt](../shared/src/main/kotlin/com/group33/shared/contract/PlanContracts.kt) | Shared P0 可序列化契约／协议配置 |
| [shared/src/main/kotlin/com/group33/shared/contract/SyncContracts.kt](../shared/src/main/kotlin/com/group33/shared/contract/SyncContracts.kt) | Shared P0 可序列化契约／协议配置 |
| [shared/src/test/kotlin/com/group33/shared/contract/WireContractTest.kt](../shared/src/test/kotlin/com/group33/shared/contract/WireContractTest.kt) | 实际契约／跨端／接口调用测试 |
| [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositories.kt) | 九个功能／恢复接口 |
| [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryModels.kt) | Android-only 类型、scope、结果和照片抽象 |
| [android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt](../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/FeatureRepositoryHarnessTest.kt) | 实际契约／跨端／接口调用测试 |
| [android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryContractsTest.kt](../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/contract/RepositoryContractsTest.kt) | 实际契约／跨端／接口调用测试 |
| [server/src/main/kotlin/com/group33/server/config/ContractJacksonConfiguration.kt](../server/src/main/kotlin/com/group33/server/config/ContractJacksonConfiguration.kt) | Server 严格反序列化配置 |
| [server/src/test/kotlin/com/group33/server/contract/WireTransportContractTest.kt](../server/src/test/kotlin/com/group33/server/contract/WireTransportContractTest.kt) | 实际契约／跨端／接口调用测试 |
| [docs/contracts/GATE1_REVIEW_PACKET.md](../docs/contracts/GATE1_REVIEW_PACKET.md) | 本地证据、覆盖边界和六角色审查包 |
| [docs/contracts/GATE1_ARTIFACTS.sha256](../docs/contracts/GATE1_ARTIFACTS.sha256) | 审查输入的校验清单（首次 81，当前 113），不是改动／作者归属列表 |

### C. 已有文件内新增／修改：18 个

| 路径 | 仅本轮改动范围 |
|---|---|
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | CI 命令加入 gate1Check，保留 test。 |
| [android-app/build.gradle.kts](../android-app/build.gradle.kts) | 契约测试 JSON 依赖、共享 fixture 资源和测试资源目录参数；原有 desugaring 不是本轮新增。 |
| [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/ApiErrorMessages.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/ApiErrorMessages.kt) | 补 P0 稳定错误码到本地化资源的映射。 |
| [android-app/src/main/res/values/strings.xml](../android-app/src/main/res/values/strings.xml) | 新增 Gate 1 P0 错误英文文案。 |
| [android-app/src/main/res/values-zh/strings.xml](../android-app/src/main/res/values-zh/strings.xml) | 新增对应简体中文错误文案。 |
| [android-app/src/test/kotlin/com/group33/medicationreminder/core/data/ApiErrorMessagesTest.kt](../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/ApiErrorMessagesTest.kt) | 在原有测试上补 P0 code/两套语言资源的检查。 |
| [build.gradle.kts](../build.gradle.kts) | 新增 gate1Check 聚合验证 task。 |
| [server/build.gradle.kts](../server/build.gradle.kts) | 固定到现有版本目录的 serialization BOM；增加测试 JSON 依赖和共享 fixture。 |
| [server/src/main/kotlin/com/group33/server/web/ApiErrors.kt](../server/src/main/kotlin/com/group33/server/web/ApiErrors.kt) | 只增缺必需 header 的结构化 400；此前的通用错误处理不归本轮新建。 |
| [shared/build.gradle.kts](../shared/build.gradle.kts) | 把同一 examples.json 注入测试资源。 |
| [docs/API_CONTRACT.md](../docs/API_CONTRACT.md) | 加入已编译契约、传输严格性和实际测试状态；不把拟议业务路由写成已实现。 |
| [docs/IMPLEMENTATION_HANDOFF.md](../docs/IMPLEMENTATION_HANDOFF.md) | 补 Gate 1 实际交付、审查包入口和账号映射暂缓说明。 |
| [docs/contracts/SHARED_MODELS.md](../docs/contracts/SHARED_MODELS.md) | 模型由文档推进为实际声明，补类型索引和仍待实现的持久化边界。 |
| [docs/contracts/REPOSITORY_INTERFACES.md](../docs/contracts/REPOSITORY_INTERFACES.md) | 同步九个已编译接口、harness 与真实适配器的区别。 |
| [docs/contracts/CONTRACT_TEST_PLAN.md](../docs/contracts/CONTRACT_TEST_PLAN.md) | 记录实际测试与下游 P0 33 项场景的覆盖边界。 |
| [docs/contracts/SUBMISSION_MATRIX.md](../docs/contracts/SUBMISSION_MATRIX.md) | 保留六角色贡献/正式审查，记录通用代码已交付及账号映射暂缓。 |
| [docs/contracts/REVIEW.md](../docs/contracts/REVIEW.md) | 追加本轮检查、依赖冲突修正和正式审查尚未完成的事实。 |
| [docs/contracts/TECHNICAL_RECOMMENDATIONS.md](../docs/contracts/TECHNICAL_RECOMMENDATIONS.md) | 加后续代码进展入口，原批准记录不重开。 |

### A. 已有内容、本轮 Gate 1 未修改：74 个

这些内容此前就在本地工作区（包含更早的规范整理和骨架修正），尚未在 Git 中发布；“已有”不等于已经合并到 main，也不自动说明是哪位同学完成。

- [.editorconfig](../.editorconfig)
- [.gitattributes](../.gitattributes)
- [.github/ISSUE_TEMPLATE/bug.yml](../.github/ISSUE_TEMPLATE/bug.yml)
- [.github/ISSUE_TEMPLATE/config.yml](../.github/ISSUE_TEMPLATE/config.yml)
- [.github/ISSUE_TEMPLATE/feature.yml](../.github/ISSUE_TEMPLATE/feature.yml)
- [.github/dependabot.yml](../.github/dependabot.yml)
- [.github/pull_request_template.md](../.github/pull_request_template.md)
- [.gitignore](../.gitignore)
- [AGENTS.md](../AGENTS.md)
- [CONTRIBUTING.md](../CONTRIBUTING.md)
- [CONTRIBUTIONS.md](../CONTRIBUTIONS.md)
- [GLOSSARY.md](../GLOSSARY.md)
- [README.md](../README.md)
- [android-app/proguard-rules.pro](../android-app/proguard-rules.pro)
- [android-app/src/debug/AndroidManifest.xml](../android-app/src/debug/AndroidManifest.xml)
- [android-app/src/main/AndroidManifest.xml](../android-app/src/main/AndroidManifest.xml)
- [android-app/src/main/kotlin/com/group33/medicationreminder/MainActivity.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/MainActivity.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHomeActivity.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHomeActivity.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHomeViewModel.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHomeViewModel.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/DemoPreview.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/DemoPreview.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/InMemoryMedicationRepository.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/InMemoryMedicationRepository.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/MedicationRepository.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/MedicationRepository.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/core/data/RepositoryProvider.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/core/data/RepositoryProvider.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderHomeActivity.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderHomeActivity.kt)
- [android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderHomeViewModel.kt](../android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderHomeViewModel.kt)
- [android-app/src/main/res/layout/activity_caregiver_home.xml](../android-app/src/main/res/layout/activity_caregiver_home.xml)
- [android-app/src/main/res/layout/activity_elder_home.xml](../android-app/src/main/res/layout/activity_elder_home.xml)
- [android-app/src/main/res/layout/activity_main.xml](../android-app/src/main/res/layout/activity_main.xml)
- [android-app/src/main/res/values-v29/themes.xml](../android-app/src/main/res/values-v29/themes.xml)
- [android-app/src/main/res/values/themes.xml](../android-app/src/main/res/values/themes.xml)
- [android-app/src/test/kotlin/com/group33/medicationreminder/PreviewViewModelTest.kt](../android-app/src/test/kotlin/com/group33/medicationreminder/PreviewViewModelTest.kt)
- [android-app/src/test/kotlin/com/group33/medicationreminder/core/data/InMemoryMedicationRepositoryTest.kt](../android-app/src/test/kotlin/com/group33/medicationreminder/core/data/InMemoryMedicationRepositoryTest.kt)
- [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)
- [docs/SCOPE_AND_ROADMAP.md](../docs/SCOPE_AND_ROADMAP.md)
- [docs/TEAM_BRIEFING.md](../docs/TEAM_BRIEFING.md)
- [docs/UI_DESIGN_DECISION.md](../docs/UI_DESIGN_DECISION.md)
- [docs/adr/0001-explicit-pairing-authorization.md](../docs/adr/0001-explicit-pairing-authorization.md)
- [docs/adr/0002-offline-first-local-reminders.md](../docs/adr/0002-offline-first-local-reminders.md)
- [docs/adr/0003-elder-time-zone-and-dose-history.md](../docs/adr/0003-elder-time-zone-and-dose-history.md)
- [docs/adr/0004-hosting-storage-and-explicit-sync.md](../docs/adr/0004-hosting-storage-and-explicit-sync.md)
- [docs/adr/0005-occurrence-generation-and-time-resolution.md](../docs/adr/0005-occurrence-generation-and-time-resolution.md)
- [docs/adr/0006-cloud-independent-demonstration-fallback.md](../docs/adr/0006-cloud-independent-demonstration-fallback.md)
- [docs/agents/domain.md](../docs/agents/domain.md)
- [docs/agents/issue-tracker.md](../docs/agents/issue-tracker.md)
- [docs/agents/triage-labels.md](../docs/agents/triage-labels.md)
- [docs/contracts/examples.json](../docs/contracts/examples.json)
- [gradle.properties](../gradle.properties)
- [gradle/libs.versions.toml](../gradle/libs.versions.toml)
- [gradle/wrapper/gradle-wrapper.jar](../gradle/wrapper/gradle-wrapper.jar)
- [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties)
- [gradlew](../gradlew)
- [gradlew.bat](../gradlew.bat)
- [server/src/main/kotlin/com/group33/server/MedicationReminderServerApplication.kt](../server/src/main/kotlin/com/group33/server/MedicationReminderServerApplication.kt)
- [server/src/main/kotlin/com/group33/server/ScheduleStore.kt](../server/src/main/kotlin/com/group33/server/ScheduleStore.kt)
- [server/src/main/kotlin/com/group33/server/auth/AnonymousUser.kt](../server/src/main/kotlin/com/group33/server/auth/AnonymousUser.kt)
- [server/src/main/kotlin/com/group33/server/auth/AnonymousUserStore.kt](../server/src/main/kotlin/com/group33/server/auth/AnonymousUserStore.kt)
- [server/src/main/kotlin/com/group33/server/auth/AuthController.kt](../server/src/main/kotlin/com/group33/server/auth/AuthController.kt)
- [server/src/main/kotlin/com/group33/server/auth/AuthService.kt](../server/src/main/kotlin/com/group33/server/auth/AuthService.kt)
- [server/src/main/kotlin/com/group33/server/auth/CareRelationshipAccess.kt](../server/src/main/kotlin/com/group33/server/auth/CareRelationshipAccess.kt)
- [server/src/main/kotlin/com/group33/server/auth/JwtService.kt](../server/src/main/kotlin/com/group33/server/auth/JwtService.kt)
- [server/src/main/kotlin/com/group33/server/config/ApiSecurityHandlers.kt](../server/src/main/kotlin/com/group33/server/config/ApiSecurityHandlers.kt)
- [server/src/main/kotlin/com/group33/server/config/SecurityConfig.kt](../server/src/main/kotlin/com/group33/server/config/SecurityConfig.kt)
- [server/src/main/kotlin/com/group33/server/web/HealthController.kt](../server/src/main/kotlin/com/group33/server/web/HealthController.kt)
- [server/src/main/kotlin/com/group33/server/web/ScheduleController.kt](../server/src/main/kotlin/com/group33/server/web/ScheduleController.kt)
- [server/src/main/resources/application.yml](../server/src/main/resources/application.yml)
- [server/src/test/kotlin/com/group33/server/ApplicationTest.kt](../server/src/test/kotlin/com/group33/server/ApplicationTest.kt)
- [server/src/test/kotlin/com/group33/server/ScheduleAuthorizationTest.kt](../server/src/test/kotlin/com/group33/server/ScheduleAuthorizationTest.kt)
- [settings.gradle.kts](../settings.gradle.kts)
- [shared/src/main/kotlin/com/group33/shared/model/ApiModels.kt](../shared/src/main/kotlin/com/group33/shared/model/ApiModels.kt)
- [shared/src/main/kotlin/com/group33/shared/model/LegacyScheduleRules.kt](../shared/src/main/kotlin/com/group33/shared/model/LegacyScheduleRules.kt)
- [shared/src/main/kotlin/com/group33/shared/model/MedicationModels.kt](../shared/src/main/kotlin/com/group33/shared/model/MedicationModels.kt)
- [shared/src/main/kotlin/com/group33/shared/model/UserModels.kt](../shared/src/main/kotlin/com/group33/shared/model/UserModels.kt)
- [shared/src/test/kotlin/com/group33/shared/model/ApiErrorSerializationTest.kt](../shared/src/test/kotlin/com/group33/shared/model/ApiErrorSerializationTest.kt)
- [shared/src/test/kotlin/com/group33/shared/model/LegacyScheduleRulesTest.kt](../shared/src/test/kotlin/com/group33/shared/model/LegacyScheduleRulesTest.kt)

### D. 首次排除的原型：16 个（当前整个目录 19 个，均保留本地）

- `android-app/prototypes/due-reminder-prototype/C_RESEARCH.md`
- `android-app/prototypes/due-reminder-prototype/README.md`
- `android-app/prototypes/due-reminder-prototype/assets/box-a.jpg`
- `android-app/prototypes/due-reminder-prototype/assets/box-b.jpg`
- `android-app/prototypes/due-reminder-prototype/assets/box-c.jpg`
- `android-app/prototypes/due-reminder-prototype/assets/box-d.jpg`
- `android-app/prototypes/due-reminder-prototype/assets/box-e.jpg`
- `android-app/prototypes/due-reminder-prototype/assets/box-f.jpg`
- `android-app/prototypes/due-reminder-prototype/c-prototype.css`
- `android-app/prototypes/due-reminder-prototype/c-prototype.js`
- `android-app/prototypes/due-reminder-prototype/copy.js`
- `android-app/prototypes/due-reminder-prototype/index.html`
- `android-app/prototypes/due-reminder-prototype/preview-b.jpg`
- `android-app/prototypes/due-reminder-prototype/preview-c.jpg`
- `android-app/prototypes/due-reminder-prototype/prototype.css`
- `android-app/prototypes/due-reminder-prototype/prototype.js`

另增加但同样排除：`android-app/prototypes/due-reminder-prototype/C_V2_NOTES.md`、`c-v2.css`、`c-v2.js`（同目录）。

### E. 本次审计新增

- `docs/INITIAL_SUBMISSION_SCOPE.md`（本报告）是提交前审计说明，不计作 Gate 1 功能或成员代码产出。

## 发布与审查交接

当前授权与最终白名单见文首。后续文件变化必须重新归类，不自动扩大范围；不删除原型、不修改业务功能、不虚构成员归属。本次 Draft PR 不等于 Gate 1 完成。六人正式审查、每人真实代码/测试 PR、具名 Android/Server 同版签署均保留；#12 保持 OPEN，负责人 BRMRs 不变。
