# #118 独立手机号联调：部署与配置交接清单

2026-11-25。Refs #118 / #16 / #88 / #96 / #143–#145。准备分支基线 `9e5621393c3d130bc65ec6754d0531c363c9cf9d`。

**状态：部署准备，不是可直接导入的运行配置。** 本清单没有生成密钥、创建云资源、应用迁移、发送短信或触发部署。不能把“变量名已找到”写成“同版服务已经可用”。

## 1. 已确定的方案与分工

沿用 [ADR-0004](adr/0004-hosting-storage-and-explicit-sync.md)：Render 运行 Spring Boot，Supabase PostgreSQL/私有 Storage 保存业务数据与照片；本地 PostgreSQL/私有文件的 LAN fallback 单独隔离。此次新建的是独立手机号联调环境，不切换既有匿名演示服务，不自动迁移匿名账号、照片、Room/outbox 或原操作。

| 项目 | 决定或已核实值 | 负责人/状态 |
| --- | --- | --- |
| 平台操作 | BRMRs / Rui Zheng 保留 Render 后台操作权；用户授权代理在必要步骤直接操作电脑 | 已确认能登录并看到旧服务，不共享账号密码 |
| Server | S2 / XinyanXue 提交经过审查的同版 commit、迁移、完整变量映射、启动/回退步骤 | 交付前不配置未知运行变量 |
| Android / 包 | S1 / BRMRs 核同源地址、实际包名/证书、构建及包摘要 | 按同版接口完成后生成 |
| 客户端 / 供应商 | C1 / tianiridescent 核 scheme、SDK 方案与实际采集、同意/取消、真实设备体验 | 已确认公开包名与登记MD5，其他以#118证据为准 |
| 新服务建议名 | `group33-phone-integration` | 建议名，尚无 service ID / URL，不保证名称可用 |
| 新服务区域 | 建议 Singapore，与最终 Supabase 地区/网络可达性协调 | 新资源未创建；地区和数据流进入最终政策 |
| 费用 | 先不升级付费工作区；不邀请 S2 加入 Hobby | 如后续选择付费资源另行确认，不能自动订阅 |
| 政策运营者/邮箱 | CSI301 Group33 项目组；rui.zheng23@student.xjtlu.edu.cn | 用户在本轮明确提供并授权用于政策 |

