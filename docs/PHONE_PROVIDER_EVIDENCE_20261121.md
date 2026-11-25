# Fusion 供应商资料核对 — 2026-11-21

Refs #118/#96。本次只查询公开官方资料，没有调用 GetFusionAuthToken/VerifyWithFusionAuthToken、发送短信、读取凭证或更改服务。结论供最终设计评审使用，不是供应商实际成功证据。

## 已核实的来源与边界

| 官方来源 | 本次可支持的结论 | 不能据此推断的内容 |
|---|---|---|
| [GetFusionAuthToken](https://help.aliyun.com/en/pnvs/developer-reference/api-dypnsapi-2017-05-25-getfusionauthtoken) | DurationSeconds 描述 SDK 鉴权 grant 的有效期，范围900–43200秒；Android 请求需对应包名/签名/方案 | 这不是 SDK 成功后返回的 VerifyToken 的保留期；也不是项目原 attempt 可以被续期的授权 |
| [VerifyWithFusionAuthToken](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-verifywithfusionauthtoken) | 接收 SDK 的 VerifyToken；返回调用状态和认证结果，PASS 时取得手机号；请求字段表没有项目 installation/attempt/session 字段 | 本次检查的字段说明没有给出 proof TTL、可重复验证次数或将 proof 绑定到项目原 attempt 的证据。不能因为未列出就断言供应商不存在内部限制 |
| [Android 融合认证](https://help.aliyun.com/zh/pnvs/developer-reference/integration-authentication-sdk/) | SDK grant 更新和统一认证 Token 回传是不同生命周期；客户端回调需要 Server 兑换结果 | 不能将 SDK grant 的过期/刷新行为直接当成认证 proof 的有效期或服务器指纹清理期限 |

项目 #97 在源中请求900秒 SDK grant；#96 的原 attempt 固定900秒和15秒内部调用预算是已记录的项目约束。供应商允许的 grant 范围不改变这些本地约束，也不使刷新后的原 attempt 重新计时。

## 公开授权信息

官方 API 授权表列出 `dypns:GetFusionAuthToken` 和 `dypns:VerifyWithFusionAuthToken`；这两个操作的表使用全部资源。配置负责人可以据此核对 RAM 主体是否具备所需的具体动作，不从旧试验曾使用的宽权限推断生产必须授予全产品权限。本次未更改任何 RAM policy。

## 未能从本次资料中收口的 proof 问题

需要保存可归属实际产品/方案/版本的官方说明或供应商答复：认证 proof 从何时起、在什么范围内失效；重复兑换结果；与方案/账户/原 SDK grant 的关联；刷新和未知网络结果下的约束。公开文档未给出的项目原 attempt 关联，仍须由服务端 capability/profile/lease 设计保证，不能只靠客户端传一个 UUID。

在上述证据及最终安全结论齐全前，保留原已消费 fingerprint，不基于猜测的900秒 TTL 清理，也不宣称超出保留范围的跨 attempt 防重放。未知供应商结果不自动重发；沿用现有15秒内部预算及显式新 scene/attempt 的既定决定。

## 交接记录

已完成：公开 API 字段、grant 范围、Android 两阶段 token 生命周期及两项 RAM 动作的来源核对。仍待：实际 scheme 的 proof 期限/关联依据、最终同版安全接受、实际受控服务配置及真实供应商/设备验收。没有用其他产品（例如人脸核身、号码校验或短信验证码接口）的 token TTL 代替 Fusion VerifyToken。
