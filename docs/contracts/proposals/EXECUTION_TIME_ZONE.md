# S2 长者执行时区上报与授权读取契约草案

状态：IN_REVIEW / PROPOSED，供 S1／S2 同提交联审；尚未接受或实现生产接线。依据负责人在 [#15 的明确范围与默认日期决定](https://github.com/BRMRs/group33/issues/15#issuecomment-6939380941)，核对 main `bbe4997034ecb837d2093f4e73267cc530976f29`。独立于 #102，不改现行 API、Shared、Room 或生产配置。

## 已确定的行为

默认历史 today 取当前选中长者执行设备最后一次可信上报的 IANA 时区，由当前时刻转换得到；复用现有 defaultRange(today) 生成含首尾的 30 个本地日期。不取照护者设备时区、Server UTC、任意计划时区或 TimeZoneAcknowledgement。没有计划也适用同一规则。没有可信时区则来源不可用，保留显式日期入口，不发起猜测范围查询。

设备时区更新不改已冻结的 occurrence 日期、执行时区或历史记录。观测时间不是当前日期：不得把 observedAt 的日期一直当作 today。

## 建议的最小服务端增量（字段和路径均待联审）

建议专用 `PUT /api/v1/elders/{elderId}/execution-time-zone`，与既有 capability report 分开。建议请求：reportId（UUID）、observationSequence（正整数）、ianaTimeZoneId、observedAtEpochMillis。Idempotency-Key 与 reportId 相同；具体大小上限和错误 reason 在接受时固定。时区用有效区域型 IANA 标识，拒绝无法解析的名称及仅固定 offset；UTC 的规范区域别名接受集合须与 Android 同版确认。

actor、installation 和执行设备绑定均来自已验证身份／可信配置，不允许请求体自称。只允许当前绑定的长者执行设备为自身上报，照护者不能代写。当前匿名安装登录不能自动证明“生产执行设备绑定已完成”；正式身份来源沿用 #96/#118 的实际决定，未具备前只做明确隔离的开发适配及契约测试，不把测试身份当供应商认证。

建议复用已有 capability 的排序规则：S1 在同一 Room 事务中持久分配每安装 observationSequence 和 outbox，重试／进程重启保持 reportId、sequence 和完整原载荷。服务端在事务中保存持久回执和该安装的最高 sequence；同 key 同载荷返回原回执，同 key 不同载荷返回 409 IDEMPOTENCY_KEY_REUSED；同 sequence 不同载荷冲突，不能覆盖。旧 sequence 可被确认接收但不替换当前时区。客户端 observedAt、网络到达顺序与 snapshotVersion 都不能充当排序权威。

建议成功回执只确认原 report（含其原服务接收时间），将“原上报已确认”和“当前最新时区”分开；重放旧回执不能把 Room 最新值降级。原子提交时区当前行与回执，未知提交结果沿用原意图重试；不自动换 key。相同 sequence、安装切换、重装、设备迁移不能借此建立另一套全局时钟。

授权读取建议同时提供独立 `GET`（解决默认日期尚无 fromDate 的启动问题），并在受权 snapshot 增加一个可空设备时区 singleton，以供同一事务持久同步。客户端不得为了先取得时区而猜测 snapshot 的日期区间。GET 的最小视图为 elderId、可信来源标识／代次、ianaTimeZoneId、observationSequence、observedAtEpochMillis、serverReceivedAtEpochMillis；无上报返回明确空值，不用虚构默认值。字段是否全部对照护者公开、安装标识是否使用不透明来源 token，由 S1/S2 在冻结 DTO 前确定。

自身长者或当前有效关系的照护者可读；关系授权与读取处于同一只读一致事务。401 表示缺失／过期身份；无关长者无论存在与否均按既有 404 RESOURCE_NOT_ACCESSIBLE，已知角色错误遵循现行 403 规则。拒绝不能携带设备时区；503 不等于撤权。响应 no-store，不由共享 HTTP 缓存跨账号复用。受权 snapshot 增加 singleton 后也必须同步既有 10,000 实体计数和整体原子合并规则。

## S1／C1 消费边界

Room 在匹配 `(RepositoryScope, sessionGeneration, relationshipId, elderId)` 的上下文内保存及发布。会话／环境／关系／长者变化或撤权时先发布不可用，再接受匹配新上下文；旧请求迟到不能恢复旧值。服务端不接受 sessionGeneration 作为授权凭据，它属于客户端隔离机制。

S1 使用设备时区和注入的当前时钟计算 currentLocalDate，监听 zone 变化、该 zone 午夜和系统时钟变化；跨 DST 不采用固定 24 小时定时器。C1 只传匹配的可用日期输入，C3 只消费 today；来源不可用时清理默认查询内容，显式日期草稿沿用既有隔离规则。离线“最后可信”值是否可用遵循已知授权和 stale 标识，不以记录年龄自行发明一个未决定的过期阈值。

## 接受前须明确的三个接缝

1. S1／S2：确定 DTO／路径、有效 IANA 名称与跨 Android/JDK tzdb 兼容策略，以及回执和当前值分离；明确 snapshot 空值及实体计数变更，同版更新 Shared/fixture/契约测试。
2. 身份／执行设备 owner：确认可信执行安装的提供点、单设备假设和更换设备时的代次／sequence 规则。禁止按 observedAt 或“最后到达”在多安装之间自动选权威；绑定不明确则不可用。
3. S1：确认 Room/outbox 持久点、当前上下文流接口、授权失效传播以及时区午夜更新入口，再与 C1 对接。默认日期规则已经确定，不再次请求产品重新决定。

## 可验收测试清单

- 鉴权：自身执行安装／有效照护者成功；其他账号、其他安装、撤权、过期凭据拒绝且不泄漏；拒绝读取与旧缓存失效分开验证。
- 重放／并发：同 key 20 并发一次效果、key/sequence 异载荷冲突、迟到旧 sequence、事务失败及未知提交后原 ID 恢复、重启仍保留排序和原回执。
- 时区：无值、多计划／无计划、上海与洛杉矶日期不同、DST 23/25 小时、跨午夜和设备时钟调整；未知 zone 不回退；更新不重写任何历史 occurrence。
- 隔离：A→B→A、长者／关系／环境切换、撤权后迟到响应、同账号新 session；先不可用、匹配后再可用。
- 同步：独立 GET 无需猜日期；snapshot singleton 计数／原子 Room 合并；旧 receipt 不覆盖新 zone，503 保留符合现行权限的 stale 缓存；不同上下文不复用。

实现时单独 PR，附真实数据库迁移／回执／授权测试与对应 S1 消费证据。当前文档不占用 V12 编号，不预先执行 DDL，不称 #15／#19 默认日期入口已上线。

## 本合同 PR 的具体表示选择

新增类型暂放 com.group33.shared.contract.proposal，不替换现行T01–T07或既有DTO；无人类同版批准前不接生产Controller/Room。ExecutionZoneReport只含reportId/observationSequence/ianaTimeZoneId/observedAt，actor和安装绑定由服务端当前会话取得。独立GET返回ExecutionZoneContext，availability为必填AVAILABLE/UNAVAILABLE；current仅在AVAILABLE时必须有值，UNAVAILABLE时省略/null等价，避免先猜snapshot日期。可信源采用不透明executionSourceId，不公开安装UUID；新设备绑定决议前来源不可用。ExecutionZoneReceipt只确认原reportId和接收时间，不把旧回执中的当前值倒灌Room。UTC接受JDK/Android区域标识UTC及有效区域别名；纯offset不接受，跨tzdb无法识别时返回明确不可用，不回退猜测。

后续同版snapshot扩展需单独审查计数/原子Room合并；本PR不改ElderSnapshot，避免让旧客户端误认已接受字段。S1产生含当前zone/observedAt/currentLocalDate的客户端上下文，currentLocalDate按当前时钟计算而非观测日期。所有接缝列于上文，不以本轮wire测试替代真实身份、HTTP、PG、Room或午夜设备验收。

本轮提供Shared、Android kotlinx与Server Boot Jackson同一正反向量，覆盖合法区域、负时间/sequence、未知字段、缺失/null和小数整数。形式成功不证明设备的上报可信或已完成服务端持久化。请S1按该head接受/修改，S2作者自查不是独立人类批准。接受后再按合同新增HTTP/事务/迁移及S1 Room实现，不抢占迁移编号。

## 解码差异反例与修正

初轮补充反例发现Boot Jackson会把缺失的可空current当null，而kotlinx拒绝；因此采用必填availability和一致的current约束。只含elderId的旧反例仍必须被拒绝。未修改全局Jackson配置；旧生产DTO无变化。所有正反向量在三端重跑，原失败证据保留。

## 2026-11-18 S1 compatibility correction

Wire and local consumption are separate. ExecutionZoneReport checks only bounded ASCII region-name representation (1–255 characters, no blank/empty/dot segments or fixed-offset syntax), never the receiving machine's tzdb in its constructor. A Server report handler must still validate against the Server registry plus authenticated current execution binding before accepting a report; wire decoding alone is not trusted acceptance.

A Server-AVAILABLE context remains decodable when the client registry lacks that region. `forLocalRuntime` produces a new explicit UNAVAILABLE/current=null local consumption view if lookup is unknown or fails. It preserves the original raw Server object/report/sequence/observation for synchronized storage and later registry updates. S1 must publish only the consumed AVAILABLE view before computing today, and keep its existing session/relationship/generation fences. No caregiver/UTC/plan/old-cache fallback or guessed query is permitted; explicit Server-UNAVAILABLE never invokes registry lookup. The adapter does not generate a date or enable Room/HTTP.

The old `Not/AZone` negative vector mixed registry membership with wire integrity; per this review it is replaced with malformed `Not//AZone`. Future/unknown but syntactically sound registry entries now have positive-decode plus UNAVAILABLE-consumption assertions in all three runtimes. The registry-skew fixture injects two supported-name sets and an unregistered synthetic name; it does not claim historical Android/JDK tzdb builds were installed.

**Report and receipt reportId are canonical lowercase UUID text, exactly36 characters (8-4-4-4-12).** Uppercase or shortened spellings are malformed, never silently normalized or assigned another operation key. This documents the existing boundary and aligns tri-runtime vectors; use lowercase alphabetic-hex positive and uppercase negative examples. Existing wire fields are unchanged.

Original two-method Shared compatibility test: unknown registry entry failed while canonical UUID checks passed. The same positive decoding case passes after the change; added local-unavailable/raw-retention/lookup-failure and syntax cases verify the required behaviour. Earlier whole-context nullable decoding evidence is separate. Same-head S1 and non-author Server/contract re-review remains required.
