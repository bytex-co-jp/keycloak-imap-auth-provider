# PostgreSQL 部署示例

此目录提供基于 PostgreSQL 的 Keycloak 镜像构建示例，独立于项目根目录的开发用 Dockerfile。
示例中的容器名、网络名和端口都是占位值，请按实际环境替换。
本目录不包含真实域名、邮箱或密码，也不提供自动部署脚本。

## 文件

| 文件 | 用途 |
| --- | --- |
| Dockerfile.postgres | 构建 Keycloak 26.8.1 + 插件的 PostgreSQL 镜像 |
| Dockerfile.postgres.dockerignore | 只把插件 JAR 加入构建上下文，排除环境文件 |
| .env.keycloak.example | 环境配置示例（可提交） |
| .env.keycloak | 本地私有设置，已被 Git 忽略 |

## 部署前设置

复制示例并编辑：

```sh
cp release/.env.keycloak.example release/.env.keycloak
```

- `KC_DB_URL`、`KC_DB_USERNAME`、`KC_DB_PASSWORD`：数据库连接信息。
- `KC_HOSTNAME`：实际的外部 HTTPS 地址。
- `KC_PROXY_TRUSTED_ADDRESSES`：反向代理的地址。未设置时，能直接访问 Keycloak 的客户端可以伪造 `X-Forwarded-For`。
- `KC_BOOTSTRAP_ADMIN_PASSWORD`：仅在首次初始化空数据库时设置独立强密码，初始化完成后从环境文件中移除。

环境文件中的值不加引号。不要把真实密码写入示例文件、README 或 Git。

## 构建

以下命令均从项目根目录执行。

1. 构建插件 JAR（需要 JDK 21 或更高版本）：

   ```sh
   ./gradlew --no-daemon clean test build
   ```

   没有本地 JDK 时，可以在临时容器中使用项目 Wrapper：

   ```sh
   docker run --rm --user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/workspace/.gradle-docker \
     -v "$PWD:/workspace" -w /workspace eclipse-temurin:21-jdk sh ./gradlew --no-daemon clean test build
   ```

2. 构建镜像：

   ```sh
   docker build -f release/Dockerfile.postgres -t imap-keycloak:1.0.0-postgres .
   ```

   插件版本与 JAR 文件名不一致时，用 `--build-arg PLUGIN_VERSION=<版本>` 指定。

## 启动示例

数据库与 Keycloak 放在同一个自定义网络中：

```sh
docker network create keycloak-net
docker run -d --name keycloak --network keycloak-net --restart unless-stopped \
  --env-file release/.env.keycloak -p 127.0.0.1:8080:8080 \
  imap-keycloak:1.0.0-postgres start --optimized
```

`KC_DB_URL` 中的主机名应为数据库在该网络中的容器名。
用户和 Realm 都持久化在 PostgreSQL 中，数据库自身的存储与备份需另行保证。

## 反向代理与回滚

- Keycloak 只监听在回环地址或内部网络上，由反向代理对外提供 HTTPS。
- 反向代理必须覆盖 `X-Forwarded-For`，入口限流示例见[部署说明](../docs/deployment.md#入口限流与可信代理)。
- 回滚时先停止新服务，再恢复原镜像和原运行参数。回退镜像不会撤销已写入数据库的用户或邮箱验证标记。
- 不要执行 `docker compose down -v` 或 `docker volume rm` 来“清理”生产数据。
