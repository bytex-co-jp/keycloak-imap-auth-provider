# 安装与测试 Realm 配置

[返回 README](../README.zh-CN.md)

## 构建前提

当前基线为 Keycloak 26.8.1，插件 1.0.0，Java 21 字节码。
使用 JDK 21 或更高版本与项目内的 Gradle Wrapper 9.8.2 构建。
不要求安装独立 Gradle，不使用 Spring Boot。

在项目根目录执行：

```sh
./gradlew clean test build
docker build -t imap-keycloak:1.0.0 .
```

产物为 build/libs/keycloak-imap-auth-provider-1.0.0.jar。
Dockerfile 将其复制到 /opt/keycloak/providers，并在新镜像构建阶段执行 kc.sh build。
这些命令不会自动把镜像部署到已运行服务。

Keycloak 依赖为 compileOnly。Mail 依赖也由运行镜像提供，
应检查目标镜像是否具有 Angus Mail 2.0.5、Jakarta Mail API 2.1.1，
以及兼容的 Activation API/实现。现有记录中的运行组合还包括
Activation API 2.1.4 和 Angus Activation 2.0.3。
不要盲目复制重复的 Keycloak、Mail API 或实现 JAR。

若手动安装到独立的可重建 Keycloak 目录，应先放置 Provider JAR，
再执行该目录的 bin/kc.sh build。不要用 docker cp 修补运行容器。
正式数据库的构建参数需要单独处理，见[部署说明](deployment.md)。

## 隔离测试环境

按[部署说明](deployment.md)启动测试服务。
创建专用 Realm，例如 example，保留可用于恢复的管理员会话。
不要更改 master Realm 的管理员认证流程。

关闭 Duplicate Emails。若使用外部用户存储，
先确认其邮箱和用户名唯一性与事务行为。
不要通过允许重复邮箱绕过身份冲突。

## User Profile 属性

进入目标 Realm 的 Realm settings > User profile > Attributes，
通过 Create attribute 分别添加以下属性。

| 配置项 | imap.auth.binding | imap.auth.identity |
| --- | --- | --- |
| 用途 | 受控接入服务标识 | 受控邮箱主体标识 |
| Multivalued | Off | Off |
| Default value | 空 | 空 |
| Enabled when | Always | Always |
| Required field | Off | Off |
| User view | Off | Off |
| User edit | Off | Off |
| Admin view | On | On |
| Admin edit | On | On |

插件要求两个属性显式存在、单值，且 edit 权限集合严格为 admin。
普通用户不能配置自己的关联标识，否则可能关联不属于自己的本地身份。
只读给管理员的配置也有助于减少内部关联信息的暴露。
本插件不要求其他自定义 User Profile 属性。

最小可合并 JSON：

```json
{
  "attributes": [
    {
      "name": "imap.auth.binding",
      "multivalued": false,
      "permissions": {"view": ["admin"], "edit": ["admin"]}
    },
    {
      "name": "imap.auth.identity",
      "multivalued": false,
      "permissions": {"view": ["admin"], "edit": ["admin"]}
    }
  ]
}
```

对应文件为[User Profile 示例](../config/user-profile-imap-attributes.json)。
省略 required、selector、defaultValue，表示无额外必填规则、始终启用且无默认值。

**该 JSON 不是完整 User Profile 的替换配置。**
先备份当前 Profile；在 JSON editor 中仅添加或按 name 更新这两项，
保留原有 username、email、其他属性、groups、验证器及顶层策略。
不要直接将片段 PUT 成为完整配置。

也须检查注册、自定义 REST 接口、导入流程与 unmanaged attributes 策略，
确保普通用户没有绕开 Profile 权限的写入通道。

## 创建 Authentication Flow

在 Authentication > Flows 中复制原 Browser Flow，或新建可绑定的 basic-flow。
保留内置 Browser Flow，记录原 Browser 绑定与客户端覆盖值。

### 严格 IMAP 策略

适用于每次进入该登录 Flow 都要验证 IMAP 的场景。

1. 在副本中添加 IMAP Authentication。
2. 将其设为 REQUIRED。
3. 移除副本中的本地 Username Password Form。
4. 移除或禁用能够成功结束该 Flow 的 Cookie、IdP 跳转和其他替代分支。
5. 不同时要求本地 Keycloak 密码。
6. 检查各客户端的 Authentication Flow Overrides。

