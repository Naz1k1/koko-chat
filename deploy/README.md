# 本机中间件

后端默认骨架模式不依赖 Docker。需要验证 `local` 配置时，再启动 MySQL 8.4、Redis 7.4、RabbitMQ 4.2；这些都是独立开发服务。

业务表由后端 Flyway V1 统一建立，Compose 仅负责创建空库和中间件。使用已有 MySQL 实例时，可参考 [数据库说明](../docs/database.md) 和 [建库脚本](mysql/00-create-database.sql)。

在仓库根目录执行：

```bash
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，填写本机开发密码。
docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --wait
```

服务端口只绑定到本机 `127.0.0.1`。RabbitMQ 管理页面为 `http://127.0.0.1:15672`，使用 `.env` 中的 RabbitMQ 账号；可通过对应的 `*_PORT` 调整端口。

Spring Boot 不会自动读取 Compose 的 `.env`。启动 `local` 配置前，把同一份变量提供给进程；以下命令适用于本例简单的 shell 兼容变量文件：

```bash
set -a
. ./deploy/.env
set +a
(cd server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)
```

在 IDE 中运行时，可直接在运行配置中填写这些环境变量并激活 `local`。HTTP 默认 8080，Netty 默认 8081，配置说明见 [后端说明](../server/README.md)。

查看与停止：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
docker compose --env-file deploy/.env -f deploy/compose.yaml logs --tail=100
docker compose --env-file deploy/.env -f deploy/compose.yaml down
```

`down` 保留命名卷。RabbitMQ 固定 hostname，以便重建容器后使用同一节点数据目录。数据库初始化变量只在首次创建数据目录时生效，后续修改密码应通过各服务的管理操作处理。单节点 RabbitMQ 用于开发，不能证明生产高可用能力；业务队列、Outbox 与重试消费者将在消息功能阶段实现。

镜像沿各维护版本线更新，正式部署时再锁定经验证的镜像摘要。配置依据：[MySQL 官方镜像](https://hub.docker.com/_/mysql)、[RabbitMQ 官方镜像](https://hub.docker.com/_/rabbitmq)、[Compose 环境变量](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)。

## 验证数据库约束

中间件健康后，在根目录运行 `./scripts/verify-database.sh`。脚本读取未纳入版本控制的 `deploy/.env`，使用 root 仅创建与清理独立的随机测试库；业务服务仍使用普通 `koko` 账号。可在脚本后传 Maven 参数，例如 `-s /path/to/settings.xml`。

如果本机已有 MySQL 占用 3306，在 `.env` 中设置 `MYSQL_PORT=3307`；不要停止其他项目的数据库。此端口会同时用于 Compose 映射和后端 JDBC 连接。
