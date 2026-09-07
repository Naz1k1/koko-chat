# 本机中间件

后端默认骨架模式不依赖 Docker。需要验证 `local` 配置时，再启动 MySQL 8.4、Redis 7.4、RabbitMQ 4.2、RustFS 1.0.0-rc.5；这些都是独立开发服务。

业务表由后端 Flyway V1–V6 顺序迁移建立，Compose 仅负责创建空库和中间件。使用已有 MySQL 实例时，可参考 [数据库说明](../docs/database.md) 和 [建库脚本](mysql/00-create-database.sql)。

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

`down` 保留命名卷。RabbitMQ 固定 hostname，以便重建容器后使用同一节点数据目录。数据库初始化变量只在首次创建数据目录时生效，后续修改密码应通过各服务的管理操作处理。单节点 RabbitMQ 用于开发，不能证明生产高可用能力；业务队列、Outbox 与重试消费者已接入。

镜像沿各维护版本线更新，正式部署时再锁定经验证的镜像摘要。配置依据：[MySQL 官方镜像](https://hub.docker.com/_/mysql)、[RabbitMQ 官方镜像](https://hub.docker.com/_/rabbitmq)、[Compose 环境变量](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)。

## 验证数据库约束

中间件健康后，在根目录运行 `./scripts/verify-database.sh`。脚本读取未纳入版本控制的 `deploy/.env`，使用 root 仅创建与清理独立的随机测试库；业务服务仍使用普通 `koko` 账号。可在脚本后传 Maven 参数，例如 `-s /path/to/settings.xml`。

如果本机已有 MySQL 占用 3306，在 `.env` 中设置 `MYSQL_PORT=3307`；不要停止其他项目的数据库。此端口会同时用于 Compose 映射和后端 JDBC 连接。

## RustFS 中的图片存在哪里

默认私有桶为 `koko-chat`，对象键为 `attachments/<上传 UUID>`。实际字节保存在 RustFS 容器 `/data`，由 Compose 命名卷 `rustfs-data` 持久化；默认项目名下的 Docker 卷名为 `koko-chat_rustfs-data`。macOS Docker Desktop 的命名卷在其 Linux 虚拟机内，不是项目目录下的普通图片文件夹。MySQL `attachment` 表保存文件名、大小、摘要、对象键和归属，`message` 保存附件引用。

- S3 API：`http://127.0.0.1:9000`；管理控制台：`http://127.0.0.1:9001`。
- `.env` 中配置 `RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`，后端和 RustFS 使用同一组开发凭证；不传给客户端、不提交仓库。已有 `.env` 需要补齐新增字段，建议权限为 600。
- `RUSTFS_ENDPOINT` 默认 `http://127.0.0.1:9000`，`RUSTFS_BUCKET` 默认 `koko-chat`。若修改映射端口，同时更新 endpoint；后端进入容器后应改用服务网络地址。
- 后端首次写入时检查并创建私有桶；健康探针暂未包括 RustFS，需要看容器 `/health` 和实际上传验收。
- `docker compose down` 保留对象；`down -v` 会删除命名卷及数据，不用于日常停止服务。

本轮固定使用官方候选版本 `rustfs/rustfs:1.0.0-rc.5`，不是稳定版承诺；此单节点部署用于开发联调。Java 接入使用 AWS SDK v2 2.54.13、S3 v4 签名和 path-style，保留默认 TLS 校验。依据：[官方 Docker 部署](https://docs.rustfs.com/en/installation/container/docker)、[Java SDK 指南](https://docs.rustfs.com/en/developer/sdk/java)、[RC.5 发布记录](https://github.com/rustfs/rustfs/releases/tag/1.0.0-rc.5)。

## 本机语音中继

单机语音直连无需 TURN；强制中继测试需启动可选的 coturn 4.17.2-r0。已有 deploy/.env 补齐以下字段：

```dotenv
KOKO_TURN_URLS=turn:127.0.0.1:3478?transport=udp
KOKO_TURN_SECRET=替换为足够长的随机密钥
TURN_EXTERNAL_IP=127.0.0.1
```

密钥同时提供给后端和 coturn，保持 .env 未跟踪且权限 600。启动命令：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile calls up -d coturn
```

TURN 控制端口 3478 UDP/TCP、中继端口 49160–49179 UDP 均只映射回环地址。容器动态使用自己的实际网卡地址作为 relay-ip；不能绑定 0.0.0.0 作为中继地址，否则 external-ip 映射可能令权限检查收到零地址而拒绝。模板允许 loopback peer，并关闭 TLS，仅用于本机两个客户端测试；不应直接开放到公网。每凭证最多 4 个分配，总分配上限 40，这是开发限额。

多台机器或公网部署需配置可达域名/IP、实际中继地址及端口范围，移除 allow-loopback-peers，按需求配置 TLS、证书、网络出口限制与带宽容量。当前尚未执行公网和双物理机验收，不能把本机 Docker 测试当作公网可用性证明。参考 [coturn 官方项目](https://github.com/coturn/coturn) 与 [4.17.2 发布](https://github.com/coturn/coturn/releases/tag/4.17.2)。

缩略图与原图共享 RustFS 卷，分别使用 thumbnails/ 和 attachments/ 前缀。未发送上传默认 24 小时到期，后台自动回收字节并保留 EXPIRED 墓碑；已发送对象不会被该任务删除，详见 [附件契约](../contracts/attachments.md)。
