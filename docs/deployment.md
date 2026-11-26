# 部署与升级

[返回 README](../README.zh-CN.md)

本页是通用操作模板，不描述维护者内部环境。
容器统一以 keycloak、Realm 以 example 为示例；
镜像、网络、Volume 和私有配置文件需要操作员按实际部署核对。
所有部署命令均需在备份、维护窗口和恢复方案确认后执行。

## 构建与 Provider 加载

```sh
./gradlew clean test build
docker build -t imap-keycloak:1.0.0 .
```

现有 Dockerfile 以 Keycloak 26.8.1 为基础，
将 build/libs/keycloak-imap-auth-provider-1.0.0.jar 放入 /opt/keycloak/providers，
在镜像构建阶段运行 /opt/keycloak/bin/kc.sh build。
不会自动部署镜像或更新运行中的容器。

使用可追溯的独立镜像标签或 digest，不覆盖唯一的回滚标签。
先在隔离环境检查 Provider 是否出现、依赖是否齐全，
再测试成功/失败登录、自动创建、Email Verified、权限和 SSO 重定向。
构建成功、ServiceLoader 通过与容器实际加载是不同的验证层级。

## 开发环境：Docker Run

下面仅供新建的隔离测试服务使用。
/secure/keycloak-dev.env 是操作员事先准备的私有环境文件占位路径，
其中可能需要首次引导管理员的配置；不要将实际凭证放入仓库。
确认容器名和 Volume 不属于已有服务后再创建，避免误启动空数据库。

```sh
docker volume create keycloak-data
docker run -d --name keycloak \
  --env-file /secure/keycloak-dev.env \
  --mount type=volume,src=keycloak-data,dst=/opt/keycloak/data \
  -p 127.0.0.1:8080:8080 \
  imap-keycloak:1.0.0 start-dev
docker logs --tail 200 keycloak
```

127.0.0.1 是本机开发绑定示例，不是内部环境地址。
H2 数据位于 data 目录；首次启动、恢复和重建必须连接预期的同一个 Volume。
start-dev 与 H2 仅用于开发测试，不等同于正式安全配置。

## 开发环境：Docker Compose

以下是文档示例，不是仓库已提供的 Compose 文件。
将其保存为操作员自己的 Compose 配置，再配置私有 env 文件。
外部 Volume 必须预先创建；升级时要使用已有 Volume 的真实名称。

```yaml
services:
  keycloak:
    image: imap-keycloak:1.0.0
    command: ["start-dev"]
    env_file:
      - /secure/keycloak-dev.env
    ports:
      - "127.0.0.1:8080:8080"
    volumes:
      - keycloak-data:/opt/keycloak/data

volumes:
  keycloak-data:
    external: true
    name: keycloak-data
```

先检查 Compose 文件中 image、网络、卷和端口；不要公开渲染后的含敏感值配置。
从持有该文件的目录执行：

```sh
docker compose up -d --no-deps keycloak
docker compose logs --tail 200 keycloak
```

已有 docker run 容器不会自动变成 Compose 服务。
迁移前记录原运行参数并核对数据，不应直接对名称相同的服务执行 up。

## 正式环境：数据库与构建参数

使用 Keycloak 26.8.1 支持的生产数据库，例如该版本支持的 PostgreSQL 组合，
并按目标数据库单独设置持久化、备份、网络与访问控制。
Keycloak data Volume 不能代替外部数据库的持久化与一致性备份。

当前仓库 Dockerfile 没有生产数据库参数化能力。
不要向它传入不存在的 build-arg，再假定数据库配置已生效。
生产部署应由操作员提供经过审查的构建文件，例如：

