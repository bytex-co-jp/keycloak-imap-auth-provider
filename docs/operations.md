# 日常运维

[返回 README](../README.zh-CN.md)

本页中的容器 keycloak、Realm example、邮箱 user@example.com 均为公开示例。
实际操作需核对身份、数据和维护窗口；本项目文档不执行任何运维动作。

## 用户生命周期

| 场景 | 管理操作 |
| --- | --- |
| 新用户首次登录 | 审核 autoCreate、默认权限与 IMAP 账号资格；成功后检查新 User ID、绑定、邮箱和授权 |
| 已有用户登录 | 确认 enabled、绑定与邮箱一致；插件不修改其角色和 Group |
| 禁用用户 | 在目标 Realm 禁用，并按风险撤销会话和后续访问能力 |
| 离职 | 同时处置 IMAP、Keycloak、角色、Group、业务系统和会话 |
| 手动授权 | 通过 Admin Console 审批和授予，插件不提供升级接口 |
| 邮箱地址修改 | 独立核验身份，审查用户 email、绑定、旧会话和 Email Verified，不依赖同邮箱自动迁移 |
| 邮箱重新分配 | 将新持有人视为新的身份审核，不直接复用旧 Keycloak 用户 |

### 新建与手动关联

自动创建默认关闭。
开启时必须先检查默认角色和 Group，不能把员工通用默认组与管理员组混用。
既有本地账号需要管理员独立核验后再填写受保护属性，
不能为解决登录错误而批量给所有同邮箱用户加标识。

管理员手动授予的角色，包括管理角色，不会在后续 IMAP 登录时被插件删除。
反之，IMAP 成功也不会审批或增加管理员权限。
定期审查 Composite、Group 继承和业务系统实际授权，而不仅检查直接 Role。

### 离职与禁用

1. 核对目标 Realm 和稳定 User ID，不单凭可重用邮箱判断人员。
2. 禁用 Keycloak 用户并关闭或回收 IMAP 账号。**两者都要做**：邮箱停用不等于 Keycloak 用户禁用，
   托管用户若有本地密码或已关联外部 IdP，仅停用邮箱后仍可登录。
3. 注销该用户的全部会话（Users > Sessions > Sign out）。仅禁用用户时会话仍在，重新启用会恢复原会话和 Refresh Token。
4. 审查并撤销 Role、Group 和应用侧权限。
5. 确认业务侧 Access Token 策略：只做本地验签的服务会在 Access Token 过期前继续接受它（默认 300 秒）。
6. 审查该用户的本地密码、外部 IdP 关联、客户端覆盖和其他替代认证入口
   （可用 `scripts/audit-imap-local-password.sh <realm>`）。
7. 保存必要审计记录，按政策决定保留、归档或删除用户。

