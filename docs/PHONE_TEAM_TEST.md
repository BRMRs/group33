# 团队手机号登录测试包

Refs #10 / #49，C1 的 main 整合候选。复用 #88 的阿里云一键登录和 SDK 短信认证；不另建手工验证码系统。

## 本次统一了什么

- 统一登录入口、首次身份选择、照护者用户名与 main 的业务导航。
- 手机号认证结果写入 S1 公共会话；业务仓库读取同一个会话实例。没有复制旧的 `PhoneSessionStore`。
- 手机号测试配置启用后，不显示匿名登录按钮，也不允许用匿名账号冒充手机号登录成功。
- 认证和业务使用同一个服务地址；手机号账号与旧匿名测试数据分开保存，不自动迁移或合并。
- 语言按钮继续显示当前语言，沿用 #19。保留键盘留白处理和服务端尚未提供密码功能时的明确提示。

## 现在还不能直接分发使用的原因

客户端接线不等于服务端已支持手机号身份访问配对、照片和计划。当前 main 尚未包含 #88/#94 的手机号服务，#96 的账号／安装绑定仍待共同确认。这个候选沿用旧试验请求字段，没有自行实现未接受的新字段或服务端授权。

更新后的 #88 保留原有、默认关闭的服务端认证试验代码，供 S1/S2 继续审核；不代表它已部署或可访问业务。另一个部署分支 `chore/16-render-main-deployment` 记录了 `https://group33-pr110-preview.onrender.com`，2026-11-08 健康检查正常。那是匿名业务的内存测试环境，不是填写密钥的管理网页，也不是手机号服务已经接通的证明；该部署分支尚未合入当前 main。

还需 S1/S2 提供同一套可核验配置：

1. 已部署手机号认证及所需业务接口的团队 HTTPS 地址。
2. 实际应用的 HTTPS 隐私政策地址。
3. 阿里云方案登记的包名及签名证书 MD5。
4. 服务端密钥配置入口、配置负责人，以及手机号账号访问业务的已审接线。

AccessKey ID/Secret 由负责人配置在服务端机密设置，运行时读取 `ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET`。测试者安装 APK 时不输入 AccessKey。当前没有已核实的部署控制台入口，不应按猜测把密钥填入其他网页。

## 构建者操作

拿到以上配置后，从仓库根目录运行（替换占位值）：

```text
gradlew :android-app:assemblePhoneTest -PphoneAuthBaseUrl=https://TEAM_SERVICE -PphoneAuthPrivacyPolicyUrl=https://APP_POLICY -PphoneAuthSigningMd5=REGISTERED_CERTIFICATE_MD5
```

这个任务检查地址、政策配置、包名和实际签名证书，不调用阿里云，也不证明服务端接口已经可用。普通 `assembleDebug` 不代表已配置手机号服务。

默认使用构建机的 Debug 签名。若阿里云方案登记的是另一个团队测试证书，构建者在受控环境设置以下环境变量；不把私钥或密码提交进 Git：

```text
GROUP33_TEST_KEYSTORE
GROUP33_TEST_STORE_PASSWORD
GROUP33_TEST_KEY_ALIAS
GROUP33_TEST_KEY_PASSWORD
```

团队登记的包名不是默认 `com.group33.medicationreminder` 时，还需显式核对实际 applicationId，并设置 `phoneAuthPackageName` 校验值；该参数不会替你修改 applicationId。

产物为 `android-app/build/outputs/apk/debug/android-app-debug.apk`。构建者保留对应提交、APK SHA-256、服务版本与实际验证结果，队友安装同一个已验证 APK。各人自己重新编译时，也必须通过同样的服务和签名检查。

## 设备验证

1. 从一键登录进入 SDK；验证成功后，新用户选身份，老用户保留服务端原身份。
2. 从短信备用入口输入号码，继续进入现有 SDK 短信认证；取消后可返回，重新进入不残留上次输入号码。
3. 首次长者直接进入对应页面，首次照护者填写用户名。登录本身不授予其他长者的数据权限。
4. 用同一服务验证配对、长者同意后授权、退出、重开和账号切换。手机号登录成功但业务返回拒绝时，不得回退匿名账号绕过。
5. 单独记录真实 SDK、服务端、业务授权和两端结果；虚构响应／本地 HTTP 夹具不算真实手机号登录成功。

当前供应商代码仅装配到 Debug 测试变体。Release 保持既有未接生产手机号服务的边界，不携带本次 SDK 或其权限。正式 Release 接线另行接受审核。
