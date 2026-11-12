# E 照片宿主接线边界提案（待 C1 / S1 确认）

日期：2026-10-31。依据 #13 负责人范围确认及 #82 S2 question（issuecomment-6530898145）。本提案不修改 C1/S1 契约或真实上传，不视为他们已接受。

## 问题和现状

ElderPhotoActivity 的 OpenDocument / TakePicture 回调收到结果后才调用 select；仅 cameraPath 经 savedInstanceState 保存。现有 select 之后的 scope/IO 隔离不足以证明外部窗口发起时 A、返回时 B 的隔离。RepositoryScope 只有 environmentId / installationId / userId，不能单独识别 A→B→A 或同账号重新认证的会话代次。

## 建议边界

- C1/S1 宿主提供可信当前 Elder 会话，以及每次登录、退出、账号/环境切换、失效时改变的不可复用会话代次。复用现有会话设施；若缺少此能力，由 C1/S1 确认接口，不由 E 生成可信 actor、operationId 或另造 wire 字段。
- E 在 launch 相机/选择器之前捕获 launch token：宿主代次、scope、请求种类和本次本地请求标识；相机路径只属于该请求。不使用回调到达时的身份去重新归属旧结果。
- 返回时先比较 launch token 与当前可信会话，再读取 URI/解码/写私有目录。代次不同、scope 不同、缺 token、会话无效或不是 Elder 时丢弃结果，停止旧 Activity 或显示重新进入提示；不得调用 select/submit，不得将结果写入 B。A→B→A 也必须拒绝旧 A 结果。
- 相同代次才允许交给现有 select；继续保留 S1 已修的异步 IO 捕获 scope、store 共享锁、generation 及迟到响应隔离。Activity 前置检查不能替代后续检查。
- 相机取消/失效只清理该未提交请求的临时 camera 文件和临时授权；不得删除 attempted 原图、命令上下文或其他 scope 草稿。选择器取消不替换当前草稿、不发起提交；既有持久权限按当前使用方式处理，不扩大权限。
- 旋转保留同一次 launch 的可校验 token 与相机路径；冷恢复须由宿主先验证会话连续性。如果无法证明同一代次，默认丢弃待返回结果、清理未尝试临时文件，不从路径或 userId 推断恢复可信身份。
- 真实提交只使用已审 host 的 MedicationPhotoRepository / OnlineCommandRepository 和命令 context；上传结果、授权内容与幂等规则继续按现有契约及 S2 端点执行。

## 需要 C1 / S1 明确

1. 现有哪个可信宿主提供会话代次及失效通知？代次在旋转与进程重建时的保持/失效规则是什么？
2. 会话改变时统一结束旧 Activity，还是 E 持有 launch token 并丢弃回调？建议同时有宿主失效与 E 入口检查。
3. 旧临时文件及 URI 授权由谁清理，如何避免清除 attempted 原来源？
4. 在真实宿主接入前，先补 E 侧可注入的 launch-token 测试是否作为下一小切片，由谁审核？

## 计划验收（尚未执行）

相机和选图各覆盖：A→A 正常、A→B、A→B→A、退出/会话过期/环境切换；拒绝路径断言无 select/import/submit、无 B 私有文件、无成功提示。覆盖外部窗口期间旋转、结果返回前进程死亡后冷恢复、取消、重复/迟到回调、同 scope 新登录；无法证明连续性时默认拒绝。已有 attempted A 来源在换图/失效后仍保留，不因拒绝外部结果被清除。用宿主测试替身证明局部隔离，真实账号/HTTP/Room/journal、授权照护端读取和双设备仍须另验。
