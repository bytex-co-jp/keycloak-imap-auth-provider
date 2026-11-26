# 故障排查

[返回 README](../README.zh-CN.md)

## 排查顺序

LOGIN_ERROR 中的 invalid_user_credentials 和 userId=null
不能证明邮箱密码错误。前置配置、用户关联、IMAP 通信和创建后权限检查
都可能产生相同的最终提示。

先在隔离请求中定位插件阶段，再核对相应配置。
不要移除安全检查、关闭 TLS 校验或修改 master Realm Flow 来试错。

## 安全日志

短期在服务启动配置中设置：

```text
KC_LOG_LEVEL=INFO,jp.co.bytex.keycloak.imapauth:DEBUG
```

这里的完整名称是实际日志分类，必须保留。
修改运行参数需按服务的变更流程进行，本文件不自动重启服务。

插件成功阶段使用 DEBUG，故障主要使用 WARN；
只记录固定事件、原因、属性名和异常类型，不输出原始异常消息或堆栈。
不记录密码、邮箱、Token 或请求正文。
其他 Keycloak、代理和应用日志须另行管理，不能据此假定它们也已脱敏。

```sh
docker logs --since 5m keycloak
```

不要开启 Mail debug、HTTP 请求正文或全局认证 TRACE。
排查完成后恢复该分类为 INFO，保留必要告警。

## 事件参考

| 事件 | 含义 |
| --- | --- |
| KEYCLOAK_AUTH_START | 自定义 action 已开始 |
| KEYCLOAK_PROFILE_CHECK_FAILED | 属性保护失败，通常尚未连接 IMAP |
| KEYCLOAK_AUTH_FAILED | 系统/处理异常，stage 表示失败阶段，exceptionType 表示异常类型 |
| IMAP_FORM_INVALID | 密码为空或超出实现长度限制 |
| KEYCLOAK_USER_LOOKUP | 开始用户查询 |
| KEYCLOAK_USER_LOOKUP_FAILED | 重复邮箱策略、关联/邮箱不符、禁用、锁定或最终身份复查失败 |
| IMAP_AUTH_START | 开始创建 Mail Store 并连接 |
| IMAP_CONNECTION_FAILED | 连接、超时或其他 Mail 通信失败 |
| IMAP_TLS_FAILED | 原因链中存在 SSL/TLS 异常 |
| IMAP_RUNTIME_FAILED | Provider、链接或运行时异常 |
| IMAP_AUTH_REJECTED | 资格信息被拒绝或 Store 未处于已连接状态 |
| IMAP_AUTH_SUCCESS | IMAP 验证成功，后续仍可能被 Keycloak 检查拒绝 |
| IMAP_CLOSE_FAILED | 关闭时出现 MessagingException，不覆盖此前结果；DEBUG |
| KEYCLOAK_USER_CREATE | 开始创建用户 |
| KEYCLOAK_USER_CREATE_FAILED | 自动创建关闭、用户名冲突或用户存储创建失败 |
| DEFAULT_PERMISSION_CHECK_FAILED | before_create 或 after_create 阶段的权限检查拒绝 |
| KEYCLOAK_AUTH_SUCCESS | 已绑定用户并调用 success() |

KEYCLOAK_AUTH_FAILED 的已知 stage 包括 KEYCLOAK_PROFILE_CHECK、
IMAP_CONFIGURATION、IMAP_FORM_INPUT、KEYCLOAK_USER_LOOKUP、
IMAP_AUTHENTICATION、KEYCLOAK_USER_CREATE、KEYCLOAK_AUTH_COMPLETE。
这些字段指示阶段，不包含可直接展示给最终用户的服务器异常详情。

## IMAP 连接与密码拒绝

IMAP_CONNECTION_FAILED：检查 DNS、出口、目标端口和超时。
SSL 通常为 993，STARTTLS 通常为 143，模式改变不会自动改端口。
可使用受控连通性工具测试网络，但不要在共享终端记录明文密码。
部分 IMAP 服务器对错误密码的响应会被邮件库报告为一般错误，此时日志显示 IMAP_CONNECTION_FAILED
而不是 IMAP_AUTH_REJECTED；同一服务器上正确密码能够登录时，应优先怀疑密码错误。