仅停用邮箱不保证已有 SSO Session 立即失效。
仅禁用用户也不应被理解为已在所有业务系统即时撤销已签发 Token。
令牌生命周期的验证结果见[安全说明](security.md#会话撤销与回滚)。

### 邮箱密码泄露

邮箱密码泄露期间，持有者可以通过 IMAP 登录 Keycloak，并为托管用户设置本地密码或关联外部 IdP。
这些凭证在邮箱改密后仍然有效，因此除重置邮箱密码外：

1. 注销该用户的全部 Keycloak 会话。
2. 检查该用户是否出现本地密码、外部 IdP 关联或 UPDATE_PASSWORD 等变化（审计脚本与 Admin Events / Login Events）。
3. 未采用严格 IMAP 托管模式时，按[安装说明](installation.md#认证模式混合认证与严格-imap-托管)评估是否启用。

### 邮箱转交与重新分配

当 user@example.com 从原持有人转交给另一人时，
不能直接复用原 Keycloak 身份，否则新持有人可能继承原 Role、Group、
会话和业务数据归属。

先审查旧 User ID、受保护绑定、角色、Group、会话和应用侧资源。
按身份保留政策隔离旧账号及其邮箱占用，再为新持有人建立经审核的新身份。
关闭 Duplicate Emails 的要求仍须保持；不要通过开启重复邮箱绕过冲突。
如果迁移业务数据或允许同一邮箱再次建号，必须进行独立审核，
避免删除旧用户后重新建号造成未经批准的数据关联。

## IMAP 和 TLS 维护

| 情况 | 操作 |
| --- | --- |
| IMAP 服务不可用 | 查看阶段日志和服务监控，不切换成本地密码或跳过认证 |
| 连接超时 | 核对 DNS、出口网络、端口与超时配置；不要无限增加超时掩盖故障 |
| 证书更新 | 检查有效期、完整证书链、主机名与 JVM 信任库 |
| 私有 CA | 通过受控镜像/信任库部署，不使用 trust-all |
| 更换服务器 | 在测试 Realm 验证，并重新审查 host:port:TLS 绑定 |
| 资格信息被拒绝 | 区分密码、完整邮箱格式和 IMAP 端锁定，不记录或索取明文密码 |

出现故障时，对既有 Keycloak SSO 与新 IMAP 请求分别评估。
服务故障仍可能进入 Keycloak 通用失败处理，
需要关注失败计数和被锁用户，不盲目反复提交验证。

## 日志与监控

```sh
docker logs --since 5m keycloak
```

短期诊断可按[故障排查](troubleshooting.md)开启插件分类 DEBUG。
避免全局 TRACE、Jakarta Mail debug 和请求正文记录。
原生 Keycloak 事件与代理日志可能含个人信息，提取故障记录前脱敏，
并设置访问权限、保留期限与告警。
不要把密码、Token 或完整 inspect/env 输出复制到 issue。

## 备份策略

应同时保护：

- Keycloak 数据库及必要事务日志。
- Realm/客户端/Flow 与 User Profile 配置。
- 镜像 tag、digest、Provider JAR 版本和运行参数。
- TLS 证书、信任库和必要 Secret 的受控副本。
- 外部用户存储及其独立恢复机制。

Realm 导出不一定包含全部运行状态或可用会话，
也不能替代数据库一致性备份。
备份应加密、限制访问并在隔离环境做恢复演练。

### H2 开发环境的离线备份示例

仅用于已确认数据卷名为 keycloak-data 的开发服务。
先只读确认挂载和其他引用者，确保没有其他实例写同一个 H2 Volume。
备份工具可用性应在停机前确认。

```sh
docker inspect keycloak --format '{{json .Mounts}}'
docker volume inspect keycloak-data
docker pull busybox:1.37.0
umask 077
IMAP_BACKUP_DIR="$HOME/keycloak-backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$IMAP_BACKUP_DIR"
docker inspect keycloak > "$IMAP_BACKUP_DIR/keycloak-inspect.json"
```

inspect 文件可能含既有凭证，只保存到私有目录，不打印、不加入 Git。
逐步执行下列命令，**每一步成功后才执行下一步**：

```sh
docker stop keycloak
docker run --rm --network none \
  --mount type=volume,src=keycloak-data,dst=/data,readonly \
  --mount type=bind,src="$IMAP_BACKUP_DIR",dst=/backup \
  busybox:1.37.0 tar -czf /backup/keycloak-data.tar.gz -C /data .
docker run --rm --network none \
  --mount type=bind,src="$IMAP_BACKUP_DIR",dst=/backup,readonly \
  busybox:1.37.0 tar -tzf /backup/keycloak-data.tar.gz
docker start keycloak
```

备份失败时不要继续升级；根据故障情况恢复旧服务。
禁止将运行中的 H2 文件热拷贝直接当作一致性备份。
归档列表可读不等于数据可恢复，还需隔离验证。
不要把持有同一 Volume 的旧容器当作独立备份。

### 正式数据库备份与恢复

使用所选数据库支持的一致性备份与恢复机制，配合部署的恢复目标。
不要把 Keycloak 容器 data 目录归档视作外部数据库备份。
恢复演练必须使用隔离数据库、网络与服务名，
避免测试服务向生产邮箱、IdP 或业务回调发起意外操作。

恢复前明确可接受的数据丢失窗口、维护窗口与版本兼容性。
需要回到升级前快照时，先停全部写入，再按独立批准的恢复方案执行。
不要在文档示例中直接覆盖正在使用的数据库或数据卷。

## 插件升级与回滚

升级流程统一见[部署说明](deployment.md)，各版本变更见 [CHANGELOG](../CHANGELOG.md)。
先构建验证、备份、记录旧镜像与运行配置，再切换镜像。
回滚旧代码不撤销已经提交的用户、Email Verified 或权限变化。

## Keycloak 版本升级

在正式更新前检查：

1. Authentication SPI 和 private SPI 的接口。
2. Mail、Activation 和 FreeMarker 运行时依赖。
3. 原生主题属性、布局宏和页面展示模型。
4. User Profile 配置格式和权限行为。
5. 自动创建、唯一约束和事务行为。
6. Brute Force、SSO、Token、客户端覆盖和回调。
7. 数据库迁移兼容性及镜像/数据库配套回滚。

现有单元测试通过不代表上述升级组合已验证。
当前基线是 Keycloak 26.8.1，不承诺无验证地兼容后续版本。