```dockerfile
FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ENV KC_DB=postgres
COPY --chown=keycloak:keycloak build/libs/keycloak-imap-auth-provider-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

这是待验证的生产构建模板，不是已完成的生产部署记录。
如果将该示例保存到 /secure/Dockerfile.keycloak-production，可从项目根目录构建：

```sh
docker build -f /secure/Dockerfile.keycloak-production -t imap-keycloak:1.0.0-postgres .
```

运行时 KC_DB 必须与构建期一致，配置 KC_DB_URL、KC_DB_USERNAME、
KC_DB_PASSWORD 等连接参数；实际凭证通过受保护的配置/Secret 注入，
不写入镜像、README、Git 或命令行示例。
KC_HOSTNAME 应配置为实际 HTTPS 地址，例如 https://sso.example.com。

生产使用正式 start；完成一致的预构建后可使用 start --optimized，
不要直接把为开发数据库构建的镜像当作 PostgreSQL optimized 镜像。
支持生产数据库的版本选择与部署必须在上线前验证，本项目未声称已完成迁移。

## HTTPS 与代理

可以由 Keycloak 直接终止 TLS，也可以由受信反向代理终止 TLS。
使用代理时按实际拓扑配置 Hostname 和 proxy headers，
仅信任受控代理，避免客户端伪造转发头。
需要明文后端时仅在受保护的内部链路显式启用 HTTP，
不把开发端口暴露为公网生产登录入口。

正确配置外部 HTTPS 地址、回调地址、Cookie 和浏览器混合内容策略。
不要用关闭 Secure Cookie、证书校验或 Request Security 来掩盖代理错误。

## 入口限流与可信代理

插件自身不做限流（见[安全说明](security.md#责任边界与-autocreate)），
登录请求的流量限制应在反向代理或 WAF 上配置。

Keycloak 取 `X-Forwarded-For` 的**最左值**作为客户端地址，没有“可信跳数”的概念。
因此代理必须**覆盖**该请求头，而不是追加；否则客户端可以伪造任意来源 IP。
同时在 Keycloak 中只信任代理自身的地址：

```text
KC_PROXY_HEADERS=xforwarded
KC_PROXY_TRUSTED_ADDRESSES=<反向代理的地址>
```

最小的 Nginx 示例（仅对登录表单提交限流，数值需按实际用户规模调整）：

```nginx
# http {} 中
limit_req_zone $binary_remote_addr zone=kc_login:10m rate=10r/m;