Render 官方说明：Hobby 不支持邀请成员；免费服务空闲15分钟休眠、临时文件会随重启/部署等丢失，750免费实例小时由工作区共享。不要让两个免费实例都依靠keep-alive常驻；新联调默认不启用keep-alive，正式演示稳定性另验。免费 Render Postgres 30天到期，本方案不以它替换既定 Supabase 栈。[免费限制](https://render.com/docs/free)、[成员权限](https://render.com/docs/team-members)。

## 2. 本轮电脑只读核对结果

已通过现有 GitHub 登录会话进入 Render，读取 Overview / Settings，未打开密钥显示、未改任何设置：

| 项目 | 实际观察 |
| --- | --- |
| 旧服务 | `group33-pr110-preview` |
| Service ID | `srv-dbmrfobrjlhs73ca25c0` |
| 类型 / 区域 | Blueprint managed、Docker、Free、Singapore |
| 来源仓库/分支 | `BRMRs/group33` / `main` |
| 实际最后成功部署 | `61140712193b816980045c52cc7276223731c268`，不是本轮最新 main |
| Dockerfile / context | `./Dockerfile` / `.`；Root Directory 空 |
| 自动部署 / 健康检查 | Off / `/health` |
| 公开预览地址 | https://group33-pr110-preview.onrender.com |

现有仓库 `render.yaml` 使用 `PERSISTENCE_MODE=memory`、照片在 `/tmp/group33-preview-photos`。这是源码设置，未在本轮读取/导出后台 Environment 的实际值；不能把源码配置冒称已逐项核对的线上环境。旧服务由 Blueprint 管理，准备新服务时不要修改其 Blueprint 或点击旧服务 Manual Deploy。

## 3. 当前阻止直接部署的具体代码事实

- main `9e56213` 无正式手机号服务装配。#143 是身份持久化适配器，#144 增加绑定认证流程，#145 仅计划编辑/停用的内部 PS2 接线；均不等于所有业务路由已可用。
- #144 的 Controller 条件是 Spring property `app.bound-phone.enabled`，但其 `application.yml` 没有新增部署映射，所需 authentication/provider/JWT/primary DataSource/可信限流 Bean 装配仍需交付。**不猜一个环境变量名来“开启”它。**
- 旧 #88 `PHONE_AUTH_ENABLED` 对应 `app.fusion.enabled`，启用的是旧试验认证，不是 #144。两者占用同组 `/api/v1/auth` 路由，不能无审查同时启用。
- main 的 `postgres-local` 连接被限制为 loopback。不能将 Supabase URL 填入 `.env.example` 后称其支持云端；待 #79/#80/#102/#104 等适配、迁移和启动检查按新 main 收口。
- Supabase 候选的 `postgres-supabase`、私有 Storage 与 TLS 配置已存在于分支，未与此次绑定手机号流程形成经过审查的统一部署版本。旧原型 schema 初始化不替代新迁移；`server/src/test/resources/db/prototypes` 下建表 SQL 是测试夹具，不能直接复制进云库。
- #145 未覆盖全部 pairing/consent、plan confirmation/reads、photo、event/reminder 授权；旧匿名业务能用、健康检查200或CI全绿，都不能代替手机号业务链验收。

代码/资料的精确来源及摘要见 [source-snapshot.json](privacy/source-snapshot.json)。

## 4. S2 交付后可填的配置台账

可编辑表格：[phone-integration-config.csv](privacy/phone-integration-config.csv)。该表仅记录名称、来源、负责人和状态；**没有秘密值，也不是可直接导入 Render 的 .env**。

### A. 当前主干已认识的设置

| 名称 | 正确用途 / 交接要求 |
| --- | --- |
| `PORT` | Render 提供，应用读取；不把本机调试端口18082当云端入口 |
| `JWT_SECRET` | 当前匿名签名设置；新绑定 phone profile 需独立已审密钥/issuer/audience映射，不能假定自动复用 |
| `PERSISTENCE_MODE` | main只有memory/postgres-local；云候选支持postgres-supabase，必须以最终构建核对 |
| `PERSISTENCE_JDBC_URL`、`PERSISTENCE_SCHEMA`、`PERSISTENCE_USERNAME`、`PERSISTENCE_PASSWORD` | 同一 primary 的连接/隔离schema/受限runtime账号；不使用管理员账号，不在URL嵌入密码 |
| `PERSISTENCE_RECEIPT_KEY_FILE` | 私有owner-only文件，读取内容要求恰好32字节；不是把64字符hex/base64原文当32字节文件。S2交付适用于容器用户的文件提供方式与权限测试 |
| `PERSISTENCE_REQUIRE_VERSIONED_SCHEMA` | 当前main只支持postgres-local；不能照搬到云模式后假定通过，应由最终cloud启动检查明确支持 |
| `PHOTO_ENABLED`、`PLAN_ENABLED`、`OCCURRENCE_ENABLED`、`DOSE_GROUP_ENABLED` | 按同版数据库和业务授权就绪逐项开启，不“一键全true” |
| `PHOTO_OBJECT_ROOT` | 本地文件模式用；`/tmp`不是持久私有云Storage |
| `KEEP_ALIVE_ENABLED` | 新免费联调建议false，按需测试；不改变旧演示服务 |

### B. 分支设置：不能误当 main 或最终新认证配置

- 云候选已出现：`SUPABASE_PROJECT_REF`、`PERSISTENCE_SSL_ROOT_CERT`、`PHOTO_STORAGE_MODE`、`SUPABASE_STORAGE_ORIGIN`、`SUPABASE_STORAGE_BUCKET`、`SUPABASE_STORAGE_SECRET_KEY_FILE`、`SUPABASE_STORAGE_REQUEST_BUDGET_MS`。必须对最终同版jar重新核对；private bucket，后台key仅服务端，TLS验证不关闭。
- 旧认证已出现：`PHONE_AUTH_ENABLED`、`ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET`、`PHONE_AUTH_SCHEME_CODE`、`PHONE_AUTH_PACKAGE_NAME`、`PHONE_AUTH_PACKAGE_SIGN`、`PHONE_AUTH_DATABASE_URL`、`PHONE_AUTH_DATABASE_USER`、`PHONE_AUTH_DATABASE_PASSWORD`、`PHONE_AUTH_HASH_KEY`。其中旧独立phone DB不能充当新方案的same-primary装配。
- 新绑定认证待S2给出明确映射：启用开关；同primary DataSource/schema；有界供应商adapter；可信代理/IP限流；phone-HMAC key；typed JWT独立签名key、issuer、audience/profile；原attempt/lease/ticket生命周期；密钥与文件权限。这里只列所需输入，不虚构环境变量。
- #118 中 C1确认：applicationId `com.group33.medicationreminder`；公开证书MD5 `de4612d56e3c0dbe3cee3b76bc3ab294`；场景`100001`。这是C1记录的公开核对，不代表当前Mac本地签名也匹配。scheme code、实际方案开关及供应商proof规则仍由配置人核对。

### C. 操作前交付表

| 输入 | 交付人 | 当前状态 |
| --- | --- | --- |
| 最终Server full SHA / CI / 审核 | S2，S1/非作者专项审查 | 未交付统一可部署版 |
| Supabase project/schema/bucket/地区、runtime及migration权限 | S2提供，负责人平台操作 | 本轮未核验真实资源，不猜ID |
| 新迁移发行包、备份验证、迁移前后检查和app兼容矩阵 | S2 | phone prototype不能代用；保留已应用V10，待#102的V11以最终main为准 |
| 运行变量完整映射及所有secret-file容器权限 | S2 | 待统一装配 |
| 可信代理与限流配置 | S2 | Render代理下不得盲目信任客户端X-Forwarded-For，也不得直接关闭限流 |
| 实际SDK/方案子服务清单、同意前不初始化证据 | C1/S1 | 依赖和启用调用已确认，合并APK/网络范围仍待验 |
| 保存期限、proof防重放保留、账号/对象/备份删除和权利响应流程 | S2牵头，负责人确认执行人 | 未完；政策保持审阅稿 |
| 最终政策URL和生效版本 | S1准备，C1/S2核事实，负责人发布 | 本轮提供中英Markdown及无外部依赖HTML草稿 |

## 5. 获得上述输入后的 Render 操作顺序

1. 确认处于自己的现有工作区。旧服务 `srv-dbmrfobrjlhs73ca25c0` 保持原状。新资源的名称先核可用性；不进入付费升级或成员邀请流程。
2. 创建新的 Docker Web Service：仓库BRMRs/group33、经过审查的目标分支、Dockerfile `./Dockerfile`、context `.`、Root Directory空、Free、协调后的区域、health `/health`、Auto-Deploy Off。**创建服务会触发初次部署**，所以不能在代码/密钥/持久化未准备好时提前点最终创建。
3. S2先完成经审的数据库/schema/bucket准备、迁移与只读验证。运行账号与迁移账号分开；已有数据库不清空、不修历史摘要、不baseline绕过。当前Free实例没有可用的Pre-Deploy Command/Shell时，迁移在受控管理环境运行；不要把Flyway管理步骤塞进应用启动或Docker构建。
4. 仅在目标新服务的 **Environment** 录入S2最终清单的已知变量与受控秘密；不在聊天/公开工单录入值。Secrets或文件的格式/权限须先验证，不能复制旧服务JWT/receipt密钥或以管理员数据库密码代替runtime权限。
5. 配置分步保存时选择 **Save only**，知道它在下一次部署才生效；不要误点Save and deploy/rebuild触发未准备好的上线。[Render变量说明](https://render.com/docs/configure-environment-variables)。
6. 使用 **Manual Deploy → Deploy a specific commit** 指定通过审查的full SHA，而不是未经核对的最新main；检查Deploys可见实际commit、启动、数据库/Storage模式和健康结果。只记名称/存在性/版本，不截图秘密值。
7. 完成下一节真实服务验收后，才由S1使用已适配同版客户端和最终政策URL构建团队包。启用真实供应商前核对其计费/额度条件；本准备不发送短信或产生供应商认证调用。
8. 在#118记录新service ID、公开origin、Server SHA、迁移版本、政策URL、APK摘要、已通过与未通过项目。记录事实不能用health200替代认证结果。

### 单独的政策页托管

草稿文件：[中](privacy/PRIVACY_POLICY_DRAFT.zh-CN.md)、[英](privacy/PRIVACY_POLICY_DRAFT.en.md)、[静态HTML](privacy/site/index.html)。HTML无脚本、远程字体、图片、表单或追踪依赖；页面浏览仍会向托管平台发送网络请求，不能说“访问不处理任何个人信息”。`noindex`不是访问控制。

正式事实审查完成后，去掉审阅稿标识、填确定生效日期、保留版本记录，再提交独立评审。政策页可建 Render **Static Site**：建议名`group33-privacy`（未创建、不保证可用）、已审发布分支、Build Command `echo policy-static`、Publish Directory `docs/privacy/site`；无应用密钥。新站首次创建也会发布，不能把这一步当“只保存草稿”。关闭后续自动部署，仅发布经过核对的版本；HTTPS可达、无登录/错误页，真机中英文/大字/返回均检查后再把实际URL填入APK参数。[Render Static Sites](https://render.com/docs/static-sites)。

## 6. 同版服务与团队包验收

- [ ] 启动不是memory/临时照片；迁移校验、重启后账号/会话/原回执和照片保留，数据库/Storage故障不回退匿名或内存。
- [ ] 实际SDK一键与短信链、旧账号固定角色/新注册、安装echo/原attempt/取消与迟到隔离分别验证；不把模拟supplier测试写成实际通过。
- [ ] 同phone会话完成所需pairing/consent、photo、plan确认/读/改/停、event/reminder；未接通的PS2业务不能借匿名token绕过。
- [ ] 错误角色403、无权资源404、撤权/注销、跨安装原操作隔离和原key重试有证据；原回执不是当前授权。
- [ ] 政策同意前没有SDK初始化/网络采集；取消不启动；第三方协议与实际方案相符；SDK权限/标识和日志实际核验。
- [ ] 数据保存/权利申请/删除和供应商渠道可执行，保留必要安全记录的范围和期限明确；手机退出不是全量删除。
- [ ] 同一HTTPS origin用于认证和业务；最终包名、签名MD5、隐私页、APK SHA、Server SHA及新云环境一致；与LAN/旧匿名演示隔离。

构建命令仅用于接口已适配、源码包含该任务的最终客户端（主干目前没有本任务）：

```text
./gradlew :android-app:assemblePhoneTest -PphoneAuthBaseUrl=https://ACTUAL_NEW_SERVICE -PphoneAuthPrivacyPolicyUrl=https://ACTUAL_FINAL_POLICY -PphoneAuthSigningMd5=de4612d56e3c0dbe3cee3b76bc3ab294
```

占位URL不能直接执行；该任务只检查配置/证书，不证明服务或SDK已经验收。团队keystore密码等只留受控构建环境，不写文档。普通assembleDebug不是手机号同版测试包。

## 7. 失败与回退

部署失败先保留安全错误类别和版本，调查配置或代码；不改旧演示、不自动重建schema/轮换稳定keys、不关闭TLS、不打开私有bucket、不伪造审查。数据迁移只向前修正；回退应用前核对schema兼容，不能用旧jar读未来schema。关新入口不等于删除数据，实际停用/清理由S2经审方案执行。

## 8. 验证与公开资料

本交付是文档与静态草稿，不改运行源码/权限/网络/数据库。校验内容为源码pin/hash、Markdown/JSON/CSV/HTML结构、仓库策略和完整artifact清单，不把它们称为部署或设备通过。无运行代码变化，未重复全项目Gradle/供应商/云端写入测试。

官方资料（2026-11-25核对）：[Render权限](https://render.com/docs/team-members)、[免费限制](https://render.com/docs/free)、[环境变量](https://render.com/docs/configure-environment-variables)、[手工部署](https://render.com/docs/deploys)、[静态站](https://render.com/docs/static-sites)、[Supabase数据库连接](https://supabase.com/docs/guides/database/connecting-to-postgres)、[private buckets](https://supabase.com/docs/guides/storage/buckets/fundamentals)。SDK与政策事实见[审查说明](privacy/README.md)。
