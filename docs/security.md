# 安全设计与边界

[返回 README](../README.zh-CN.md)

## 密码与 TLS

插件仅在当前请求和 Jakarta Mail 连接调用中使用邮箱密码。
不写入 Credential、User Attribute、Authentication Session 或 URL，
不创建 Keycloak 本地密码，不把 IMAP 密码放入 OIDC Token。

密码按原值传递，不 trim。Java String 不能可靠清零，
请求结束后的内存回收不能替代运行环境的内存、转储和进程访问控制。
代理、APM、自定义过滤器和容器日志同样不得记录表单请求正文。

SSL 从连接开始即加密；STARTTLS 必须成功升级。
启用证书链与主机名验证，只允许 TLS 1.2 / 1.3。
使用 JVM 信任库，不提供信任全部证书或明文回退。
连接成功、失败和超时后都尝试关闭 Store；关闭异常不替换先前认证结果。

## 身份关联与邮箱冲突

既有用户必须满足：

- 用户 enabled，未被 Keycloak Brute Force Detection 锁定。
- imap.auth.binding 与当前 host:port:TLS 一致。
- imap.auth.identity 与规范化的认证邮箱一致。
- 用户实际保存的邮箱与认证邮箱规范化后一致。

同邮箱不等于同一身份。没有受控标识的本地用户默认拒绝关联，
不能由新邮箱持有人自行补写属性接管。
管理员必须独立核验身份后才可以手动关联，
且拥有这些属性写权限等同于拥有账号关联权限。

两个属性必须在 User Profile 显式定义，单值且 edit 权限严格为 admin。
推荐普通用户不可查看，管理员可查看；界面设置见[安装说明](installation.md)。
代码检查不阻止自定义 REST 服务或导入程序直接写用户属性，
必须另行限制这些入口，不开放用户可写的 unmanaged attributes 通道。

Realm 启用 Duplicate Emails 时插件拒绝登录。
邮箱更换和转交的操作流程见[运维说明](operations.md)。

## 邮箱域名信任边界

IMAP 服务器可能托管多个域名。若不限制域名，开启 autoCreate 后，
同一服务器上其他域名的邮箱持有人也能在当前 Realm 自动开户并获得 Email Verified。

`allowedEmailDomains`（必填）声明该 Execution 只信任哪些域名，对所有 IMAP 登录生效：

- 精确域名匹配，不区分大小写，不做后缀或子域名推断。
- 域名不匹配时，在用户查找、设置认证用户和 IMAP 连接之前拒绝，
  因此不会把其他域名的凭证转发到 IMAP 服务器，也不会写入用户级失败计数。
- 缺失、为空或任一条目无效时拒绝该 Execution 的所有登录（fail-closed），不提供通配符或禁用选项。
- 域名不并入 `imap.auth.binding`，既有绑定属性不需要迁移。

