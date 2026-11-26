# 配置参考

[返回 README](../README.zh-CN.md)

## 范围与标识

配置保存在当前 Realm 的 Authentication Flow Execution 中，
不是 JVM 全局 IMAP 服务器设置，也不需要在 Java 中修改服务器地址。
不同 Realm、不同 Execution 可使用不同配置。

| 标识 | 值 |
| --- | --- |
| Provider ID | imap-authenticator |
| 显示名称 | IMAP Authentication |
| 支持执行要求 | REQUIRED、DISABLED |

DISABLED 会停用该执行；管理员必须审核整个 Flow，不能把禁用配置当作连接失败处理。

## 所有参数

| 显示名称 | 配置键 | 默认值 | 校验与说明 |
| --- | --- | --- | --- |
| IMAP Host | host | 无 | 必填；受限的字母、数字、点、连字符模式 |
| IMAP Port | port | 993 | 整数，1–65535 |
| TLS Mode | tls | SSL | 仅 SSL 或 STARTTLS，大小写固定 |
| Connection Timeout | connectTimeout | 5000 | 整数，100–60000 ms |
| Read Timeout | readTimeout | 5000 | 整数，100–60000 ms，也用于写入超时 |
| Auto Create User | autoCreate | false | 仅 true 或 false |
| Login Identifier | identifier | Email | 当前只接受 Email |
| Allowed Default Roles | allowedDefaultRoles | 空 | 逗号分隔的稳定角色标识 |
| Allowed Default Groups | allowedDefaultGroups | 空 | 逗号分隔的稳定 Group 标识 |
| Allowed Email Domains | allowedEmailDomains | 空（未配置） | **必填**；逗号分隔的精确邮箱域名，见[邮箱域名白名单](#邮箱域名白名单) |

host 会转为小写。现有字符校验不等于完整 DNS 或 IPv4 地址合法性校验，
不能依赖它证明目标是可连接的服务器；IPv6 字面量和国际化主机名未受支持。
无效或缺失的必填设置不会自动放行，用户仍看到通用失败提示。

## TLS 与用户名

| 模式 | 典型端口 | 行为 |
| --- | --- | --- |
| SSL | 993 | 连接起始即加密 |
| STARTTLS | 143 | 必须成功升级后才允许认证 |

选择 STARTTLS 不会自动把 993 改成 143。
两种模式均启用证书链与主机名验证，只允许 TLS 1.2 / 1.3。
私有 CA 应受控导入 JVM 信任库，不使用信任全部证书或关闭主机名验证。

使用完整邮箱进行 IMAP 登录：输入先去除前后空白，再整体小写。
仅接受受限 ASCII 格式，最大输入长度 254；这不是完整 RFC 邮箱语法支持。
不支持国际化邮箱或大小写敏感的本地部分。
密码不 trim，长度须为 1–4096 个 Java 字符单元，且不保存。

## 绑定格式

| 用户属性 | 示例 | 说明 |
| --- | --- | --- |
| imap.auth.binding | mail.example.com:993:SSL | 小写 host、port 与 TLS Mode |
| imap.auth.identity | user@example.com | 规范化邮箱主体 |

两个属性都由管理员控制，Profile 配置见[安装说明](installation.md)。
插件每次检查当前绑定、用户邮箱与认证邮箱是否一致。
不会根据别名推断或仅按同邮箱关联普通本地用户。

## 邮箱域名白名单

> **Breaking change：** 自本版本起 `allowedEmailDomains` 为必填项。
> 未配置、为空或无效时，该 Execution 拒绝**所有** IMAP 登录。
> 升级前请先按[部署说明](deployment.md#升级到含邮箱域名白名单的版本)写入配置。

```text
allowedEmailDomains=example.com,example.co.jp
```

该设置声明“本 Execution 只信任 IMAP 服务器对这些域名的认证”，
对所有 IMAP 登录生效，包括已绑定的既有用户和自动创建，不仅限于 autoCreate。

匹配规则：

- 输入邮箱规范化后，取 `@` 之后的部分与列表**精确比较**，不区分大小写。
- 不做后缀匹配：`example.com` 不匹配 `mail.example.com`、`notexample.com`、
  `example.com.attacker.net`。子域名必须逐一列出。
- 域名不匹配时，在查找 Keycloak 用户、设置用户和连接 IMAP **之前**拒绝；
  用户看到与其他失败相同的通用提示。

配置规则：

- 条目前后空白会去除，并统一转为小写；规范化后重复的条目静默去重。
- 每个条目须为 ASCII 域名：仅字母、数字、连字符和点；每个 label 长 1–63，
  不以连字符开头或结尾；整个域名最长 253。国际化域名只能使用 punycode（`xn--…`）。
- 拒绝空条目（如 `a.com,` 或 `a.com,,b.com`）、通配符（`*`、`*.example.com`）、
  前导或末尾的点、`@`、空格、端口、路径和非 ASCII 字符。
- 最多 100 个不同域名，整个配置字符串最多 16000 个字符。
- **任意一个条目无效，整个配置即无效**，该 Execution 拒绝所有登录（fail-closed）。
  不提供通配符或禁用开关。

修改在下一次登录请求时立即生效，不需要重启或重建镜像。

| 日志事件 | 含义 |
| --- | --- |
| `IMAP_DOMAIN_CONFIG_INVALID reason=missing` | 未配置或为空 |
| `IMAP_DOMAIN_CONFIG_INVALID reason=invalid_entry` | 存在非法条目 |
| `IMAP_DOMAIN_CONFIG_INVALID reason=too_many_domains` / `too_long` | 超过数量或长度上限 |
| `KEYCLOAK_AUTH_FAILED stage=IMAP_DOMAIN_CONFIGURATION` | 上述配置错误导致的拒绝 |
| `IMAP_DOMAIN_REJECTED reason=domain_not_allowed` | 输入邮箱的域名不在列表中 |

日志只记录固定事件和原因，不输出邮箱、域名、配置值或密码。

同一 Realm 使用多个 IMAP Host 时，每个 Execution 只列出由该 Host 负责的域名；
客户端 Flow Override 引用的 Flow 也须分别配置。
插件只能检查提交的登录名；IMAP 服务端若允许别名或跨域代理登录，
管理员仍须审核其账号语义（见[安全说明](security.md)）。

## 允许列表

```text
allowedDefaultRoles=realm:employee-role-id,client:application-client-id:reader-role-id
allowedDefaultGroups=group:employees-group-id
```

这些示例 ID 均为占位值，必须替换为当前 Realm 管理 API 返回的内部 ID。
CLIENT_UUID 对应客户端内部 id，不是 clientId 显示名称。
employee、/employees 等名称和路径不能直接作为允许项。

允许的角色格式为 realm:ROLE_ID 或 client:CLIENT_UUID:ROLE_ID，
Group 格式为 group:GROUP_ID。各 ID 仅支持字母、数字、下划线和连字符。
列表允许条目前后空白，但不允许通配符、重复条目或空条目；
每个配置字符串最多 16000 个字符。

角色 ID、所有者和 Realm 必须匹配，Group ID 必须在当前 Realm 中存在。
空列表保留保守策略，不承认未知的自定义默认角色或默认 Group。
允许列表只检查已存在的默认权限，不主动 grantRole 或 joinGroup。

每个自定义 Composite 成员也须独立批准。
被继承的 Group、所有父 Group 及所有后代 Group 均须批准，
其 Role 与 Composite 闭包仍须通过检查。
已知 Keycloak 管理权限不能通过允许列表豁免。
完整拒绝规则与检查边界见[安全说明](security.md)。

## 修改设置的影响

- 修改 host、port 或 tls 会改变绑定值，已有用户需要管理员重新审查关联。
- 修改超时不改变绑定值，但影响新认证请求的等待时间。
- 修改 allowedEmailDomains 不改变绑定值；从列表移除某域名后，该域名的既有用户也无法再通过 IMAP 登录，
  但其已有 SSO 会话不会自动撤销。
- 关闭 autoCreate 后，已存在且正确关联的用户仍可登录；Keycloak 中不存在的地址在连接 IMAP 前即以通用失败拒绝。
- 开启 autoCreate 后，未知地址的每次提交都会连接 IMAP，不计入任何用户的失败次数，需要入口限流。
- 允许列表只影响自动创建路径，不撤销管理员给既有用户的授权。
- 修改 Role、Composite 或 Group 后，后续自动创建会重新检查当前权限图。
- 改变 Flow 或 Cookie 策略不会自动撤销旧会话。
- 修改 Realm 名称或角色名称不应被当作身份关联迁移工具。

配置变更应先在隔离 Realm 验证，并记录原值与恢复步骤。
