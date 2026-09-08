# 监控、死信与多节点验收

日期：2026-09-08。本轮完成上一阶段确定的后台完善第一批：运维监控和告警、失败消息归档查询与重放、多节点故障验证。保留 Java 21 / Spring Boot 3 / Netty / 普通 Service 分层。

## 已交付

- 独立运维凭据与默认关闭入口，禁止缓存、正文限制、并发限制；普通聊天令牌不能访问。
- 每 15 秒缓存监控快照，Prometheus 格式指标与 7 条告警规则；覆盖 MySQL/Redis/RabbitMQ/RustFS、Outbox 和队列积压、死信、重放、连接、业务线程池。日志记录告警触发与恢复。
- V7 新增两张运维表。死信先归档再 ACK，归档查询不返回正文；人工确认和重放均有原因及幂等编号。重放使用短事务认领、发布租约和原消息编号，失败重试。
- Python 运维 CLI、接口契约、Prometheus 抓取配置、告警规则与规则测试、多节点验证脚本。

## 实际结果

| 验证 | 结果与边界 |
| --- | --- |
| 完整后端 verify | 33 项，0 失败、0 错误；31 项通过，独立数据库和双进程用例在此命令按条件跳过 |
| 独立 MySQL 迁移 | 单独 1 项通过；V1–V7 首次及重复执行，15 张表；临时库删除 |
| 双进程恢复 | 单独 1 项通过；两个实际后端 JVM、两个 WebSocket 客户端、独立临时库与 MQ 命名空间 |
| 归档失败 | 注入 JDBC 归档失败后原件仍可从真实 RabbitMQ 读取；成功后 ACK，再次相同正文合并次数 |
| 重放与审计 | 原编号操作重试返回原结果，内容冲突/已有待重放操作被拒绝；注入 MQ 发布失败仍保留 PENDING；过期租约重新认领并发布，保留原 eventId / messageId / 接收者范围 |
| 访问和格式 | 无运维凭据或普通 token 返回 401；非法分页 400；响应不含正文；畸形事件拒绝重放，可人工确认归档 |
| 告警 | 未检查死信和老 Outbox 触发告警，处理后恢复；缺失依赖状态不会被当成健康 |
| 真实 CLI | 在启用运维定时任务的测试 JVM 上执行 ops.py status，得到已更新的 MySQL 健康快照 |
| Prometheus | 官方 promtool 3.5.0 验证抓取配置语法、全部告警规则及触发/恢复用例；真实 HTTP metrics 输出通过 check metrics |

双进程场景先验证分属两个节点的客户端实时收消息，再将已发布 Outbox 注入为过期 PUBLISHING，确认重投沿用消息 ID 且数据库仍只有一条原消息。随后强制结束被叫所在 JVM，存活节点继续接受消息；同一用户会话连到存活节点，补拉故障期间消息并收到新的实时推送。此测试包含真正的子进程终止，不是仅模拟一个 Service 返回异常。

归档、重放集成测试沿用开发测试库的随机账号与独立 MQ 命名空间，结束定向清理。多节点测试另建随机数据库，进程和队列由测试持有并清理，不停止用户已有服务。规则验证容器退出即删除，没有部署常驻监控服务或发送外部通知。桌面代码未修改，本轮未重复构建 DMG 或跑完整桌面套件。

结束核查：ops_dead_letter、ops_action、message_outbox 与 it_ 测试账号均为 0，随机临时数据库剩余 0，Flyway 1–7 全部成功。

## 复现

配置 JDK 21 和 deploy/.env，启动本项目中间件后：

```bash
./scripts/verify-auth.sh
./scripts/verify-database.sh
./scripts/verify-multi-node.sh

docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/deploy/monitoring:/work:ro" prom/prometheus:v3.5.0 \
  check config --syntax-only /work/prometheus.yml

docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/deploy/monitoring:/work:ro" prom/prometheus:v3.5.0 \
  test rules /work/alerts.test.yml
```

测试脚本支持追加本机 Maven 参数。真实指标格式检查可先使用 ops.py metrics 保存到临时文件，再通过 promtool check metrics 的 stdin 校验。不要将凭据写入文件名或命令行参数。

## 尚未完成的上线工作

当前具备后台运维的基础能力，仍没有生产 HTTPS/WSS 入口、常驻 Prometheus/Alertmanager、通知接收方、数据库/对象存储备份恢复演练或容量结论。未验证 broker 集群切主、Redis/MySQL 主从故障、真实网络分区、跨机房切换。死信正文和审计保留策略也需后续确定。

下一批优先处理生产配置与备份恢复，再做连接数、吞吐和延迟压测。操作步骤见 [运维手册](operations.md)，接口及可靠性边界见 [运维契约](../contracts/operations.md)。