这只约束**这一个 Browser Flow**。托管用户仍可能取得 Keycloak 本地密码，
并经 Direct Grant（含每个 Realm 自带的 `admin-cli`）、其他 Flow 或客户端覆盖登录。
要阻断这些入口，还必须按下一节配置“严格 IMAP 托管模式”。

## 认证模式：混合认证与严格 IMAP 托管

**IMAP 用户不需要 Keycloak 本地密码，不等于无法设置本地密码。**
Keycloak 允许托管用户通过 `kc_action=UPDATE_PASSWORD`（账户控制台的 “Set up password” 也走这里）、
管理员指定的 Required Action、开启 Reset Password 时的邮件重置，以及管理员操作取得本地密码。
插件只校验邮箱密码，不阻止也不检查本地密码；邮箱停用后，本地密码仍可在下列入口登录：

| 使用本地密码的入口 | 默认是否可用 | 能否用 Flow 防护 |
| --- | --- | --- |
| Browser Flow 中的 Username Password Form / Password Form | Realm 内置 `browser` Flow 含此步骤 | 能 |
| Direct Grant（`grant_type=password`） | 每个 Realm 自带的 `admin-cli` 客户端默认开启 | 能 |
| 客户端 Authentication Flow Override（browser / direct_grant） | 取决于配置 | 能，需逐个覆盖 |
| 身份提供方首次登录的 “Verify Existing Account by Re-authentication” | 配置了 IdP 时 | 能 |
| 已关联到托管用户的外部 IdP 账号（不使用本地密码，见下文） | 配置了 IdP 时 | 能（Post login flow 防护） |
| SAML ECP（HTTP Basic） | 仅当 SAML 客户端开启 `saml.allow.ecp.flow`（默认关闭） | **不能**：内置 `saml ecp` Flow 不可修改或覆盖，只能保持 ECP 关闭 |
| Docker 协议（HTTP Basic） | 仅当启用 docker 特性 | 预期能（Realm 的 Docker Authentication Flow 绑定；未实测） |
| User Federation（LDAP 等）校验的密码 | 取决于配置 | 不在本节范围，须单独评估 |

**A. 混合认证模式**：Realm 内同时允许 IMAP 与其他认证方式。
插件**不保证**阻断其他认证方式，托管用户取得本地密码后可绕过 IMAP；
只适用于接受这一点的部署，并须执行[运维说明](operations.md)中的离职与泄露处置。

**B. 严格 IMAP 托管模式**：托管用户（带 `imap.auth.binding` 属性的用户）只能通过 IMAP 登录。
它依赖下面的 **Flow 配置、关闭不适用的入口（如 SAML ECP、不需要的 Direct Access Grants 和账号关联方式）和持续审计**，
不是插件自身的全局强制保证；新增客户端、覆盖、IdP 或 Keycloak 升级都可能引入新入口。

### 严格模式：托管用户本地密码防护

在每个会校验本地密码的步骤之后（或在识别用户之后、校验密码之前），
加入同一个条件子流程，下文称“托管用户防护”：

```text
imap-managed-no-local-password            CONDITIONAL（子流程，basic-flow）
├── Condition - user attribute            REQUIRED
│     Attribute name:  imap.auth.binding
│     Expected value:  .+
│     Regex:           ON
│     Negate output:   OFF
└── Deny access                           REQUIRED
      Error message:   Invalid username or password.
```

只修改**副本**，不修改内置 Flow；在测试 Realm 验证后再绑定。各入口的放置位置：

| 入口 | 副本来源 | 防护子流程的位置 | 绑定 |
| --- | --- | --- | --- |
| Browser | `browser` | `forms` 子流程中，Username Password Form 之后、Conditional 2FA 之前 | Realm 的 Browser Flow，以及所有 browser 覆盖 |
| Direct Grant | `direct grant` | 顶层，Username Validation 之后、Password 之前 | Realm 的 Direct Grant Flow，以及所有 direct_grant 覆盖 |
| Reset Credentials | `reset credentials` | 顶层，Choose User 之后、Send Reset Email 之前 | Realm 的 Reset Credentials Flow |
| 身份提供方首次登录 | `first broker login` | “Verify Existing Account by Re-authentication” 子流程中，re-authentication 表单之后 | 每个 IdP 的 First login flow（以及 Realm 默认值） |
| 身份提供方每次登录 | 新建 basic-flow | 顶层：防护子流程，其后加 **Allow access [REQUIRED]** | 每个 IdP 的 Post login flow（见下文） |
| Docker（启用时） | `docker auth` | HTTP Basic 之后 | Realm 的 Docker Authentication Flow |