IMAP_AUTH_REJECTED：检查完整邮箱、服务器是否支持该密码认证方式、
IMAP 端锁定以及输入大小写策略。
插件规范化邮箱，但密码原样传递。
其他工具登录成功并不能自动证明当前 Execution 的主机、TLS、用户名和信任库相同。

IMAP_RUNTIME_FAILED：检查镜像内 Mail/Activation 依赖和重复 JAR。
不要为修复类冲突而关闭证书验证或打包整套 Keycloak 类。

## SSL/TLS 握手

IMAP_TLS_FAILED 时核对证书有效期、完整链、主机名和 JVM 信任库。
OpenSSL 和 JVM 的信任来源可能不同，TCP 端口可达也不代表 TLS 验证成功。
STARTTLS 必须成功，不可通过允许明文降级处理故障。

## User Profile 与身份绑定

若出现：

```text
KEYCLOAK_PROFILE_CHECK_FAILED reason=missing_or_invalid_attribute attribute=imap.auth.binding
```

先检查该属性，再检查 imap.auth.identity；两项都必须存在。
仅补一项仍会失败。设置要求见[安装说明](installation.md)。

reason=attribute_not_admin_only 表示 edit 权限不严格等于 admin；
检查单值、Always、非必填和管理员可编辑，普通用户不得编辑。

Profile 定义正确但用户关联失败时，检查受控 host:port:TLS 和邮箱主体，
以及 user.email、enabled 与锁定状态。
更换 IMAP 端点后不要自动给所有用户改绑定。
同邮箱的普通本地账号仍应拒绝，须管理员独立审核。

## 自动创建与默认权限

autoCreate 默认 false。此时 Keycloak 中不存在的地址不会连接 IMAP，
插件日志为 `KEYCLOAK_USER_LOOKUP_FAILED reason=unknown_user_auto_create_disabled`。
期望自动建号时，检查 Execution 是否保存为 true、
Realm 是否关闭 Duplicate Emails、用户名/邮箱是否冲突。
并发创建冲突需要重试，不应重查后接管本地账号。

DEFAULT_PERMISSION_CHECK_FAILED 在 IMAP 成功后也会拒绝：
检查默认 Role、所有 Composite 成员、Group 父级/后代及实际创建后的权限。
default-roles-* 根名称本身不是拒绝条件。
自定义成员须按 ID 批准；管理权限不能加入允许列表后直接豁免。
见[配置参考](configuration.md)与[安全说明](security.md)。

## Email Verified 未更新

确认已使用包含该功能的镜像，且出现 IMAP_AUTH_SUCCESS 与最终 KEYCLOAK_AUTH_SUCCESS。
检查绑定、保存邮箱、enabled 和创建后权限检查。
失败或冲突不会更新，已有 verified=true 不重复写入。
旧代码回滚不会撤销已提交的标记。
代码与单元测试支持该行为，不代表每个实际环境已验证全部场景。

## 失败后重复用户名区域

原生 AuthenticationContextBean 在上下文已有用户时可能令 showUsername() 返回 true，
布局会显示已识别用户名，再加上自定义表单，形成重复输入区域。
这不等于失败路径调用了默认 Username Password Form。

当前修复仅在该页面的展示模型中抑制原生用户名展示，
保留失败处理的用户上下文；错误提示放在自定义表单内。
若仍复现，检查是否部署了修复镜像、主题覆盖或缓存，
并只读审查 Flow 是否还配置了本地密码表单；不要先修改 Flow 掩盖问题。

## Brute Force 锁定

锁定日志表示应查看 Realm 的失败与锁定记录。
插件不会让正确 IMAP 密码绕过 Keycloak 锁定。
只有托管用户会被计数；IMAP 认证成功会清零失败次数。
Realm 未启用 Brute Force Detection 时不会计数或锁定。
IMAP 故障期间反复提交可能影响托管用户的失败状态，
修复系统原因后按管理员政策解除锁定，不关闭全局防护。

## HTTP Cookie 与 HTTPS

HTTP 开发访问可能产生 secure context、Secure Cookie 或跨站 Cookie 警告。
正式访问应使用 HTTPS；核对 Hostname、受信代理转发头、外部回调与 Cookie 域。
代理终止 TLS 时正确配置后端协议和信任边界。
不要以关闭 Cookie 安全策略、放宽凭证日志或修改 IMAP TLS 来解决浏览器问题。
