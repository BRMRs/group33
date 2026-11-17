# E 照片流程本地整合与复查 — 2026-10-31

## 版本与保护

功能分支 `feature/13-elder-photo-submit`，先 fast-forward 负责人最终修正 `aa82dda9db6f3afdfe547f018fb5b6f162f394ac`，再合入 main `b2aeb492a97ea1e17ced4da22972f34cae44ea73`，本地合并提交 `350548cdb471f0536b9cb1ebbaa28e91d4399c02`。上述验证在本地合并后执行；随后按成员授权提交证据并推送功能分支，未向 main 写入。相对 aa82dda，android-app/shared/CONTRIBUTIONS.md 无差异；S1 的超时、跨 scope 导入、并发及原图缺失保护完整保留。

原工作区和照片工作区的 gradle.properties 均已备份，照片工作区另有 stash 保留，恢复为逐字节相同内容，SHA-256 `f6c913d9986b34538e3b19ace576f00efdcb8dea3c1b4acad221d551814837da`，未提交。备份、原始日志和截图在 `/tmp/group33-e-followup-20261031/`。

## 构建和测试

使用 JDK17 和 SDK35 编译；仓库外 init 脚本只更换测试 applicationId 为 `com.group33.medicationreminder.pr82merge`，业务源码及资源不变，保护原安装包。首次误用 Studio 内置 JDK25 导致 Gradle 启动失败，改用项目基线 JDK17 后成功。

`gate1Check test :android-app:lintDebug :android-app:assembleDebug :android-app:assembleDebugAndroidTest --offline --console=plain -I /tmp/pr82-merge-device.init.gradle`：BUILD SUCCESSFUL，129 tasks，50 executed / 4 from cache / 75 up-to-date。

JVM 报告：Android Debug69 / Release69、Shared18、Server43，共199次报告执行，零失败/错误/跳过；不声称所有基础测试重新执行。lint 0 errors / 9 warnings。仓库策略、diff 检查通过；151项摘要以工作区原生行尾及提交版 gradle.properties 核对相符。未提交的本机 gradle.properties 与提交摘要不同是保留的本地配置，不改摘要以掩盖。

## 设备和人工操作

测试设备仅 API37.1 Medium_Phone 模拟器，1080px / 540dpi = 320dp，font_scale 2.0。通过真实 MAIN/LAUNCHER 进入长者及照片页，不直接打开内部 Activity。照片只使用模拟器场景及生成的纯色 PNG，无真实药品/个人数据。

中英文均已从 launcher 进入照片流程，实际完成系统相机取消、拍摄、预览、重拍、系统选图取消、Downloads 中选择生成 PNG、私有预览、确认使用。320dp/200% 下上述按钮可滚动到达并点击，文字无观察到的省略或覆盖。长者没有药名/剂量输入。页面常驻上传未接通提示，点击使用后中文为“暂时无法上传，照片已保留在本机，尚未发送。”，英文为“Upload is not available. Your photo is kept on this device and has not been sent.”。

双语截图在本目录。英文原整套脚本在系统选择器最近图片页未定位到 fixture；人工改用 Downloads 补查。英文补查脚本最后误用 `not sent` 匹配实际 `not been sent` 而断言失败，实际 UI 文字核对正确。中文整套脚本同样在选择器定位中断，随后明确选择 Downloads 的补查通过。保留原日志，不将任一整套脚本声称为全绿；取消与重拍来自同次版本前段实际操作。UIAutomator 曾有 null-root，故结论以可见截图/后续实际操作及专项设备测试分别说明。

## #59 键盘场景与未测项

本机 SDK system-images 只有 android-37.1，只有 Medium_Phone AVD，无 API35 镜像/设备。磁盘可用空间在模拟器启动后降至约380MiB，未下载额外镜像、未清理用户文件。**要求的 API35 中英文320dp/200%键盘验收未执行，不能记通过。** #59 独立工作区已 fast-forward 至6166778，JDK17/ANDROID_HOME 明确后 APK 编译通过；这不是 API35 运行证据。

真实上传/身份/HTTP/Room journal、会话切换期间的外部 Activity 回调隔离、完整进程恢复、真实相机兼容、TalkBack实际听验、灰阶及双设备仍未验收。本轮不会把无真实上传宿主的页面当作已上传。

## 接线边界提案

见 [HOST_BOUNDARY_PROPOSAL.md](HOST_BOUNDARY_PROPOSAL.md)，已在 [#13 请求 C1/S1 确认](https://github.com/BRMRs/group33/issues/13#issuecomment-6540152773)。提出 launch 前捕获可信会话代次和scope、返回前核对、A→B→A也拒绝、旋转/冷恢复无法证明连续性则拒绝；没有擅自扩充共享接口或接真实上传。

## 专项设备回归与恢复

本次独立 API37 实际执行 PrivatePhotoDraftStoreTest 8项 + ElderPhotoLayoutTest 2项，`OK (10 tests)`；含跨scope延迟IO、同profile独立store并发、原图消失拒绝及中英文320dp/200%布局。不是API23/35结果，也不证明外部窗口A→B回调隔离。原始记录 `/tmp/group33-e-followup-20261031/photo-device.log`。

设备已恢复物理420dpi、font_scale1.0；独立照片app和测试包卸载，测试PNG删除，原开发安装包保留。没有修改系统语言、TalkBack或灰阶设置。
