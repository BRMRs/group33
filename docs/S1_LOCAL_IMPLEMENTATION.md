# S1本地规则初版：代码、测试与接入边界

关联：[#24](https://github.com/BRMRs/group33/issues/24)、[#12](https://github.com/BRMRs/group33/issues/12)。本地授权/规则见[ADR-0010](adr/0010-s1-local-v2-policy.md)。负责人现已授权将本次S1独立改动提交、推送并创建Draft PR；不合并、不代签，不混入#25文档分支。

## 已实现

| 文件 | 实际行为 | 验证范围 |
|---|---|---|
| `android-app/.../core/reminder/ReminderTimingPolicy.kt` | 共用Clock、30分钟跟进时点、两小时精确截止、十分钟延后不越截止、已结束催促不重开 | V201的纯规则部分 |
| `android-app/.../core/reminder/ProbeRoundPolicy.kt` | 两次各60秒；任一次回复阻止两次失败告警；未执行与无应答分开；已有告警不再要求创建；晚回复标连接恢复 | V204的判定部分；不验证认证/跨轮匹配或Server并发 |
| `android-app/.../core/reminder/FollowUpPolicy.kt` | 照护结论优先、无法确认仍待核实；15/60秒投递评估；0/15/60秒名义重试和收到后2/5分钟再提醒 | V203/V205的本地规则，不证明通知送达 |
| `android-app/.../core/data/contract/CommandRecoveryPolicy.kt` | 原operationId恢复；拒绝不自动重放；成功/缓存未更新只刷新；用户/安装/环境变化拒绝旧操作 | R01/R04既有公共命令类型的真实决策逻辑 |

代码路径中的省略前缀是`src/main/kotlin/com/group33/medicationreminder`。公开入口为evaluate、next、assessDelivery、conclude和decide；不是新增HTTP路由。未修改旧预览或Tang的#35/#36，未改变Shared DTO/JSON/依赖版本。

## 输入怎样接入

- E的Repository在真正记录动作时也调用ReminderTimingPolicy，不能仅让页面隐藏按钮。Clock与页面/调度器一致。到期校验和持久化必须按实际事务边界再检查。
- ProbeRoundFacts只允许装入已经鉴权并匹配当前用户/环境/长者/轮次/尝试的事实。responseAt是对应实际接收时刻，不是历史同步时间或尚未发生的预定事件。已发生的有效回复及已创建告警以记录存在为准，系统时间回拨不能让它们失效，也不能仅凭回复时间早于发送时间拒绝一条已验证的真实回复。S2仍需在服务端事务内裁决超时与回复；Android不能据这份投影自行宣布跨端探测验收。
- AlertProgress的transmissionCount、lastTransmissionAt、receivedAt、reminderCount、lastReminderAt、handled和deliveryBlocked从真实进度恢复。处理每个Transmit/Remind前，适配器必须原子认领、保存并使用同一alertId；反复调用next只会返回同一个待办，不提供exactly-once或进程持久化。
- receivedAt只能是可信设备回执，不能是推送平台“接受发送”；blocked必须来自明确权限/传输证据，未知不能伪装已知。通知可见与回执仍应分别记录。
- handled仅代表真实已处理/解决，不能把已读、已联系或Unable to confirm传成true。RemindersExhausted只表示初版两次再提示用完，跟进待办仍保留。正常按收到后2/5分钟再提示；若首次再提示执行晚了，第二次至少距实际首次3分钟，避免恢复时连发；适配器需连同reminderCount保存lastReminderAt。
- FollowUpPolicy的currentCaregiverOutcome必须是当前有权限、已被Server接受的有效版本，不能直接取最后到达的一条。保留双方原始记录和审计由存储实现，conclude不删除或改写记录。
- CommandRecoveryPolicy不认证、不发送也不生成新意图；真实adapter恢复时仍要核对原始payload、当前授权及elder/target边界。

## 已新增测试

`android-app/src/test/kotlin/com/group33/medicationreminder/core/reminder/S1PolicyTest.kt`与`CommandRecoveryPolicyTest.kt`：34个测试方法，覆盖真实生产策略代码，不用fake证明Room/HTTP行为。所有时间均由Clock控制，不等待真实分钟。

覆盖：截止前/恰好/后；30分钟仍能响应；延后目标恰好deadline；单次Clock采样；四种探测回复组合；漏执行/晚发/迟到/已有告警/系统时间回拨；15/60秒边界；重试同ID/上限/晚恢复不突发；2/5分钟提醒及晚恢复防连发；耗尽不自动结案；照护三种结果优先；无确认不等于未服用；四种在线命令状态及三维scope变化。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/rui/Android/sdk \
./gradlew :android-app:testDebugUnitTest --tests '*S1PolicyTest' --tests '*CommandRecoveryPolicyTest'

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/rui/Android/sdk ./gradlew gate1Check test --console=plain
python3 scripts/check_repository_policy.py --branch feature/24-s1-local-policy --pr-title 'feat(android): add initial s1 reminder and recovery policies'
shasum -a 256 -c docs/contracts/GATE1_ARTIFACTS.sha256
```

实际检查数/结果随本次交付记录保存；不能用旧SHA CI冒充新版本远端CI。这些测试不等于V201–V205或全部P0场景验收通过。

## 接下来需要S2配合

1. 共同确定组/动作身份、成员冻结、Server全收/全拒及receipt查询；提供重复提交、部分成员失败和丢回执的真实事务测试。
2. 提供探测请求/应答和鉴权/轮次匹配的字段，服务端调度及重启恢复、60秒等待、迟到与超时的事务裁决/去重。
3. 提供当前照护者登记/更正版本与审计API，关系撤销和乱序响应不能恢复旧结论；不能伪装Elder动作。
4. 告警持久化、相同alertId重试、回执/可见/已联系/已处理区分；验证小米推送账号/SDK在MIUI10上的通知及回执，明确完全断云LAN方案。
5. 与S1对齐真实Room/outbox/HTTP接入，再做同型号双设备后台、锁屏、离线和恢复测试。当前连接的是MI PAD 4 PLUS / Android8.1 API27 / MIUI10，第二台未读取。

## 本轮明确未完成

真实Room事务、整组全收/全拒落库、HTTP/Server业务、回执持久化、平台闹钟/推送、设备权限/开机恢复、双设备送达与实时预算测试均未完成。对应实现仍在#12/#14/#15/#17/#18/#20等普通任务，不能把本地投影测试勾成生产验收。

34个规则用例是可交接的S1代码草稿；未经本人阅读/复现/正式review，不计为真人签署或Gate1关闭。最终代码分支略超500行建议规模的主要原因是四个相关规则边界及其可读反例成对交付；不混入UI、依赖升级或协议冻结。
