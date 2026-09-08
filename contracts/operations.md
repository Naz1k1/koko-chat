# 后台运维接口

当前 `local` 已实现监控快照、Prometheus 指标、死信归档查询、人工重放和操作审计。独立 `KOKO_OPS_TOKEN` 非空时启用运维入口及后台任务，至少 32 字符；普通聊天 access token 无权使用。`KOKO_OPS_ACTOR` 是服务配置的凭据标识，默认 local-operator，同一共享凭据不代表已识别具体自然人。

所有请求携带 `Authorization: Bearer <运维凭据>`。入口为 `/internal/ops`，默认未配置时返回 404，凭据错误返回 401，禁止缓存，正文最多 4096 字节，每实例最多并发 4 个运维请求。部署时应限制为运维网络并使用 HTTPS，凭据不出现在 URL、日志、示例或 Git 中。

| 接口 | 行为 |
| --- | --- |
| GET /internal/ops/status | 缓存快照：collectedAt、metrics、alerts；快照超过 90 秒或尚未生成时 HTTP 503，告警本身记录在 alerts |
| GET /internal/ops/metrics | Prometheus text 0.0.4 指标；包含 snapshot_age_seconds，不暴露用户/消息 ID 标签 |
| GET /internal/ops/dead-letters?limit=20&after=… | 按归档 ID 升序分页，limit 1–50；items 与 nextCursor，不返回原始正文 |
| POST /internal/ops/dead-letters/{id}/replays | 创建或返回同编号重放操作，HTTP 202；不是设备送达承诺 |
| POST /internal/ops/dead-letters/{id}/acknowledgements | 确认已人工检查此归档，保留故障记录与审计，不重新投递 |
| GET /internal/ops/actions/{requestId} | 查询操作类型、凭据标识、原因、状态、尝试次数与错误类型 |

两个 POST 正文均为：

```json
{"requestId":"客户端生成的小写UUID","reason":"修复路由后重新投递"}
```

reason 为 1–200 字符，不接受控制字符。相同 requestId、归档、类型、原因和凭据标识返回原操作；内容不同返回 409 OPS_COMMAND_CONFLICT。响应丢失后先查询 action，重试保持原编号。每条死信同时最多一个未完成重放，重复申请返回 409 REPLAY_PENDING。语法或分页错误返回 400；归档/操作不存在返回 404；无效事件或原消息不存在返回 409 NOT_REPLAYABLE。

## 归档和重放责任

启用后，每 5 秒最多从本命名空间的 dispatch.dlq 取 20 条，先将原始事件保存到 MySQL，再在原 AMQP channel 上 ACK。归档失败 NACK 并保留原件。命名空间与正文 SHA-256 共同确定归档 ID，相同内容合并计数；计数也包含归档成功但 ACK 丢失造成的重投，不是独立故障数量。单条归档正文上限 256 KiB，超限原件留在 broker，运维日志提示处理失败。

查询返回首次/最近观察时间、次数、是否已检查、是否可重放，以及有效事件的 eventId/messageId。畸形或引用丢失事件不能重放，可添加原因确认归档。按哈希 ID 翻页不是时间顺序，新归档可能落在游标之前；检查新增事件应从第一页刷新。

重放前检查事件类型/版本、原消息、会话序号和原 Outbox 归属，再重建 attempt=0 的消息事件，保留原 eventId、messageId 及定向接收者范围。旧网关地址不被复用，消息重新经过当前成员权限、Redis 路由与 Netty 连接检查。不重建原消息，不递增会话 seq；接收端仍需按消息 ID 去重。

操作先落 MySQL，状态为 PENDING；后台以 FOR UPDATE SKIP LOCKED 认领最多 5 条，进入 PUBLISHING 并持有 60 秒租约。事务外发布，只有 confirm ACK 且没有 mandatory return 才标记 PUBLISHED；失败回 PENDING，30 秒后重试。结果写回匹配租约令牌，进程退出后由其他实例重新认领。网络发布和数据库提交无法原子完成，重放仍可能重复投递，不能承诺 exactly-once。

ACK 操作为 ACKNOWLEDGED，只更新已检查时间。PUBLISHED 仅确认操作发起之前已观察到的失败；之后再次出现的死信仍会触发告警。重放等待过久会单独告警；原始归档及审计目前保留，无自动删除或正文编辑接口。

## 监控和告警

每 15 秒更新快照，HTTP 抓取直接读取内存。运维调度与业务定时任务分开，归档失败不阻止尝试已有重放。采集 MySQL、Redis、RabbitMQ、RustFS 状态、当前 JVM 的认证连接数、业务线程池活动数/排队数、Outbox 待发布数量/最老时间、队列 ready 数、未检查死信数和最老待重放时间。

RustFS 使用有限超时 HEAD 桶探测，尚未创建桶的 404 视为服务可达，不写入探测对象；这不证明磁盘容量或实际读写成功。RabbitMQ ready 数不包含 unacked。MySQL Outbox 是共享库统计，在多个节点的指标中重复出现，不能简单跨节点相加；connections 是每个节点的连接数，不是去重用户数。

默认阈值：依赖不可用；Outbox 最老 ≥60 秒或待发布 ≥100；有未检查死信；dispatch ready ≥1000；线程池排队 ≥200；重放最老 ≥60 秒。进程日志只在状态变化时记录 OPS_ALERT_FIRING / OPS_ALERT_RESOLVED，不重复刷同一告警。Prometheus 规则增加持续时间与抓取失联、快照过期检测。

这些是开发起始阈值，尚未基于容量压测校准。进程完全退出时自身无法记录告警，需外部 Prometheus 检测 up；本轮提供抓取配置与规则，未配置邮件/聊天通知或常驻 Alertmanager。

操作步骤见 [运维手册](../docs/operations.md)，验证范围见 [本轮验收](../docs/operations-verification.md)。语义参考 [RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms) 与 [Prometheus 告警规则](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)。
