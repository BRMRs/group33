# #118：S2 手机号服务实施与团队配置交接

## 2026-11-25 当前整合说明（BRMRs）

下文的源码表和原始配置缺口是11月21日的历史快照；七个full SHA/source hash保持不变。
#96设计已按原 `caecd8f` 的实际S1/C1/限定非作者安全结论接受，并squash进入main
`9e5621393c3d130bc65ec6754d0531c363c9cf9d`。接受台账见#142；不再把设计批准或负责人平台选择当作开工等待。

当前执行以[负责人#118交接](https://github.com/BRMRs/group33/issues/118#issuecomment-7152263731)为准：
已选独立免费Render联调服务、负责人操作平台、S2提供已审配置；#146提供已确认主体/邮箱的政策草稿和部署清单，尚未生效或发布HTTPS政策。
PS1-A已有#143，绑定认证及现成修复已有#144/#147，#145覆盖计划编辑/停用的PS2子集；这些仍是独立审查输入，未组成完整生产服务。
原trial #88/#94/#97、已交公共Android session和15秒预算继续复用，不将旧bodyless接口当成新bound-auth装配。
A–E的同primary装配、正式前向迁移、剩余PS2入口、数据事实和同版部署交接仍须S2补齐，C1/S1核实际SDK与最终APK。
本次只更新整合事实，不修改供应商证据、环境、秘密、wire或实现审核结果。

负责人：XinyanXue（S2）。2026-11-21，Refs #118/#49/#10/#16。此交付回应 [C1 的具体交接请求](https://github.com/BRMRs/group33/issues/118#issuecomment-7039934061)，提供源码可核验的接口现状、配置操作和第一批实施边界。本文没有发布手机号服务或冻结待审字段。

## 1. 2026-11-21 交接快照（历史输入）

| 输入 | 核对版本 | 已有内容／仍缺内容 |
|---|---|---|
| 实际 main | `b5acc05224612bf25797ee71a5cdf1a879b67d91` | 配对业务解析器仍要求匿名账户；不支持直接把试验 phone JWT 用作配对 actor |
| #88 | `03846a9975170e7b0f6cc4e2ea030c270d7ea2fb` | 默认关闭的 Fusion 试验接口、Android 公共会话和 assemblePhoneTest 已存在；尚未成为生产手机号账号／安装／业务授权接线 |
| #94 | `554387b89691f6fe76568821fe94596d22dfc438` | 当前 phone account 校验及临时不可用区分已交付分支；不新增 installation/session 绑定 |
| #97 | `d320841986015f109f0ecd2260026ea1be598b23` | 15 秒内部调用预算、固定并发／无等待队列、显式 SDK 网络超时／禁止自动重发及有界 SQL 已交付分支；不是已合 main 或真实供应商成功 |
| #96 | `caecd8f8210cae6fc54adf847a65a4078c2bbaf2` | D1–D4 设计与 S1 决定已有记录；旧 `1ee26cd` 的独立设计结论可追溯，当前同版正式接受／专项结论仍须收口 |

保留 #88 → #94 → #97 的现有依赖。本文不复制其实现，不把 Android 延长等待描述成供应商超时已解决，也不把公共会话列为尚未开始。

源码逐项引用及 SHA-256 见 [来源清单](contracts/proposals/phone-server-handoff-20261121.sources.json)。本次是源码／文档交接核对，没有新调用供应商、发短信或运行真实手机号验收。

## 2. 已有试验接口与未来接口必须分开

以下现状来自 [#88 PhoneAuthController](https://github.com/BRMRs/group33/blob/03846a9975170e7b0f6cc4e2ea030c270d7ea2fb/server/src/main/kotlin/com/group33/server/auth/PhoneAuthController.kt)，共同前缀 `/api/v1/auth`。它们是分支上的实验路径，不能据此声称团队服务已启用。

| 路径 | 当前试验请求 | 当前试验结果 |
|---|---|---|
| POST `/fusion/token` | 无 body 参数 | fusionAuthToken、schemeCode、expiresInSeconds；没有原 attempt 的服务端 capability |
| POST `/fusion/verify` | verifyToken；SDK 短信可带 expectedPhone | SIGNED_IN 的 accessToken/expiresInSeconds/user，或 REGISTRATION_REQUIRED 的 registrationTicket |
| POST `/phone/register` | registrationTicket、role、可选 displayName | 初次固定角色账户及试验会话；CAREGIVER 名称必须有效 |

expectedPhone 仅在供应商 PASS 后匹配，不是手机号身份证明。SDK 短信继续沿用同一 verify/register 链路，不新增手工发码／验码服务。密码登录／重置没有被本文加入接口。

未来 D1–D4 在 #96 中提出 installationId、原 authenticationAttemptToken、严格 Start/Refresh、固定截止、typed phone JWT 及持久 session/jti；**这些不是当前试验请求已支持的字段**。C1 可以核对消费方案和现有客户端，但应在 Server 切片、同版接受记录及适配输入就绪后再切换请求。

禁止仅把 main 的 `type=anonymous` 检查改成接受 phone：下游目录、当前 account/install/session、原安装操作身份及同事务关系授权仍须一起实现。登录不授予其他长者权限，不自动合并匿名数据，不用 userId 代替 installationId。

## 3. S2 下一批具体实现切片

以下是实施 brief，不是已完成代码或已排定日期。首项可独立交付的本轮成果就是此源码／配置交接及供应商资料核对；受影响的生产切片保留既定前置。

| 顺序 | S2 交付范围 | 可观察验证与开工前置 |
|---|---|---|
| PS1：有界原 attempt 与持久身份 | 新的向前迁移；原 installation/profile/capability、固定截止和短 lease；稳定 account、独立 installation/session；保留 #97 已有预算 | 严格 Start/Refresh、截止前/恰好/后、同 attempt 刷新不续期、未知结果不自动重发、并发重复及 late PASS 不落状态。前置：#96 当前版接受/专项结论；不猜迁移版本号 |
| PS2：当前业务 principal／授权 | 在各真实 primary transaction 内读取当前 account/install/session、当前关系、原操作范围和效果／receipt；覆盖目录和下游 Store | 自己账户成功、错误角色403、无权目标404、撤销后拒绝、同安装恢复原身份、另一安装不能接管 pending、原回执不代替当前授权。前置：PS1 与跨端错误／身份契约接受 |
| PS3：同服务受控部署 | 实际 HTTPS origin、部署 Server commit、启用的 auth/business 路由、迁移/回退与配置负责人记录 | 从接受后的受控构建验证健康、配置模式、真实认证与业务权限；不以 health200 代替手机号成功。前置：PS1/PS2、实际服务权限、配置和供应商资料 |
| PS4：团队包与联合验收 | S2 交付服务清单；S1 复用 assemblePhoneTest 生成同版包；C1 执行设备操作 | 一键／SDK 短信、新老账号、固定角色、配对同意、越权拒绝、退出/换号/重装恢复及失败重试分别留证。前置：PS3、政策 URL、登记证书和设备条件 |

PS1/PS2 的迁移号必须以届时实际 main 为准；当前 dose V10 已应用、index V11 仍在 #102 待审，不能复用 V10、重写已应用历史或提前占用待定号。当前试验 PhoneAccountStore 的首次使用建表不作为正式上线迁移方案。

不把未接通的实现写成“今天可用”，也不为依赖未齐的后续阶段承诺未经核验的时间。C1 的正式适配起点是发布且可核验的 PS1/PS2 同版输入；服务/包就绪后才有真实联合验收起点。

## 4. AccessKey 配置在哪个服务、哪个入口

配置 accountable owner 是 S2；服务访问与受控授权由负责人管理。现有公开记录没有可核验的 phone-enabled 服务控制台 URL。`group33-pr110-preview.onrender.com` 是已记录的匿名业务预览，不是密钥填写页面，也不是手机号服务已接通。

若最终采用受控 Render 服务，配置负责人先确认**实际服务名／service ID、所部署提交、目标环境与已有访问权限**。随后在 Render Dashboard 选择该服务 → Environment → Environment Variables → Add Environment Variable。页面保存选项会影响是否部署；`Save only` 的值要到后续部署才生效。该操作说明来自 [Render 官方文档](https://render.com/docs/configure-environment-variables)，不表示本次已操作某个服务。

以下名称由 #88 的 [application.yml](https://github.com/BRMRs/group33/blob/03846a9975170e7b0f6cc4e2ea030c270d7ea2fb/server/src/main/resources/application.yml) 核对；它们不自动使当前 main 支持试验代码。本文不列任何真实值。

| 名称 | 配置职责／核对内容 |
|---|---|
| PHONE_AUTH_ENABLED | 在已审服务构建、存储和路由就绪后显式启用；默认关闭 |
| ALIBABA_CLOUD_ACCESS_KEY_ID、ALIBABA_CLOUD_ACCESS_KEY_SECRET | 由有权限的配置负责人填入服务端机密环境；测试者安装 APK 不填写 |
| PHONE_AUTH_SCHEME_CODE | 对应实际 Android Fusion 方案 |
| PHONE_AUTH_PACKAGE_NAME | 真实 applicationId，当前确认为 com.group33.medicationreminder |
| PHONE_AUTH_PACKAGE_SIGN | 与供应商登记及实际团队签名一致的证书 MD5，按现有实现无冒号；不猜登记值 |
| PHONE_AUTH_DATABASE_URL、PHONE_AUTH_DATABASE_USER、PHONE_AUTH_DATABASE_PASSWORD | 当前试验独立 phone store 名称；生产统一 DataSource 与迁移接线待实施，不能直接照试验首次建表上线 |
| PHONE_AUTH_HASH_KEY | 至少32字节且稳定保管；更换会影响已有账号发现，轮换另按迁移设计 |
| JWT_SECRET | 受控、非缺省的签名密钥；未来 phone profile/audience/session 接线仍需实现 |

普通业务 `PERSISTENCE_*` 与试验 `PHONE_AUTH_DATABASE_*` 不是自动统一。仅设置前一组不能补齐 phone store，也不能证明同一 primary transaction 下的账号和关系检查已经实现。

持有密钥的 C1 不需要将值贴到 GitHub/聊天，也不需要把密钥放 Android。若由配置负责人操作，由负责人通过现有受控服务权限/秘密管理渠道交付或录入；本票公开记录只写“已设置／未设置、谁负责、何时验证”和实际服务 ID/提交，不记录值。若服务尚未确定或无访问权，先保持此项明确未就绪，不把密钥尝试填入匿名预览站点。

按 [官方授权表](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-verifywithfusionauthtoken) 核对所需 Fusion API 权限；资料核对见 [供应商证据](PHONE_PROVIDER_EVIDENCE_20261121.md)。本文没有实际授予 RAM 权限或注入凭证。

## 5. 服务就绪后如何确认配置成功

1. 记录 Server commit、环境和已审迁移状态；确认不是旧匿名构建，且关键变量只记录存在/缺失。
2. 健康检查只证明基础可达。用同版受控测试验证实际 token/verify/register 路由，分别记录安全的结果类别与耗时；真实供应商操作在配置和验收条件齐全后执行。
3. 保留 #97 的 SDK_TOKEN/VERIFY 与 SUCCESS/DENIED/TIMEOUT/SATURATED/INTERRUPTED/FAILED 和 elapsed_ms 诊断分类；不复制供应商报错正文、号码或 proof。既有预算不等于本次实测网络延迟。
4. 用实际 phone 会话验证自有账户及授权业务，再验证无关系/错误角色/撤销/另一安装；禁止失败后匿名回退。
5. S1 从接受后的源码沿用 #88 assemblePhoneTest，核对同源 HTTPS 业务/认证地址、政策 URL、applicationId、登记 MD5；记录 APK SHA-256、源码和 Server commit 后再分发。
6. 在 #118 分开链接供应商、Server、业务授权和设备证据。所有未通过项保留状态和负责人，不能以 CI、样例、首页跳转或 health200 勾成完成。

生产回退必须保留数据库兼容性和原操作证据；关闭该模式／回退构建不自动删除账号、切匿名或迁移云 pending 到 LAN。实际操作方案随 PS3 同版服务交付。

## 6. 仍缺的条件与当前可做工作

当前可做的源码核对、分阶段实施 brief、变量/配置步骤和公开供应商资料核对已由本交付完成。下一步受影响实现需要 #96 当前同版接受/独立结论、proof 保留/关联证据以及实际服务/政策/登记签名条件。PS2 之前不对 C1 宣称真实手机号配对已可用；服务与团队包齐备前不启动真实短信验收。本交付不关闭 #118。
