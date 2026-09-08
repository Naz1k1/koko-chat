# 后台运维手册

本轮在现有 Java 21 / Spring Boot 3 / Netty / Service 分层中增加运维模块，没有引入 DDD 或拆分微服务。先处理监控、失败消息和多节点恢复；生产 TLS、备份恢复与容量压测仍需后续实施。

## 启用本机运维

Flyway 自动执行 [V7](../server/src/main/resources/db/migration/V7__create_operations_audit.sql)，增加 ops_dead_letter 和 ops_action，旧迁移不变。deploy/.env 提供至少 32 字符的独立随机 KOKO_OPS_TOKEN，以及 KOKO_OPS_ACTOR（例如 local-operator）；不要复用聊天令牌或中间件密码。未配置 token 时运维 API 与采集/归档任务关闭。当前本机 .env 已补齐随机凭据，保持未跟踪、权限 600。

从仓库根目录加载环境并启动 local 后端：

```bash
set -a
source deploy/.env
set +a
(cd server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)
```

另一个终端同样加载环境，然后使用无需第三方 Python 包的命令：

```bash
python3 scripts/ops.py status
python3 scripts/ops.py list --limit 20
python3 scripts/ops.py metrics
```

默认操作 127.0.0.1:8080，使用 `--url https://运维服务域名` 更换入口。CLI 只允许回环 HTTP 或正常验证证书的 HTTPS，拒绝跟随重定向。凭据从环境读取，不作为命令行参数。status 返回的是最近一次定时快照，启动早期可能返回 503，稍后重试。

## 处理失败消息

1. 查看 status 和 list，结合日志判断数据库、MQ、路由是否恢复。归档查询不显示消息正文，畸形事件标记不可重放。
2. 对可重放记录执行以下命令，将示例 ID 换成实际归档 ID：

```bash
python3 scripts/ops.py replay 归档ID --reason '修复路由后重新投递'
```

CLI 会先打印本次操作 UUID，再发请求。若超时或响应丢失，按原 UUID 查询，不生成新编号盲目重放：

```bash
python3 scripts/ops.py action 操作UUID
python3 scripts/ops.py replay 归档ID --reason '修复路由后重新投递' --request-id 原操作UUID
```

PUBLISHED 表示 broker 已确认可路由发布，仍需由接收端回执/历史同步确认设备收到。相同 messageId 重复投递不会创建新消息。无效事件无法通过修改正文强制重放；人工确认后可记录原因，保留证据并清除该次未检查告警：

```bash
python3 scripts/ops.py ack 归档ID --reason '确认畸形事件，保留归档不重放'
```

归档和重放不会删除聊天消息、附件或队列，也不会向全部用户广播。后续再次出现的失败仍会报警。

## 常驻监控与邮件

已增加独立 Prometheus / Alertmanager 常驻部署、邮件接收方配置与故障/恢复通知。启动、凭据、告警策略、实际投递验证和运行边界见 [常驻监控与邮件手册](monitoring.md)。监控邮箱与后端环境分别使用 deploy/monitoring/.env 和 deploy/.env。

```bash
python3 scripts/monitoring.py up
python3 scripts/monitoring.py status
python3 scripts/monitoring.py test-email
```

邮件未配置时 up 明确报错；显式 --monitor-only 可只启动采集和告警页面。生产 TLS、备份恢复与容量压测不在本轮范围。

## 验证与故障演练

```bash
./scripts/verify-auth.sh
./scripts/verify-database.sh
./scripts/verify-multi-node.sh
```

前两个脚本沿用已有开发中间件验证约定；verify-auth 临时关闭普通测试进程的运维自动任务，运维用例单独配置测试 token 和 MQ 命名空间。

verify-multi-node 使用 root 创建/删除随机 koko_chat_test_ 数据库，两个测试 JVM 也使用该测试凭据，仅连接这个临时库；使用独立 MQ 命名空间和随机端口，启动后验证实际运维 CLI、跨节点消息、发布租约恢复，强制终止一个测试 JVM，再验证存活节点补拉和推送。正常成功或失败都会结束子进程、删除专属队列及临时库。Redis 测试路由使用唯一前缀，最多 90 秒过期。脚本不会停止现有后端、重启 broker 或删除业务库。

不要将该测试等同于 broker 集群切主、物理机断电、网络分区、长连接容量压测或生产容灾验收。当前服务端仍使用单节点开发中间件，生产配置和备份恢复尚未交付。