# server {} 中
location ~ ^/realms/[^/]+/login-actions/authenticate {
    limit_req zone=kc_login burst=20 nodelay;
    proxy_pass http://keycloak:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;   # 覆盖，不使用 $proxy_add_x_forwarded_for
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

同一出口 IP 下有大量用户（NAT、代理）时需调高阈值。
前面还有 CDN 或负载均衡时，由最外层的可信代理负责覆盖该请求头。

## 镜像升级

升级前完成[运维备份](operations.md)，记录旧镜像 digest、容器运行参数、
Flow 绑定、客户端覆盖及数据库状态。敏感 inspect/env 文件只放在私有备份目录。

Docker Run 的更新顺序：

1. 构建并验证新标签，保留旧镜像。
2. 确认数据持久化和备份可恢复。
3. 停止旧容器；H2 不允许两个容器同时写同一个数据卷。
4. 保留旧容器作回滚依据，名称调整由操作员明确安排。
5. 以原 Volume、网络、端口、Secret、TLS 和环境配置创建新容器，仅替换镜像。
6. 验证启动日志、Provider 加载和完整登录路径。

Compose 的更新顺序：

1. 修改操作员持有的 Compose image 标签。
2. 核对外部 Volume 名称和数据库配置保持一致。
3. 完成备份后执行 docker compose up -d --no-deps keycloak。
4. 验证后再决定何时清理旧镜像。

docker restart 不会更换容器镜像。禁止用 docker cp 修改运行容器。
不要执行删除数据卷的 down -v、docker volume rm 或等效清理操作。

## 升级到含邮箱域名白名单的版本

**这是 breaking change。** 新版本要求每个 IMAP Authentication Execution 都配置
`allowedEmailDomains`（规则见[配置参考](configuration.md#邮箱域名白名单)）。
未配置的 Execution 在升级后会拒绝所有 IMAP 登录。
影响范围限于使用该 Execution 的 Flow；master Realm 管理控制台和已有 SSO 会话不受影响，
补写配置后立即恢复，不需要重启。

推荐顺序（零中断）：

1. **列出所有 IMAP Execution**：在每个 Realm 中找出 providerId 为 `imap-authenticator` 的 Execution，
   包括客户端 Authentication Flow Overrides 引用的 Flow，并记录各自的 authenticationConfig ID。
2. **审计既有用户的域名**：统计带有 `imap.auth.identity` 属性的用户邮箱域名。
   出现预期之外的域名时，可能是此前通过多域名 IMAP 服务器自动创建的用户，应单独评估、禁用或删除，
   不要为了让他们继续登录而把域名加入列表。
3. **在旧版本运行期间预先写入配置**，例如：

   ```sh
   kcadm.sh get authentication/config/<config-id> -r <realm> > imap-config.json
   # 在 imap-config.json 的 config 对象中加入 "allowedEmailDomains": "example.com"
   kcadm.sh update authentication/config/<config-id> -r <realm> -f imap-config.json
   ```

   提交完整的配置对象，不要只提交这一个键。

   **不要把掩码值当作真实配置写回。** 旧版插件运行时，Admin REST 会把它未声明的配置键
   （包括已写入的 `allowedEmailDomains`）显示为 `**********`。若 GET 得到的文件中出现该掩码，
   必须先替换为真实值再提交，不能原样 PUT 回去；经 REST 原样写回掩码时服务端如何处理未经验证。
   即使掩码被当作字面值保存，新版本也会把它判为无效条目并拒绝所有登录（fail-closed），
   不会因此放宽限制，但会造成登录中断。写入后请在新版本的管理控制台或配置 GET 结果中确认真实值。
4. **升级插件**，用允许域名和不允许域名各验证一次登录，
   并确认日志中出现 `IMAP_DOMAIN_REJECTED`，且没有 `IMAP_DOMAIN_CONFIG_INVALID`。

已在 Keycloak 26.8.1（PostgreSQL，隔离测试环境）上验证的行为：

| 项目 | 结果 |
| --- | --- |
| 旧版插件运行时通过 Admin REST 写入 `allowedEmailDomains` | 数据库原样保存；旧版插件忽略该键，行为不变 |
| 旧版插件运行时读取该配置 | Admin REST 将这个未声明的键显示为 `**********`（数据库中的值不变），因此请勿用 GET 结果判断是否写入成功 |
| 旧版管理控制台再次保存该 Execution 配置 | 未声明的键保留原值，不会丢失 |
| 升级到新版本 | 直接读取预写的值；管理控制台显示 Allowed Email Domains 字段 |
| 回滚到旧版本 | 键继续保留，旧版插件忽略它（同时失去域名限制） |

其他 Keycloak 版本未验证，升级前应在隔离环境重复上述检查。

**回滚到旧版本会失去域名限制**：旧版插件忽略 `allowedEmailDomains`，
所有域名的登录都会再次转发到 IMAP 服务器，开启 autoCreate 时也会再次允许其他域名自动开户。
回滚期间应关闭 autoCreate 或依靠入口限流等其他措施。

上表的升级行为验证使用了不可达的测试 IMAP 主机。
此后已在隔离环境用真实 Keycloak 26.8.1 与 GreenMail（IMAPS，经 Keycloak 信任库验证证书）
完成端到端登录验证，包括自动创建、绑定用户登录、失败计数与锁定。
此后又在 ByteX Inc. 运营的生产 Keycloak 环境中完成了从内部版本的升级与验证。
其他 IMAP 服务器的实现、证书链、认证机制、限速和故障行为可能不同，
正式启用前仍须在隔离环境用实际使用的邮件服务器做端到端验收。
遗漏某个 Execution 时，日志会出现 `IMAP_DOMAIN_CONFIG_INVALID reason=missing`，
补写配置即可。

## 升级到登记 password 认证类型的版本

**这是认证策略变化，升级前必须检查。** 从本版本起：

- 插件的认证类别为 `password`，IMAP 失败由 Keycloak 原生 Brute Force Detection 计数和锁定。
  **Realm 必须启用 Brute Force Detection** 才有这项保护；未启用时不计数、不锁定。
- IMAP 认证成功时登记 `password` 凭证类型，并按原生机制清零失败次数。
- 只有托管用户会被计数。插件不对未知用户提供保护，需依靠入口限流和邮件服务器的账号保护，
  保护范围见[安全说明](security.md#brute-force-detection-与故障)。
- “Condition - credential” 对 IMAP 登录的判断随之改变，
  以 `credentials=none` 控制二次认证的 Flow 升级后会跳过 OTP，
  详见[安全说明](security.md#imap-登录登记为-password-类型认证)。

推荐顺序：

1. **在旧版本运行期间执行只读检查。** 先用有查看权限的管理员登录 kcadm.sh，
   再对每个使用 IMAP 的 Realm 运行仓库中的检查脚本（需要 jq）：

   ```sh
   kcadm.sh config credentials --server https://keycloak.example.com --realm master --user <admin>
   scripts/check-imap-credential-conditions.sh <realm> [<realm>...]
   ```

   kcadm.sh 不在 PATH 中时用 `KCADM` 指定，例如
   `KCADM="docker exec -i keycloak /opt/keycloak/bin/kcadm.sh"`。
   脚本只发送 GET 请求，**不修改任何 Flow**；它遍历所有顶层 Flow（含嵌套子流程），
   并标出各 Flow 由 Realm 绑定、客户端 Flow Override 还是身份提供方引用。

2. **逐条处理输出。** 脚本不会给出“安全”结论，含 IMAP 的 Flow 至少需要人工确认：

   | 级别 | 含义 | 处理 |
   | --- | --- | --- |
   | `HIGH` | 条件为 `none` included 或 `password` not included；升级后 IMAP 登录不再进入该子流程，其中的 OTP 等步骤被跳过 | 升级前必须调整 Flow |
   | `CHANGE` | 条件为 `password` included 或 `none` not included；升级后 IMAP 登录开始进入该子流程 | 确认新增的 OTP、拒绝访问等步骤符合预期 |
   | `REVIEW` | 含 IMAP，但条件不涉及 `none`/`password`、条件缺少配置、没有 Condition - credential，或存在无法识别的自定义条件认证器 | 人工确认这些步骤不依赖登记的凭证类型 |
   | `INFO` | Condition - credential 所在 Flow 不含 IMAP | 确认同一认证会话不会先执行含 IMAP 的 Flow |

   退出码：0 表示所列 Realm 中没有含 IMAP 的 Flow，1 表示存在需要审查的条目，
   2 表示读取失败、结果不完整（不能当作无风险）。
   脚本按 Flow 的展开顺序标出 `imap_earlier`，但 ALTERNATIVE 分支、条件子流程和自定义认证器的
   实际执行路径只能由管理员判断，也不能识别第三方扩展对凭证登记的其他用法。

3. **人工调整 `HIGH` 的 Flow。** 插件和脚本都不会自动修改 Flow。目标是让必须执行的 OTP
   不依赖“未登记凭证”这一状态，例如：
   - 所有用户都必须使用 OTP 时，把 OTP Form 设为 REQUIRED，不放在 Condition - credential 子流程中；
   - 已配置 OTP 的用户才要求 OTP 时，改用 “Condition - user configured”；
   - 只想对某种认证方式追加 OTP 时，把该认证方式和 OTP 放在同一个子流程中，
     而不是依赖 `none` 判断“上一步是什么”。

   调整前先导出 Flow 作为备份，在测试 Realm 中验证，并保留管理员恢复通道。

4. **确认 Realm 已启用 Brute Force Detection**，例如检查
   `kcadm.sh get realms/<realm> --fields bruteForceProtected` 返回 `true`，
   并按组织策略设置失败次数阈值和等待时间。

5. **升级插件后验证**：托管用户错误密码会计数、正确密码登录后清零；
   每个 `HIGH`/`CHANGE` Flow 的 OTP 要求与调整后的预期一致；
   本地用户（含用户名等于邮箱的用户）的 IMAP 失败不会计入其失败次数。

回滚到旧版本时，`password` 条件和 `none` 条件恢复旧的判断，失败次数也不再计数；
若已按第 3 步调整 Flow，调整后的 Flow 在旧版本中同样生效。

## 升级：严格 IMAP 托管模式与本地密码审计

插件不阻止托管用户取得或使用 Keycloak 本地密码。已有部署无论升级与否都可能存在这一旁路；
是否需要处理取决于采用的认证模式，见[安装说明](installation.md#认证模式混合认证与严格-imap-托管)。
采用严格 IMAP 托管模式时，按以下顺序迁移（全部步骤都不删除、不重置用户凭证）：

1. **只读审计现状**：

   ```sh
   kcadm.sh config credentials --server https://keycloak.example.com --realm master --user <admin>
   scripts/audit-imap-local-password.sh <realm> [<realm>...]
   ```

   kcadm.sh 不在 PATH 中时用 `KCADM` 指定，例如
   `KCADM="docker exec -i keycloak /opt/keycloak/bin/kcadm.sh"`。

   | 级别 | 含义 |
   | --- | --- |
   | `HIGH` | 托管用户现在就能不经 IMAP 登录的入口（无防护的 Browser / Direct Grant / 覆盖 / IdP 重认证 / ECP、IdP 自动关联），或入口未防护时已有本地密码的托管用户 |
   | `WARNING` | 前提条件或潜在入口：已有本地密码的托管用户、已关联外部 IdP 的托管用户（无 Post login 防护时）、开启的 IdP 邮件验证关联、带 UPDATE_PASSWORD 的托管用户、开启的 Reset Password、未启用的 Brute Force Detection |
   | `REVIEW` | 无法静态判断：无法识别的认证器、形似但不符合要求的防护（含缺少 Allow access 的 Post login 防护）、User Federation |
   | `INFO` | 背景信息和已确认的防护 |

   退出码：0 仅有 INFO，1 有需处理项，2 部分请求失败、结果不完整（**不能当作没有问题**）。
   **没有 HIGH 或退出码 0 都不等于认证是安全的**，只表示在审计范围内没有发现已知问题。
   该脚本不能证明不存在其他入口，覆盖范围见脚本末尾输出和[安装说明](installation.md#严格模式托管用户本地密码防护)。

2. **在测试 Realm 中建立防护副本并验证**，再绑定到正式 Realm 的 Browser、Direct Grant、Reset Credentials、
   IdP 首次登录、Docker（启用时）Flow 和**每个**客户端覆盖。
   关闭所有 SAML 客户端的 ECP（`saml.allow.ecp.flow`），该入口无法用 Flow 防护。
3. **处理已有本地密码的托管用户**：防护生效后这些密码已无法登录。
   逐个与负责人确认用途；确需删除或要求重设时由管理员在 Admin Console 人工操作并留记录。
4. **处理外部 IdP**：已有关联的登录不经过 IMAP，邮箱停用后仍可登录，修改首次登录 Flow 对它无效。
   按[安装说明](installation.md#严格模式外部身份提供方idp)为每个 IdP 配置 Post login 防护（含 Allow access），
   或由管理员解除关联；并关闭首次登录 Flow 中的邮件验证关联和自动关联。
5. **重新审计**，直到没有 `HIGH` 和未解释的 `WARNING` / `REVIEW`；此后在每次新增客户端、覆盖、IdP、
   修改 Flow 或升级 Keycloak 后重复。

已在隔离的 Keycloak 26.8.1 + GreenMail 中验证（托管用户：自动创建无本地密码 / 自动创建后自行设置本地密码 /
绑定前已有本地密码；另有普通本地用户）：

| 入口 | 未配置防护 | 配置防护后 |
| --- | --- | --- |
| IMAP 登录 | 托管用户成功 | 托管用户成功（不受影响） |
| Realm Browser Flow 本地密码 | 托管用户成功 | 托管用户拒绝，普通用户成功 |
| 客户端 browser 覆盖到内置 Flow | 托管用户成功 | 改绑防护副本后拒绝 |
| Direct Grant（Realm Flow，含 `admin-cli`） | 签发 Token | 拒绝，不签发 Token；普通用户签发 |
| 客户端 direct_grant 覆盖到内置 Flow | 签发 Token | 改绑防护副本后拒绝 |
| SAML ECP（客户端开启） | 签发断言 | Flow 无法防护；关闭 ECP 后不可用 |
| IdP 首次登录重认证 | 以本地密码关联外部账号并登录 | 拒绝，不建立关联 |
| IdP 首次登录邮件验证关联 | 收到托管邮箱邮件即可关联并登录 | 本地密码防护不覆盖；关闭该步骤后不再提供 |
| 已有 IdP 关联登录 | 成功，邮箱停用后仍成功 | 仅 Post login 防护或解除关联后被拒绝 |
| Reset Credentials | 发送重置邮件并可设置本地密码 | 托管用户拒绝且不发邮件；普通用户正常 |
| kc_action=UPDATE_PASSWORD | 可设置本地密码 | 仍可设置，但无法用于上述入口 |
| 邮箱停用后的本地密码 | 上述入口仍成功 | 全部拒绝 |
| 管理员 REST 设置密码 | 可用 | 可设置；用户带绑定属性时仍被防护拒绝，移除绑定属性后可用 |

## 升级：autoCreate=false 时未知地址不再连接 IMAP

本版本起，autoCreate=false 的 Execution 对 Keycloak 中不存在的地址在连接 IMAP 前以通用失败拒绝；
此前这些地址会先连接 IMAP 验证密码，再因不允许自动创建而被拒绝。用户看到的提示、HTTP 状态和登录事件不变。

- 已关联的托管用户、autoCreate=true 的行为、域名白名单、失败计数与锁定均不变。
- 插件日志中这类拒绝由 `KEYCLOAK_USER_CREATE_FAILED reason=creation_disabled_or_username_conflict`（IMAP 之后）
  变为 `KEYCLOAK_USER_LOOKUP_FAILED reason=unknown_user_auto_create_disabled`（IMAP 之前），
  也不再出现对应的 `IMAP_AUTH_*` 日志；依赖这些日志计数的监控需要调整。
- IMAP 服务器上来自 Keycloak 的、针对不存在用户的认证尝试会减少。
- 响应时间差异的影响见[安全说明](security.md#账号枚举与响应时间)；autoCreate=true 时未知地址仍会连接 IMAP，见[责任边界](security.md#责任边界与-autocreate)。

## 回滚

先停止新服务，再恢复旧镜像/旧容器及匹配的运行配置。
不能同时启动新旧 H2 写入实例；不要恢复成意外的新空 Volume。
保留旧容器不等于备份数据库，因为旧容器可能与新容器引用同一数据卷。

数据库回滚独立于镜像回滚：
同版本插件回退不自动撤销 Email Verified、用户创建或授权变更。
数据库快照恢复可能丢失快照之后的数据，须单独审核。
涉及 Keycloak 或数据库结构升级时，旧镜像也未必能读取升级后的数据库。

Provider 不能在已绑定 Flow 仍引用它时直接移除。
确需撤回认证方式时先恢复原 Browser Flow 和客户端覆盖，保留管理员恢复通道。
版本变更与升级注意事项见 [CHANGELOG](../CHANGELOG.md)。