限制：插件只检查提交的登录名。IMAP 服务端若允许别名、master user 或跨域代理登录，
仍可能映射到其他域名的邮箱，管理员须审核其账号语义。
被拒绝的域名会立即返回，响应时间可能暴露列表中有哪些域名；域名列表不应视为机密。
允许域名内部的撞库仍需入口限流。
配置格式见[配置参考](configuration.md#邮箱域名白名单)。

## 自动创建

仅在域名被允许、IMAP 成功、autoCreate=true、邮箱未关联且无用户名冲突时创建。
autoCreate=false 时，Keycloak 中不存在的地址在连接 IMAP 前即被拒绝，不会把密码转发给 IMAP 服务器。
username 和 email 使用同一规范化邮箱，enabled=true，写入受控绑定，
不会覆盖既有密码或自动启用被禁用账号。

并发创建依赖用户存储的用户名/邮箱唯一性与事务。
冲突请求失败并请求回滚，不重查后自动接管竞争请求的用户；
用户可在竞争结束后重新登录。
外部存储若不提供相同保证，需要部署方补充验证，不能仅凭 Mock 测试推定安全。

## Email Verified

更新发生在 IMAP 成功之后，并要求最终身份、绑定、邮箱和 enabled 复查通过。
新建用户还须通过创建前、创建后默认权限检查。
已有 Email Verified=true 不重复写入。
密码失败、禁用、别名/绑定冲突或权限检查拒绝时不设置该标记。

该策略信任管理员配置的 IMAP 服务对完整邮箱账号的认证，
不另行发邮件验证，也不推断一个邮箱账号能代表其他别名。
对于服务端接受别名或多主体共用凭证的系统，
管理员必须先审核其账号语义是否适合此信任模型。
此信任边界不是插件能够自动判断的业务事实。

## 默认 Role 与 Group

自动创建前检查 Realm 默认配置，创建后检查实际角色和 Group，
避免用户存储在创建时附加未经检查的权限。
允许列表只批准继承现有默认权限，不调用 grantRole/joinGroup。

内置普通基线为当前 Realm 的默认角色根，以及：

- Realm Role：offline_access、uma_authorization。
- account Client Role：view-profile、manage-account、manage-account-links。

默认角色根按 Realm 返回的稳定 ID 识别，不因 default-roles-* 名称被拒绝。
它的所有 Composite 成员仍要递归检查；根获准不代表所有子角色获准。

自定义角色使用[稳定 ID 允许列表](configuration.md)明确批准。
每个自定义 Composite 成员也须独立批准。
同名但不同 ID、Client 或 Realm 的对象不可混用。

Group 自身、所有父 Group 及所有后代 Group 均须批准；
检查直接角色、祖先继承角色和所有 Composite 闭包。
Keycloak 的父 Group 成员不自动获得后代成员身份，
后代检查是本插件额外采用的保守约束。
每次创建重新读取模型，不缓存顶层批准以跳过后续权限变更。

权限图遇到循环、超过当前实现的深度/规模限制或无法解析对象时失败关闭。
这些检查是自动创建的门槛，不是新的角色管理或审批系统。

## 管理权限拒绝规则

以下权限即使列入允许列表也拒绝自动继承：

- realm-management、security-admin-console、admin-cli 客户端的角色。
- 当前 Realm 关联的 master admin client 角色。
- master Realm 中使用管理命名约定的 *-realm 客户端角色。
- 固定拒绝的 Realm 角色 admin、create-realm。

间接包含这些权限的 Composite 和 Group 同样拒绝。
这不是按任意名称包含 admin/manager 来猜测权限；
代码对上述固定内置标识和管理 Client 采用显式规则。

应用可能把某个自定义角色或 Group 身份映射为高权限，
Keycloak 元数据无法自动证明它属于普通权限。
允许列表代表管理员已经审核，而不是插件给出的安全证明。
无法判断时不批准，改由管理员预建和手动授权。

既有 IMAP 用户后续登录不添加、删除 Role 或 Group，
不会撤销管理员手动授予的管理权限。
未来改变 Composite、Group 或应用授权规则可能改变既有用户的有效权限，
必须由管理员审查，插件不会自动清理。

## Brute Force Detection 与故障

前提：Realm 必须启用 Brute Force Detection。
未启用时插件不计数、不锁定，每次提交都会连接 IMAP。

插件的认证类别为 `password`，与 Keycloak 自带的密码表单相同。
Keycloak 26 的原生 Brute Force 只处理 `password`、`otp`、`recovery-authn-codes` 类别，
因此 IMAP 失败由 Keycloak 自身计数和锁定，插件不另行维护计数。

启用后，不同账号的保护范围如下。插件不能保护所有账号。

| 账号类型 | 失败计数与锁定 |
| --- | --- |
| 托管用户（绑定属性与当前 IMAP 配置一致的既有用户） | 计入该用户，达到阈值后锁定。锁定期间错误和正确密码都在连接 IMAP 前被拒绝，且不重复计数。IMAP 认证成功后，与原生密码登录一样清零失败次数 |
| 未知用户（Keycloak 中不存在，含待自动创建的邮箱） | **不计入任何用户**。autoCreate=false 时在连接 IMAP 前以通用失败拒绝；autoCreate=true 时每次提交都会连接 IMAP，插件不提供保护，依赖邮件服务器的账号保护，见[责任边界](#责任边界与-autocreate) |
| 未关联的本地用户（同邮箱但没有绑定属性，包括用户名与邮箱相同的情况） | 在连接 IMAP 前拒绝，IMAP 失败不计入该本地用户；其本地密码登录的计数和锁定不受影响 |
| 邮箱域名不在 allowedEmailDomains 中 | 在查找用户和连接 IMAP 前拒绝，不计入任何用户 |

计数对象只来自插件为托管用户设置的认证上下文。
插件不写入 Keycloak 的 `ATTEMPTED_USERNAME`，回显邮箱使用插件专用的 `IMAP_ATTEMPTED_EMAIL`。
同一认证会话中先在自带用户名/密码表单失败，再通过 “Try another way” 切换到 IMAP 时，
自带表单留下的 `ATTEMPTED_USERNAME` 会在每次 IMAP 提交开始时删除，
因此 IMAP 失败不会计入之前表单中输入的账号。

### IMAP 登录登记为 password 类型认证

IMAP 认证成功时，插件按 Keycloak 自带密码表单的方式登记 `password` 凭证类型。
Keycloak 依据这一登记在登录成功后清零失败次数；
这一登记也被 “Condition - credential”（conditional-credential）条件读取，
因此该条件对 IMAP 登录的判断与旧版本不同：

| 条件配置 | 旧版本 IMAP 登录 | 当前版本 IMAP 登录 | 影响 |
| --- | --- | --- | --- |
| credentials=`none`，included | 匹配 | **不匹配** | 子流程中的 OTP 等步骤**不再执行** |
| credentials=`password`，not included | 匹配 | **不匹配** | 子流程中的 OTP 等步骤**不再执行** |
| credentials=`password`，included | 不匹配 | 匹配 | 子流程开始执行（可能新增 OTP 或拒绝访问） |
| credentials=`none`，not included | 不匹配 | 匹配 | 子流程开始执行 |
| 其他凭证类型（如 `otp`） | 不变 | 不变 | 仍取决于同一会话中其他认证器的登记 |

这些结果已在隔离的 Keycloak 26.8.1 中用 IMAP + Condition - credential + OTP Form 端到端验证。
当前版本的 IMAP 登录与自带密码表单登录在这些条件下的结果一致；
例如 `none` 条件此前只是因为插件未登记凭证才对 IMAP 登录要求 OTP。

**不得假设升级后所有现有 OTP 配置仍然安全。**
使用 `credentials=none` 或 “password not included” 控制二次认证的 Flow，
升级后 IMAP 登录会跳过该 OTP，必须在升级前调整。
不使用 Condition - credential 的 Flow（OTP Form 设为 REQUIRED，
或使用 “Condition - user configured”）在验证中仍要求 OTP。
检查与迁移步骤见[部署说明](deployment.md#升级到登记-password-认证类型的版本)。

Level of Authentication（acr）、AMR、认证器选择界面、账户控制台的凭证类型列表、
`kc_action=UPDATE_PASSWORD`、认证事件和用户会话在验证中与旧版本一致。
另据 Keycloak 26.8.1 源码，IMAP 位于 Level of Authentication 条件子流程中时，
针对 password 凭证的 `kc_action` 操作（如更新或删除）会要求该级别的认证；
这只会提高要求，未做端到端验证。

### 责任边界与 autoCreate

插件负责正确执行 IMAP 验证、安全地创建和绑定用户、执行域名白名单、接入原生 Brute Force Detection，
以及不引入认证旁路、不泄露密码。插件**不是**完整的身份安全防护系统：

- 插件自身不做限流、不限制并发，也不维护独立的失败计数或锁定。
- 邮箱密码的猜测防护（账户锁定、认证失败策略）由邮件服务器负责。
  企业邮箱通常同时开放 Webmail、IMAP、SMTP AUTH 等入口，即使插件限流，攻击者仍可从其他入口尝试同一密码。
- 入口流量限制和 HTTP 防洪由 Nginx / WAF 负责，示例见[部署说明](deployment.md#入口限流与可信代理)。

**autoCreate=true 的含义：**

- Keycloak 中不存在的地址，每次提交都会转发到 IMAP 服务器，**插件不对未知用户提供暴力破解保护**。
- 邮件服务器应具备合理的认证失败防护。
- 经 Keycloak 提交的错误密码可能触发 IMAP 端的账户锁定，影响真实用户。
- 邮件服务器若按来源 IP 封禁（例如 fail2ban），所有经 Keycloak 的请求共用同一个出口 IP，
  一旦被封禁，所有用户的 IMAP 登录都会中断。应针对 Keycloak 出口 IP 评估这一策略：
  把它排除在 IP 封禁之外，会让经 Keycloak 的尝试失去这层保护，
  因此只应在邮件服务器保留按账户锁定、且 Keycloak 前端已有入口限流时才这样做。
  不要关闭邮件服务器自身的认证失败防护。

**推荐的保守部署是 autoCreate=false**：由管理员预先创建并绑定用户，未知地址在连接 IMAP 前即被拒绝。

### 限流与故障

入口请求/IP 限流和邮件服务器端的账号保护仍然必要。
IMAP 网络、TLS 或运行异常均不放行，不自动切换本地密码；
服务层仍返回失败，面向用户和 Keycloak 登录事件保持通用错误，
详细类型在插件日志中区分。故障期间应评估用户级失败计数和锁定影响。

插件没有 Redis、跨会话全局限流或分布式审批服务，也不计划在插件内实现限流。
不同失败分支的响应耗时不保证相同，通用提示不能消除所有枚举侧信道。

### 账号枚举与响应时间

所有拒绝分支都返回相同的页面、提示文字、HTTP 状态和 `invalid_user_credentials` 登录事件错误，
插件日志只记录固定原因，不记录邮箱或密码。但是否连接 IMAP 会造成明显的响应时间差异：

| 分支 | 是否连接 IMAP |
| --- | --- |
| 域名不在白名单中 | 否 |
| 未关联的本地用户（同邮箱） | 否 |
| 已锁定的托管用户 | 否 |
| 未知地址，autoCreate=false | 否 |
| 未知地址，autoCreate=true | 是 |
| 托管用户（密码错误或正确） | 是 |

隔离环境（Keycloak 26.8.1 + 同机 GreenMail）中，不连接 IMAP 的分支中位数约 10–14 ms，
连接 IMAP 的分支约 150 ms；实际 IMAP 服务器的网络延迟通常更大，差异也更明显。因此：

- autoCreate=false 时，攻击者可以通过响应时间区分“托管用户”与“未知地址或未关联的本地用户”；
  旧版本中未知地址也会连接 IMAP，区分的是“未关联的本地用户”与其他地址。两者都只泄露“地址属于哪一类”，不泄露密码。
- 已锁定的托管用户也会更快返回。
- 本阶段不实施人为延迟或时间均衡。入口按来源（IP）限流只能**降低枚举速度，不能消除时间差**；
  需要防止枚举的部署应结合入口限流和邮件服务器端的账号保护。
- “autoCreate=false 时提前拒绝未知地址”只减少了转发到 IMAP 的无效尝试，**不解决全部密码喷洒问题**：
  对托管用户的喷洒仍会连接 IMAP（由 Brute Force Detection 按用户计数），autoCreate=true 时未知地址也仍会连接 IMAP。
  不要宣称当前版本已解决密码喷洒或账号枚举。

管理员可见的登录事件仍会区分托管用户（事件带 userId）与其他地址，这与 Keycloak 对普通用户的行为一致；
事件和日志应按内部资料管理。

## 本地密码与其他认证入口

插件只校验邮箱密码，不阻止托管用户取得 Keycloak 本地密码，也不阻止其他认证方式。
已在隔离的 Keycloak 26.8.1 中验证：托管用户可通过 `kc_action=UPDATE_PASSWORD`、
管理员指定的 Required Action 或开启的 Reset Password 设置本地密码；
邮箱停用后，该密码仍能经 Username Password Form、Direct Grant（含每个 Realm 自带的 `admin-cli`）、
客户端 Flow 覆盖、SAML ECP 和 IdP 首次登录重认证登录或关联外部账号。
管理员手动绑定的用户还会保留绑定前的本地密码。

- **仅配置 Browser Flow 不足以阻断 Direct Grant**，也不能阻断客户端覆盖、IdP 重认证或 ECP。
- **邮箱停用不自动等于 Keycloak 用户禁用**；本地密码、已关联的外部 IdP 和现有会话都不经过 IMAP。
- 混合认证模式不提供上述保证；严格 IMAP 托管模式依赖部署配置与持续审计，
  不是插件自身的全局强制保证。配置方法见[安装说明](installation.md#认证模式混合认证与严格-imap-托管)。
- SAML ECP 使用不可修改的内置 Flow，只能通过保持 `saml.allow.ecp.flow` 关闭来阻断。
- 外部 IdP 账号可不经本地密码关联到托管用户：首次登录 Flow 的 “Verify existing account by Email”
  只要求能读取托管邮箱的邮件（已验证：关联与登录均不经过 IMAP），自动关联则不做验证。
  已有关联的登录只执行 Post login flow，不再执行首次登录 Flow，邮箱停用后仍然有效。
  创建新关联与使用已有关联需分别防护，见[安装说明](installation.md#严格模式外部身份提供方idp)；
  外部 IdP 自身的认证强度不在本插件范围内。
- 只读审计（`scripts/audit-imap-local-password.sh`）没有 HIGH 不等于认证安全，它只覆盖已知的入口形态。
- 托管用户在密码入口的失败尝试（包括被防护拒绝的 Direct Grant）与 IMAP 失败计入同一个 Brute Force 计数，
  这与 Keycloak 对普通用户的行为一致，任何人都可借此触发该用户的临时锁定。

## 会话、撤销与回滚

IMAP 密码修改、邮箱账号停用不保证自动撤销已有 Keycloak SSO Session。
Cookie 允许策略甚至可在没有新 IMAP 请求时完成登录。
离职或高风险变更要显式处理 Keycloak 用户、会话、Refresh Token 和业务侧访问，
已签发 Access Token 的可用时间还取决于客户端的校验方式与有效期。

已在隔离的 Keycloak 26.8.1 中验证（默认 Access Token 有效期 300 秒）：

| 操作 | 新登录 | Refresh Token | userinfo / Introspection（Keycloak 在线校验） | 仅本地验签的下游服务 |
| --- | --- | --- | --- | --- |
| 仅停用邮箱 | IMAP 失败 | **仍可刷新** | 仍有效 | 仍接受 |
| 禁用 Keycloak 用户 | 立即失败 | 拒绝（User disabled） | 拒绝 / inactive | **仍接受到过期** |
| 禁用后重新启用（未注销会话） | — | **恢复可用** | 恢复有效 | 仍接受到过期 |
| 管理员注销该用户全部会话 | 不受影响（取决于用户状态） | 拒绝（Session not active） | 拒绝 / inactive | **仍接受到过期** |
| Realm “Not before” 撤销 | 新令牌正常 | 拒绝（Stale token） | 拒绝 / inactive | **仍接受到过期**（除非下游自行检查） |

**不得假设已签发的 Access Token 会立即失效。** Keycloak 端的撤销只影响向 Keycloak 在线校验的服务；
只验证签名和过期时间的下游服务会在 Access Token 过期前继续接受它。
需要更快失效时缩短 Access Token 有效期，或让下游使用 Introspection、Back-channel Logout 等机制。
禁用用户只是暂停，会话仍在，重新启用会恢复原会话；离职处置应同时注销会话。

创建失败及创建后权限拒绝会要求当前事务回滚，
但外部存储、插件钩子或应用产生的事务外副作用不能自动撤销。
并发修改权限配置也需要真实数据库和部署环境验证。
回滚镜像不会自动撤销已提交的 Email Verified 或用户权限变更。

## 日志与部署边界

日志仅使用固定阶段、原因、属性名和异常类型；
不记录邮箱密码、Token、请求正文、异常消息或堆栈。
Keycloak 自身事件、代理和应用日志是独立通道，仍须设置访问控制和保留期限。

生产使用 HTTPS 和支持的生产数据库，不把 H2/start-dev 当作生产部署。
不要修改 master Realm 管理员认证 Flow，保留恢复通道并先在测试 Realm 验证。
安装后既要测试新会话，也要测试旧会话和客户端 Flow 覆盖。