执行顺序与要求的含义：

- 条件认证器需要已识别的用户，防护必须放在识别用户的步骤**之后**；
  放在前面时 Keycloak 找不到用户，会使该 Flow 对所有人报错。
- 防护子流程必须是 **CONDITIONAL**，其中条件与 Deny access 都是 **REQUIRED**。
  不要放进只有 ALTERNATIVE 的层级，也不要设为 DISABLED，否则不会执行。
- Browser 表单先校验密码再执行防护，因此托管用户输入正确本地密码时也只看到与密码错误相同的提示；
  Direct Grant 中防护位于密码校验之前，不会校验本地密码。
- 普通本地用户没有 `imap.auth.binding`，条件不成立，登录不受影响。
- IMAP 登录使用自己的 Flow 或分支，不经过这些密码步骤，不受影响。

配置错误时的后果：

| 错误 | 后果 |
| --- | --- |
| 漏掉某个客户端覆盖、IdP 或新建的 Flow | 该入口仍接受托管用户的本地密码（静默旁路） |
| 属性名写错、勾选 Negate output、正则不匹配 | 防护不生效（静默旁路） |
| 防护放在识别用户之前 | 该 Flow 对所有用户报错（可用性故障） |
| 防护子流程设为 DISABLED / ALTERNATIVE | 不执行（静默旁路） |
| 对 SAML 客户端开启 ECP | 无法用 Flow 防护，托管用户可用本地密码取得 SAML 断言 |

另外：

- `admin-cli`：每个 Realm 都有，默认开启 Direct Access Grants 且没有覆盖，
  因此必须由 Realm 的 Direct Grant Flow 防护；若业务不需要，也可在该 Realm 关闭其 Direct Access Grants。
  master Realm 的 `admin-cli` 是管理员入口，不要在 master Realm 配置本防护或 IMAP。
- `UPDATE_PASSWORD` Required Action 无法由 Flow 阻止，严格模式只阻止本地密码的**使用**。
  若 Realm 内全部是托管用户，可额外在 Authentication > Required actions 中关闭它；
  这会影响该 Realm 所有用户的自助改密码。
