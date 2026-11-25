# 手机号联调隐私材料审查说明

2026-11-25。Refs #118。用户已确认运营者为 **CSI301 Group33 项目组**，政策邮箱为 **rui.zheng23@student.xjtlu.edu.cn**，并授权必要时直接操作电脑。已选独立联调环境，保留旧预览；平台操作由负责人承担，S2继续提供实现和配置，不为Hobby成员邀请升级付费。

## 交付入口

- [中文完整草稿](PRIVACY_POLICY_DRAFT.zh-CN.md)
- [英文完整草稿](PRIVACY_POLICY_DRAFT.en.md)
- [中英静态HTML审阅版](site/index.html)
- [部署与配置交接清单](../PHONE_INTEGRATION_DEPLOYMENT.md)
- [无秘密配置台账CSV](phone-integration-config.csv)
- [源码版本与摘要](source-snapshot.json)

HTML为由同版Markdown生成的可读副本，不是已发布网址；不加载外部资源或运行JavaScript。没有把用户联系邮箱之外的后台账号/邮箱或任何秘密写入材料。

## 政策尚未转为正式版的实际原因

| 待核事实 | 负责协作 | 需要补的证据 |
| --- | --- | --- |
| 认证SDK实际字段、运营商/图形/友盟子服务与接收主体 | C1/S1，供应商方案负责人 | 最终合并APK、manifest/权限和初始化/网络核验；当前打包Fusion1.2.16、UM common9.5.6/asms1.8.0并调用useSDKSupplyUMSDK(true) |
| 保存期限、proof fingerprint、防重放与数据权利 | S2牵头、负责人承接执行 | 分数据类别的期限、清理触发/方法、响应时限、账号/对象/备份删除验证；不能以900秒grant猜proof保留期 |
| 云端区域、接收方与跨境处理 | S2提供实际资源，负责人核对安排 | Render Singapore已见；新Supabase project/地区/存储和完整数据流未核验，不假定全在境内 |
| 生产保护与政策承诺一致 | S1/S2/C1 | 同意前不初始化、HTTPS、当前授权、数据日志、保存与删除机制的最终证据 |

这些是本项目具体代码和数据流引出的发布前事实核对，不以课程项目身份替代个人信息要求，也不把一份政策草稿声明为法律合规认证。项目不招募真实患者或未成年人，业务资料继续只用虚构数据。既定同意/权限/删除缺口留给相关owner实现，文档不虚构已实现按钮。

## 官方依据（2026-11-25核对）

- [阿里云融合认证合规指南](https://help.aliyun.com/zh/pnvs/security-and-compliance/converged-certification-services-compliance-guide)：告知实际SDK，取得同意后初始化。
- [Android融合认证接入](https://help.aliyun.com/zh/pnvs/developer-reference/the-android-client-access-3)：组件依赖、初始化与隐私界面配置；不把iOS示例当Android实测。
- [号码认证政策](https://terms.aliyun.com/legal-agreement/terms/suit_bu1_ali_cloud/suit_bu1_ali_cloud202112211045_86198.html)、[短信SDK政策](https://terms.alicdn.com/legal-agreement/terms/privacy_policy_full/20230922101800634/20230922101800634.html)、[图形认证政策](https://terms.alicdn.com/legal-agreement/terms/privacy_policy_full/20240510173033685/20240510173033685.html)。
- [融合认证服务条款](https://terms.alicdn.com/legal-agreement/terms/product/20230213121650869/20230213121650869.html)、[友盟政策](https://www.umeng.com/policy.html)：当前UM依赖与登录安全处理不能从披露中删除，采集范围需按实际版本核对。
- [Render隐私政策](https://render.com/privacy)、[Supabase隐私政策](https://supabase.com/privacy)。托管品牌政策不替代项目自己的数据处理披露。
- [个人信息保护法官方公布文本](https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/bgt/art/2023/art_f374e8245320413181742e6d1baf4366.html)：参考信息告知/保存必要性、个人权利、敏感信息与境外提供的规定。本文未作特定部署已满足法律条件的结论。

## 本轮验证范围

仅核对并生成文档/静态文件、来源摘要、仓库规范和内容结构；Render操作为现有GitHub登录与只读查看Overview/Settings。没有创建/修改/部署服务、加入成员、读取/导出secret值、供应商认证调用或迁移写入。静态页本地预览属于审阅，不是上线验收。

完整性清单按当前已跟踪字节重新生成，并补齐主干已有的48个未登记路径；这些路径仅新增摘要，没有修改其运行内容。CSV已统一LF，避免工作区与Git检出字节不一致。
