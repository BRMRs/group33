# T01–T07 技术决定记录（保留未被 V2 替换的决定）

**现行产品版本为 [ELDER_REMINDER_V2 / ADR-0008](../adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md)。** 2026-10-17明确保留两小时响应窗口，增加独立三十分钟未确认通知及T−5/T+5两次连接检查；r1的两小时字段/断言相符，但不能证明新增闭环。后续代码/JSON/测试按 [V2 技术清单](../ELDER_UI_TECHNICAL_REVIEW.md) 协调迁移。旧批准记录是历史，不允许继续采用三十分钟退出、全天心跳或长者同步负担、禁止照护者独立补登记或刷新代替告警。

后续 Gate 1 代码/测试进展见 [实际交付与审查包](GATE1_REVIEW_PACKET.md)。本记录中的“仅同步文档/不写业务代码”描述原批准同步轮次；后来经项目负责人授权补齐了契约声明和测试，但仍未实现下游具体业务功能，也不代替六人正式审查。

对应 [共享契约、Repository 接口和契约测试 Issue](https://github.com/BRMRs/group33/issues/12) 及 [双视角审查记录](REVIEW.md)。项目负责人于 **2026-10-16** 明确批准全部七项，版本 **`T01-T07/2026-10-16-r1`**，特别确认 T02 长者端单写生成与滚动 24 小时窗口、T07 完全不依赖云服务的本地备用验收。本文件保留选择理由和代价，已同步 API、Shared 模型、Repository 和测试方案；不是代码交付或六人正式签署。

既定产品规则继续有效：显式长者同意、关系授权、每日计划、长者执行时区、两小时响应窗口与三十分钟独立未确认通知、十分钟稍后提醒、已生成事件原意保留、及时离线报告对账、三类状态分离、双语/无障碍、Render/Supabase/Room 及本地备用部署。新增求助/非回应跟进、照护者补登记与T−5/T+5两次失败连接告警（无全天监测）属于当前版本必需能力；时点已定；具体探测响应等待上限/机制及通知渠道仍需技术审查，不能改动已定时点或将闭环退回 P1。

批准与同步追踪：[Issue 12 确认记录](https://github.com/BRMRs/group33/issues/12#issuecomment-6216776752)。ADR-0005/0006 分别记录单写生成/冻结时间与完全断云备用的长期取舍。

## 决策入口

| 项目 | 已确认方案摘要 | 负责人批准 | 后续正式共同审查 |
|---|---|---|---|
| T01 时间 | gap 按跳时长度向前平移；fold 取较早发生的一次；响应窗口用左闭右开区间 | 已确认 | C2/E/S1/S2 |
| T02 发生项 | 长者安装作为实际发生项生成单写端；先实例化未来 24 小时，离线继续滚动；已生成事件不改写 | 已确认 | E/C2/S1/S2 |
| T03 overlap | 同一 Medication 同一发生点的重复才算冲突；不同 Medication 同时提醒允许 | 已确认 | C2/S2/S1 |
| T04 配对参数 | 8 位数字、10 分钟、继承过期时间、单个待处理请求、基础限流 | 已确认（含配置默认值） | C1/E/S2 |
| T05 幂等 | 规范化请求指纹；项目期保留已接受凭证；同键执行中返回可重试 409 | 已确认（含配置默认值） | S1/S2 + 功能作者 |
| T06 照片 | JPEG/PNG、5 MiB、私有对象、staging 后发布 READY、持久保存未完成上传源 | 已确认（含配置默认值） | E/C2/S2/S1 |
| T07 快照/备用 | 一致性完整快照；历史默认 30 天、预览 7 天；本地 Spring Boot + PostgreSQL + 私有文件存储 | 已确认 | C3/S1/S2/E |

原待确认 T01–T07 中未被 ADR-0008 替换的选择仍有效；新协议迁移按 V2-T01–T05 审查，不重开已定产品行为。技术负责人仍须审查实现正确性、参数配置与可执行证据。六个角色分别提交契约/模型/映射代码及相关测试，Android/Server 对同一版本正式签署，不能被项目负责人的方案批准替代。

## T01：DST、deadline 和跨端时间解析

**已确认方案**

- 保存用户输入的 nominal local date/time、IANA zone 和解析后的 `plannedAtEpochMillis`，不能只保存 UTC 或手机当前时区。
- 正常日期直接解析；gap 中不存在的本地时间向前平移整个跳时长度；fold 中重复的本地时间只选择较早的那个 instant。保留原输入和最终 offset，不产生第二个 occurrence。
- 响应窗口定义为 `[plannedAt, plannedAt + 7_200_000ms)`：deadline 前 1ms 为及时；恰好 deadline 及之后属于 Late taken report，仍保留 Missed；在 Due 前提交动作按既定状态规则拒绝。三十分钟只通知照护者，长者仍可操作；两小时截止才撤下旧闹钟/照片/操作；当前长者界面不提供过窗报告入口，遗留/延迟证据仍保留。
- 两小时响应窗口和三十分钟通知均按真实经过的毫秒计，不按可能重复/跳跃的墙上钟表小时计。已有事件的 instant/deadline 不因系统时区数据库更新而重算。
- Remind later 固定从本地 recordedAt 延后 600,000ms；若目标时间恰好达到或超过 deadline，窗口到期优先，不缩短延迟、不再弹过期提醒，未有及时 Taken 时进入 Missed。仅目标严格早于 deadline 时设置 snoozedUntil；否则为 null，UI 明示窗口先结束。依据已接受 Issue #4，补充 D03/O01 边界夹具；迟到同步不重启十分钟计时。
- Server JDK 17 与 Android 采用同一显式解析算法；Android API 23 所需 `java.time` 支持通过 core-library desugaring 配置并验证。记录 `resolutionPolicyVersion`、`resolvedOffsetSeconds`；同一个算法不能被当成时区数据库版本必然相同的保证。跨端差异应显式对账，不能静默移动已生成事件或丢掉本地报告。

**理由与取舍**：此 gap/fold 选择与 Java 常见解析行为接近，容易固定测试向量；代价是 gap 当天可能在不同墙上时间提醒。Java 明确区分 gap/overlap 并提供相应处理，Android 对旧平台的 API 支持需要单独配置。[Java 17 时间解析](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/ZonedDateTime.html)、[Android API desugaring](https://developer.android.com/studio/write/java8-support)。这些文档支持平台事实；本项目策略的批准来源是本轮项目负责人的明确确认。

**对契约的影响**

| API | 模型 / Repository | 测试 |
|---|---|---|
| 无需新业务路由；动作及发生项上报携带冻结解析上下文，Server 验证原确认计划 | occurrence/生成证据增加解析策略版本和 offset；计划仍使用 IANA zone，历史 snapshot 不重写 | 扩展 D01/D03/D04/S07：正常、gap、fold、deadline−1ms、deadline、deadline+1ms；在 API 23/35 与 Server 跑同一 golden vectors |

可审查向量：`America/New_York` 的 `2026-03-08 02:30` 解析到 `03:30 -04:00`（`07:30Z`）；`2026-11-01 01:30` 取 `-04:00`（`05:30Z`），只创建一次。解析信息可在实例详情中解释，不额外增加“已知且相同时区”的阻塞确认。

**确认记录**：本项已由项目负责人按本方案批准（2026-10-16）；后续只审查实现与测试，不重新询问该选择。

## T02：发生项生成、下载窗口及旧版本对账

**已确认方案**

1. 区分“由计划计算出的预览”与“已经真实实例化并持久化的 Dose event”。预览不是已生成事件、不进入服药历史；已有 SCHEDULED/DUE/SNOOZED 等真实事件不能为了套新计划而被重命名成预览。
2. P0 每位长者只有一个执行安装，由它作为实际 occurrence 的生成单写端；Server 仍是已接受业务数据与凭证的权威保存方。Server 可计算预览，但不让独立计算的预览抢占 canonical event 身份。
3. 实例化未来 **24 小时**的真实事件，随着执行、恢复和本地调度滚动补齐；生成不等待网络。计划预览可看未来 7 天，但它不是七天不可变 Dose event 批量生成。24 小时是实现窗口，不能变成“离线超过 24 小时就停止提醒”的产品限制。
4. Room 将发生项及冻结上下文原子保存：`generationId`、`OccurrenceKey`、`planRevision`、生成时间、原计划 instant/offset、策略版本，以及来源确认快照信息。生成时间沿用 P0 对应用本机时间的信任假设，不宣称防篡改证明。
5. 在线同步时向 Server 注册这些真实发生项；离线时后续 Taken/Remind later 携带同一生成证据。即使独立注册尚未成功，动作提交也应能原子注册发生项并接受动作。
6. Server 校验认证长者、所属计划、曾确认版本、日期/时间和冻结证据。唯一键仍为 `(planId, localDate, localTime)`，不加 revision；重复注册返回同一事件。旧 revision 本身不是拒绝理由。
7. 已生成事件继续使用旧 snapshot；收到新计划后，仅未生成部分使用新 revision。与已存在真实 occurrence 的证据矛盾时保留本地报告为 Sync needs attention 并供照护者/诊断定位冲突，不向长者日常页面增加同步任务，不使用“谁先联网谁覆盖历史”。

**理由与取舍**：单写端避免 Server 与离线长者各自创建不同版本的同一个发生项；24 小时减少提前冻结过多未来事件的概率。代价是要增加生成证据、注册与重试协议。

**已接受的代价**：如果明早的 SCHEDULED 事件已经实例化，今晚修改计划不能覆盖它；编辑作用于尚未生成的部分。界面要区分“计划已保存”“长者尚未同步”“某发生项保留原版本”，给出实际生效的实例说明。这是遵守既有不可变约束的结果，不是本轮新增“随时覆盖 Scheduled”的权限。

**对契约的影响**

| API | 模型 / Repository | 测试 |
|---|---|---|
| 增加 Elder-only `POST /elders/{elderId}/occurrence-registrations`（批量、幂等）；`dose-actions` 支持内联相同生成证据 | `OccurrenceGenerationEvidence`、`OccurrenceRegistrationRequest`/`Response`/`Receipt` 和 `DoseActionRequest.generationEvidence`，具体名称/字段以 SHARED_MODELS 为准；Room 保留生成记录和原 revision；S1 公共调度、E/C2 功能作者共同对接，S2 做注册/动作事务 | 扩展 D05/S03/S06/S07/S08：旧版离线生成→新版计划保存→旧版及时报告；注册与动作任意到达顺序；重复注册；跨日/重启滚动补齐；矛盾证据不改历史 |

**确认记录**：本项已由项目负责人按本方案批准（2026-10-16）；后续只审查实现与测试，不重新询问该选择。

## T03：overlap 的精确定义

**已确认方案**

- 只检测同一长者、同一 `medicationId`、活动计划之间的重复发生点；日期范围相交还不够，必须在已确认时区解析后有相同 planned instant。
- 一个计划内重复输入相同 local time 直接校验失败；DST 解析令同 Medication 两个 nominal times 落在同一 instant 时也应作为重复发生点处理，不能静默生成两条。
- 不同 `medicationId` 在同一时间提醒允许；药名文本相同不自动认定为同一药品，系统不做药物知识推断。
- 不增加“两次提醒至少间隔两小时”等规则；响应窗口不是药物安全间隔。
- 更新时排除该计划自己的旧 revision，停用计划不占据新的计划配置冲突；但保留的真实事件仍需按 T02 防止重复。开放末日与 DST 的重复检查不能只扫描当前下载窗口后宣称永远无冲突；实现评审必须补全范围/时区规则算法和反例，不能仅凭这个谓词声称验证器已完成。

**理由**：既避免意外重复，又保留同时服用不同药品的正常提醒能力，不让校验变成用药建议。

| API | 模型 / Repository | 测试 |
|---|---|---|
| `422 OVERLAPPING_PLAN`，仅向已授权调用者返回具体 field/reason；保存原子失败 | 不新增全局药品身份；按现有 elderId/medicationId、日期、时区和计划 revision 校验 | 扩展 D06/P03：同药同点拒绝、不同药同点允许、同药不同时间允许、起止日包含边界、自己的旧版本不误冲突、DST 同点重复 |

**确认记录**：本项已由项目负责人按本方案批准（2026-10-16）；后续只审查实现与测试，不重新询问该选择。

## T04：配对码、并发、限流和终态保留

**已确认的可配置默认值**

| 参数 | 已批准默认 | 原因 |
|---|---|---|
| code | 安全随机生成的 8 位数字字符串，保留前导零 | 手动输入简单，又比更短数字码有更大的空间；不是密码或授权凭证 |
| 有效期 | 10 分钟；pending request 继承原 expiry，不因输入而延长 | 留出两设备操作时间，又不形成长期邀请 |
| pending | 首个有效请求原子消费此 code 并建立唯一 pending；后续新请求需新的 code，拒绝/取消不重新开放旧码 | 保持既定 single-use 约束，减少多个不易区分的请求 |
| 尝试限制 | 每 installation 每分钟 5 次；每 IP 每分钟 20 次；长者生成码每分钟 3 次 | P0 可测试、可调的误用/穷举节流，不锁死被猜测长者的既有关系 |
| 终态列表 | 30 天可查询的最小请求元数据；保留更小去重/失效凭据按 T05 | 支持恢复和排查，同时给列表有界保留期 |

Server 使用过期 instant 判断，客户端倒计时仅显示。code 生成/占用/accept/reject/cancel/replace 在同一关系事务约束内处理；并发 accept 和 revoke/cancel 必须按状态转换串行化，不能生成第二个活动关系。accept 先提交后再 revoke 可依序成功，但最终必须是 REVOKED；cancel 先成功则后续 accept 不得成功。有效 code 的 hash 查找应唯一，随机碰撞重新生成；需要幂等回显的原码必须私有保护，不能放日志，expiry 仍由服务端检查。已消费 code 的原操作重放可以返回原终态，但不能创建新 pending 或续期。

**对契约的影响**：现有路由基本不变；`PairingCodeView.expiresAtEpochMillis`、终态 reason 和 `429 + Retry-After` 的资源文案需要固定。扩展 A02/A03/A05：过期瞬间、前导零、原请求重放不续命、两次并发接受、取消/接受竞态、限流恢复及独立关系不受影响。

**确认记录**：本项及所列默认值已获项目负责人批准（2026-10-16）；对应角色保留正式实现审查与测试职责。

## T05：幂等规范、执行中响应、拒绝与重试

**已确认方案**

- 命令上下文使用一次逻辑意图一个随机 UUID；actor、environment、operation、target 和规范化 payload 绑定。重新点按钮不自动换 key；新的明确用户意图才创建新 key。
- 对类型化 DTO 做 canonical 编码：对象字段顺序无关；只在字段定义明确等价时将可选缺省与 null 归一。若存在“省略表示不修改、null 表示清除”的字段，两者必须不同；必填字段缺失先按校验失败处理，不能靠归一补齐。daily localTimes 先校验无重复再排序。用户药名/剂量/说明文本保持原样，不翻译、不用 trim/Unicode 改写来偷偷合并不同输入；图片指纹包含原文件字节的 SHA-256 和受控媒体类型。
- P0 项目期不自动淘汰已接受的幂等凭证、发生项唯一键及报告证据；不要套用“24 小时后忘记所有 key”的通用模板。未来保留策略另由数据量和项目生命周期决定。
- 处理顺序保持当前草案：当前认证/授权 → accepted receipt 查找/指纹核验 → 新命令 revision/输入校验 → 业务与 accepted receipt 同事务提交。数据库唯一约束裁决同键并发。[PostgreSQL INSERT 与冲突处理](https://www.postgresql.org/docs/17/sql-insert.html)。
- 同键仍在执行时有界等待，超过等待预算返回 `409 OPERATION_IN_PROGRESS`、`retryable=true`、`Retry-After: 1`；客户端保持 AWAITING_CONFIRMATION，不作为普通不可恢复冲突。完成后再重试仍返回原凭证，无需引入新的轮询业务资源。
- 拒绝不是 accepted receipt。保存原请求身份/指纹及错误记录；环境条件恢复后的人工重试可以重评同一原请求，不凭旧拒绝记录谎报已接受。内容错误需要新的明确纠正意图，原始长者报告仍保留；同一个 key 不能换 payload。
- 自动恢复只处理未知结果/可重试传输失败，不盲目重发已明确拒绝的在线命令。上述人工重试需要 Repository 区分“原 payload、前提已修复的显式重试”和“修改内容的新意图”；尤其不能把 Elder 原始报告换成新报告。API/Repository 已同步细化 rejected/new intent 文案；这是 T05 的技术表达修正，不修改既定离线产品规则。
- `resultRef` 标记 USER/PAIRING_CODE/PAIRING_REQUEST/CARE_RELATIONSHIP/PHOTO/MEDICATION/REMINDER_PLAN/DOSE_EVENT/CAPABILITY 等受控类型和 opaque ID；旧 receipt 仅确认动作，不能覆盖新状态。

**理由**：覆盖“提交成功但断网”“进程重启”“Server 重启”“旧版本重放”，又不把所有 409/401 一律判失败。代价是要保留小型持久化 operation/receipt 记录和恢复 journal。

**对契约的影响**：增加 `OPERATION_IN_PROGRESS` 代码及 Retry-After 语义、规范化版本标记、受控 resultRef；不重开现有路由权限。扩展 W01/S01/S02/S04/S05/R02/R04：JSON 字段重排同义、localTimes 顺序同义、不同文本不同义、同 key 不同 bytes、执行中重试、accepted 后版本已变化、旧拒绝重评和缓存待刷新。

**确认记录**：本项及所列默认值已获项目负责人批准（2026-10-16）；对应角色保留正式实现审查与测试职责。

## T06：照片限制、发布事务和上传源保留

**已确认方案**

- P0 接受 JPEG/PNG，单文件最大 **5 MiB（5,242,880 bytes）**；限制解码像素预算，初始设为 20 MP 并由实际设备验证。Server 校验真实格式与可解码性，不只信扩展名和 Content-Type。
- 走当前草案的 Spring Boot multipart 上传与鉴权内容代理。Supabase 标准上传文档将不超过 6 MB 的文件作为合适场景；5 MiB 是本项目建议值，不是供应商的硬上限。[Supabase 标准上传](https://supabase.com/docs/guides/storage/uploads/standard-uploads)。本轮不引入分块上传、裁剪或图片编辑。
- 连接超时 10 秒、整次图片请求预算 120 秒，均可配置；超时不证明服务端没成功，仍使用原 operationId 恢复。超过体积/类型限制分别返回 `413 PHOTO_TOO_LARGE` / `415 PHOTO_TYPE_UNSUPPORTED`，属于本地可预检、服务端仍必须验证的错误。
- 状态采用 private STAGING → READY。先稳定绑定 upload operation 和内部 photo/object identity；上传对象并确认可用后才发布 READY 元数据，成功 receipt 与元数据事务提交。已有 READY 对象不能被新上传盲目覆盖。
- Android 首次上传前保留 app 私有可重读副本及摘要，不能只依赖临时图库引用。未接受/未知/待人工处理的上传不因缓存清理或重启被丢弃；确认 ready 且无未完成意图后才能按缓存策略清理副本。
- Server 清理阈值 24 小时，但必须同时满足未被 READY/Medication 引用、无活动上传/恢复租约且已对账。不能仅凭 createdAt 删除仍可能恢复的上传。失败对象可以暂时私有孤立，不能暴露半成品 Medication。

**理由**：规模适合 P0 两端手工照片交接，能够解释重试和跨 Storage/DB 故障；不需要把整个上传系统交给某个客户端成员单独解决。

**对契约的影响**：现有 routes 不变，补错误码、大小/媒体配置和上传 attempt 的本地保留语义；内部 staging 不冒充公开业务状态。扩展 P01–P04/R04：恰好 5 MiB、超 1 byte、伪 MIME、解码失败/像素超限、对象成功但 metadata 失败、应答丢失、源引用失效后重启恢复及清理不能误删未完成项。

**确认记录**：本项及所列默认值已获项目负责人批准（2026-10-16）；对应角色保留正式实现审查与测试职责。

## T07：完整快照、保留、能力序列和本地备用

**已确认方案**

1. 默认历史展示最近 30 天；计划预览未来 7 天。单次 snapshot 日期范围上限设为 90 天、实体总量保护上限设为 10,000；超限清楚返回 `422 SNAPSHOT_RANGE_TOO_LARGE`，不截断成看似完整的数据。它们是下载/展示范围，不是只允许离线 7 天，也不是丢弃第 31 天的报告。
2. Server 在主写库的同一只读 Repeatable Read transaction 内读取授权和相关业务表，响应有界完整 snapshot；实际写入使用其自己的事务/锁/唯一约束，不能用“快照读取隔离级别”代替写入授权并发约束。PostgreSQL 文档说明该级别的连续读取共享事务快照，这支持此建议，但不会自动实现全部业务一致性。[PostgreSQL 事务隔离](https://www.postgresql.org/docs/17/transaction-iso.html)。
3. 客户端按 environment/installation/Elder 串行同步，用 request/access generation 丢弃旧 fetch。opaque snapshotVersion 只判相等，不排序；先前写入已接受后新发出的 snapshot 必须读到该效果或更新状态。
4. P0 不自动清理业务 Room 行、生成证据和未同步/被拒绝报告；30 天只是默认视图。已同步图片 bytes 可以成为独立可回收缓存，已知撤销后不再以授权状态展示；物理删除关系缓存不是本轮新增产品要求。
5. 能力 observationSequence 和 outbox 在 Room 同事务持久化，单调递增并跨进程/重启恢复；同 report 的重试不增序号。更换安装/数据清除恢复不纳入 P0。
6. **本地备用全链路不依赖云服务**：同一 Spring Boot artifact + 本地 PostgreSQL + 私有本地文件 Storage adapter；通过 profile 注入 JDBC、Storage、JWT 和 Android API base 配置。云端仍是已选 Render + Supabase，没有替换该决定。
7. 云/本地采用相同业务 API、migration 和授权；按环境隔离会话、Room/outbox 与照片命令。备用开始一套独立的真实登录/配对/上传流程，不自动迁移云端身份和 Pending actions。预先准备依赖、镜像与 APK，演练时两设备访问同一局域网 Server。

**理由与成本**：完整快照比 delta/push 简单，读窗口有界；全本地备用能同时覆盖 Render 和 Supabase 不可达。它额外需要本地文件存储 adapter、配置和真实演练，但不要求再搭一整套本地 Supabase 控制面。

| API | 模型 / Repository | 测试 |
|---|---|---|
| snapshot routes 保持；补范围/超限错误码；本地服务端口/baseUrl 配置与云端分离 | 不改变 DoseStatus；补参数配置、环境 scope 和 persisted sequence；local/cloud Storage 是 Server 内部适配差异 | 扩展 S06/S08/S09/R03/O02：多表读一致、超限不截断、过窗报告保留、能力序列重启、云端断开后本地完整两设备流程、跨环境 pending 不误发 |

**确认记录**：本项已由项目负责人按本方案批准（2026-10-16）；后续只审查实现与测试，不重新询问该选择。

## 已确认后的交接要求

全部七项状态为 **APPROVED_BY_PROJECT_LEAD**，版本 `T01-T07/2026-10-16-r1`。批准不等于实现、不等于通过测试，也不替代六人正式审查。这份决定记录本身不交付业务代码；后续用户授权的局部脚手架修复及验证见 REVIEW。

| 后续产出 | 责任 / 状态 |
|---|---|
| API、模型、Repository、测试方案及普通 Issue 同步 | 本轮文档/跟踪交接；同步现行规则至受影响产品/实现票据，旧讨论仅保留历史 |
| 六角色实名、各自代码/测试 PR | E/C1/C2/C3/S1/S2，待完成；功能作者负责自己的映射和联调，S1 不包办 |
| 同一基线/fixture/commit 的 Android 与 Server 正式签署 | 待完成，记录姓名、日期、版本 |
| 实际序列化、HTTP、Repository 与生产适配器测试 | 待实现并执行；JSON 示例不是通过证据 |
| 完全断云的本地双设备演练 | S2/S1/C3 牵头，全员接入；待实现和验收 |

本轮只同步规范、夹具和 Issue，不修改业务源码、运行配置或现有测试通过状态。API 23 的 desugaring、完整时区/开放日期验证算法和 20 MP 实机预算仍是实现验证义务，不是重新待拍板的产品决策。