- 不删除、不重置任何已有凭证。已有本地密码的托管用户在防护生效后无法用它登录，
  由管理员按[部署说明](deployment.md#升级严格-imap-托管模式与本地密码审计)人工处理。
- 管理员恢复：管理员仍可通过 Admin Console 或 REST 设置密码，但只要用户仍带绑定属性，防护照样拒绝。
  确需让托管用户改用本地密码时，先按审批流程移除 `imap.auth.binding` 与 `imap.auth.identity`
  （用户转为普通本地用户，此后 IMAP 登录会被拒绝），再设置密码。
  master Realm 管理员不受影响。

### 严格模式：外部身份提供方（IdP）

外部 IdP 账号一旦关联到托管用户，此后经该 IdP 登录**不经过 IMAP**，邮箱停用后仍然有效。
需要分别处理“创建新关联”和“使用已有关联登录”；外部 IdP 本身的认证强度（密码策略、MFA、
是否核实邮箱）由该 IdP 负责，不在本插件和本防护的范围内。

已在隔离的 Keycloak 26.8.1 中验证：

| 场景 | 内置 first broker login | 关闭邮件验证 + 重认证防护 | 加 Post login flow 防护 |
| --- | --- | --- | --- |
| 新关联：Verify existing account by Email | 收到托管邮箱邮件即可关联并登录，不经过 IMAP | 该步骤关闭后不再提供 | 可建立关联，但登录被拒绝 |
| 新关联：Re-authentication（本地密码） | 有本地密码即可关联 | 被防护拒绝，不建立关联 | 关联仍会建立，但登录被拒绝 |
| 已有关联登录 | 成功，不经过 IMAP；邮箱停用后仍成功 | **仍成功**（首次登录 Flow 不再执行） | 被拒绝 |
| 无关的外部用户（新建本地账号） | 成功 | 成功 | 成功 |
| 禁用 Keycloak 用户 | 已有关联登录被拒绝 | 同左 | 同左 |

- **创建新关联**：托管用户的本地密码防护（重认证）**不覆盖** “Verify existing account by Email”；
  任何能读取该托管邮箱邮件的人都可借此关联外部账号。严格模式下不需要账号关联时，
  在首次登录 Flow 副本中把该步骤设为 DISABLED；同时不要使用自动关联（Automatically set existing user）。
- **使用已有关联登录**：修改首次登录 Flow 对已有关联**没有作用**。要阻止托管用户经 IdP 登录，
  为每个 IdP 设置 Post login flow：顶层放托管用户防护子流程，其后放 **Allow access [REQUIRED]**。
  缺少 Allow access 时，该 Flow 对条件不成立的其他用户报错，**所有**经该 IdP 的登录都会失败。
  也可由管理员解除托管用户的 IdP 关联（Users > Identity provider links）。
- 审计脚本会报告开启的邮件验证关联、自动关联、无防护的重认证、缺少 Allow access 的 Post login 防护，
  以及托管用户的已有关联。

### 配置后审计

配置后运行只读审计，确认没有 HIGH / WARNING / REVIEW 未处理项：

```sh
scripts/audit-imap-local-password.sh <realm> [<realm>...]
```

审计只发送 GET 请求，不修改任何用户或 Flow；输出含用户 ID 与用户名，按内部资料管理。
它按静态结构判断防护，无法覆盖第三方认证器、User Federation 校验的密码或未来新增的入口；
**没有 HIGH、退出码 0 也不代表认证是安全的**，只表示在审计范围内没有发现已知问题。每次新增客户端、覆盖、IdP 或升级 Keycloak 后都要重新运行，
并在测试 Realm 中实际验证：托管用户本地密码在 Browser、Direct Grant（含 `admin-cli`）、
各覆盖客户端和 IdP 重认证中均被拒绝，托管用户的 IdP 登录符合预期，普通本地用户和 IMAP 登录正常。

### 允许已有 SSO 或受信 IdP

只有业务明确允许跳过新的 IMAP 验证时才采用：

```text
Browser Flow
├── Cookie                              ALTERNATIVE
├── Identity Provider Redirector        ALTERNATIVE
└── Forms                               ALTERNATIVE
    └── IMAP Authentication             REQUIRED
```

同层不要混合 REQUIRED 与 ALTERNATIVE 以免产生意外执行语义。
该策略允许 Cookie 或外部 IdP 成功后跳过 IMAP；
它不是“每次登录必须校验邮箱密码”的策略。
外部 IdP 的身份关联安全必须另行审核。

无论何种策略，都应测试旧 SSO Session、所有客户端覆盖和所有登录入口。
绑定改变不会自动撤销之前签发的 Token 或现有会话。

## 配置 Execution 并绑定

打开 IMAP Execution 的配置：

- host 示例使用 mail.example.com。
- SSL 示例为 port=993、tls=SSL。
- STARTTLS 示例为 port=143、tls=STARTTLS。
- identifier 保持 Email。
- **必填** allowedEmailDomains，例如 example.com；只列出该 IMAP Host 负责的域名。
  未配置时该 Execution 拒绝所有登录。
- 若期望首次自动建号，显式设置 autoCreate=true。
- 保持空允许列表起步，确认普通默认权限；如有自定义默认权限再审核批准。

全部键和值域见[配置参考](configuration.md)。
确认配置完整后，仅在测试 Realm 将副本绑定为 Browser Flow；
UI 的 Bind flow 或 Authentication > Bindings 可用于选择该 Flow。

## 首次登录验收

使用完整测试邮箱 user@example.com，不要把真实密码写入脚本或测试源码。
验证成功登录、自建用户、两个绑定属性和 Email Verified。
确认新建用户没有本地密码、没有意外管理员角色或 Group。
“此刻没有本地密码”不代表以后不会有；采用严格 IMAP 托管模式时，
还要按上一节验证本地密码入口均被拒绝，并运行只读审计。

再验证错误密码、禁用用户、同邮箱本地账号冲突、
属性保护缺失、默认权限拒绝、IMAP 不可用以及 TLS 错误。
页面应始终只有一套输入区域，失败时保留邮箱并清空密码。

恢复时先还原 Browser Flow 绑定和客户端覆盖，再按部署计划回滚镜像。
不要在仍被绑定的 Flow 引用该 Provider 时直接移除 JAR。
