# koko-chat 消息队列设计

状态：架构基线，2026-09-08 已实现文本单聊的生产者、两级消费者、confirm/return、租约恢复、三档重试及死信。与 [整体架构](architecture.md) 配套；实际验证见 [单聊验收](chat-verification.md)。

当前实现与下文完整目标有以下边界：

- 单个 JVM 同时承担接入、Outbox、分发和网关消费。网关路由为进程随机 UUID，独占队列名由应用生成；同一进程 AMQP 重连时沿用该路由并自动重建队列，尚未实现下文每次 AMQP 连接更换 gatewayEpoch 的方案。
- Redis 每个设备会话路由租约 15 秒，每 5 秒续期。断线后依靠过期清理，网关投递前再次核对本机连接和数据库认证会话。Redis 查询失败进入重试，不能解释为用户离线。
- 当前按单聊成员逐设备发布网关任务，未实现群扇出或按节点批量聚合。网关队列最多 5000 条、TTL 30 秒；通知过期、连接退出时由历史同步恢复。
- 消息与 Outbox 同事务提交，Outbox 最多认领 5 条、租约 60 秒；发送时待发布数达到 10000 拒绝新消息。该检查是背压阈值，跨会话并发时并非严格全局容量上限。
- 已实现 5/30/120 秒重试与第 4 次转死信；真实测试覆盖 5 秒 TTL 回流与畸形事件死信，尚未逐档等待 30/120 秒或实施 broker 集群故障演练。
- 开发 RabbitMQ 为单节点；quorum 队列类型不代表已经具备多副本容灾。设备回执不承担 MQ ACK，客户端每 10 秒固定上界补拉保证最终补齐。
- DLQ 只有保留与诊断日志，尚无人工重放页面、指标告警、Outbox 归档和生产容量结论。

## 1. 选型与实际职责

采用 **RabbitMQ + Spring AMQP**，从首期进入聊天主链路。选择依据是本项目需要明确的任务路由、消费确认、并发控制、失败重试和死信处理；它们可以围绕单聊、群聊和多节点连接形成可验证的工程实践。

| 组件 | 核心职责 |
| --- | --- |
| Netty | 长连接、鉴权、心跳、实际设备推送 |
| Spring Boot Service | 消息事务、权限、会话序号、幂等、群成员规则 |
| MySQL | 消息、成员、读进度与 outbox 的持久事实源 |
| RabbitMQ | 持久分发任务、群扇出、节点定向投递、削峰、消费重试与死信 |
| Redis | 当前设备会话、在线路由、连接票据与缓存 |

MQ 放在消息事务之后。它缓冲的是**分发与推送压力**，不能消除消息写入 MySQL 的压力。首期继续同步提交消息与 outbox，然后异步投递，保持 SEND_ACK 表示“服务端已保存”。

## 2. 两级队列拓扑

```text
MessageService
  └─ MySQL 同一事务：message + message_outbox
       ├─ 提交后向发送者返回 SEND_ACK
       └─ OutboxPublisher
             ↓ publisher confirm + mandatory return 检查
          im.message.x（topic，持久 exchange）
             ↓ routing key: message.created
          im.dispatch.q（durable quorum）
             ↓ 多个 DispatchConsumer 可以竞争消费
          权限校验 → 群成员展开 → Redis 当前设备路由 → 按节点聚合
             ↓ 全部目标确认发布或明确离线后，ACK 原业务事件
          im.gateway.x（direct，持久 exchange）
             ├─ gatewayRoute A → 节点 A 在线队列 → A 的消费者 → A 的 Netty
             └─ gatewayRoute B → 节点 B 在线队列 → B 的消费者 → B 的 Netty

可重试的业务失败 → im.retry.x → 5s / 30s / 120s quorum 队列
                                     └─ TTL + DLX → im.message.x → im.dispatch.q
无法处理 / 次数耗尽 → im.dead.x → im.dispatch.dlq（durable quorum）
```

节点在线队列由 broker 生成名称，类型为 classic，exclusive=true、autoDelete=true、durable=false，属于当前 AMQP 消费连接。图中的“节点 A/B 队列”是职责名称，不是让各节点共同消费一个队列。

首期一个后端进程包含 OutboxPublisher、DispatchConsumer、GatewayDeliveryConsumer；多节点时运行相同程序。业务队列竞争消费，网关队列定向消费，二者的分工保持一致。

Direct exchange 按 routing key 精确匹配 binding。独立功能订阅需要各自的队列；将两个消费者接到同一队列会形成任务分担，而不是让两者各处理一次。[RabbitMQ Exchange 模型](https://www.rabbitmq.com/tutorials/amqp-concepts)

## 3. 队列与路由契约

| 名称 / 角色 | 类型 | 约定 |
| --- | --- | --- |
| im.message.x | durable topic exchange | 已提交业务事件；首期 routing key 为 message.created |
| im.dispatch.q | durable quorum queue | 绑定 message.created；无消息 TTL；限制积压，overflow=reject-publish |
| im.gateway.x | durable direct exchange | 按 gatewayRoute 定向投递到节点消费者 |
| broker 生成的节点队列 | exclusive、auto-delete classic queue | 只容纳短时在线尝试；建议消息 TTL 30 秒、有限长度、拒绝溢出发布；不作为离线存储 |
| im.retry.x | durable direct exchange | retry.5s、retry.30s、retry.120s 分别匹配固定 TTL 队列 |
| im.dispatch.retry.5s.q / 30s.q / 120s.q | durable quorum queue | 没有业务消费者；到期经 DLX 回 im.message.x，死信 routing key 为 message.created |
| im.dead.x | durable direct exchange | routing key dispatch.dead |
| im.dispatch.dlq | durable quorum queue | 保留无法处理事件及失败上下文；首期不自动循环回放 |

exchange、queue、binding 在部署/启动时声明并校验，必需 binding 缺失时不能将 publisher confirm 当作整个业务拓扑已就绪。队列参数一旦建立不能随意改变，类型或不兼容参数变化通过受控迁移处理。

长期分发任务与 retry/DLQ 采用 quorum，以验证复制队列的可靠消费；每个短命设备或连接不单独建立 quorum 队列。节点在线队列是一种可恢复的实时通知通道，进程或 AMQP 连接失效时可能丢失其中的提示。MySQL 消息不因此删除，客户端通过游标同步恢复。

开发环境可以用单个 RabbitMQ 节点验证流程；单节点不能提供 broker 故障下的高可用。需要高可用演练时使用三个 broker 节点和三副本 quorum，记录多数派不可用期间的行为。[Quorum Queue 说明](https://www.rabbitmq.com/docs/quorum-queues)

## 4. 发布确认与数据库一致性

message_outbox 初始状态为 PENDING。Publisher 用数据库短事务取得租约，在事务外发布，状态可以为 PENDING → PUBLISHING → PUBLISHED；租约到期重新认领，持续错误记录 last_error 并报警。每次认领生成新的 lease_token，完成或失败更新必须匹配该令牌，避免过期发布者覆盖新的认领状态；具体字段见 [数据库说明](database.md)。

生产者设置持久消息属性，使用 correlated publisher confirms，开启 mandatory 和 returns。只有该次发布 confirm=ACK 且未返回 unroutable，才把 outbox 标记为 PUBLISHED。

| 失败场景 | 处理 |
| --- | --- |
| 数据库回滚 | 不回 SEND_ACK，不产生可发布事件 |
| 数据库提交后、发布前进程退出 | 租约恢复后继续发布 |
| 发布超时或连接断开 | 结果未知，使用原 eventId 重试 |
| 收到 basic.return，即使随后 confirm=ACK | 未成功路由；保留 outbox，修复 binding 或按策略重试 |
| MQ 已确认，更新 outbox 前退出 | 可能重复发布，由下游幂等处理 |
| Broker 流控、磁盘告警或队列拒绝发布 | 降低发布并发，保留待发布记录，不忙等重试 |

RabbitMQ 对不可路由消息也可能发出 publisher ACK；因此确认与 returns 必须关联处理。确认只覆盖 broker 的接收责任，不代表消费者或设备已经完成处理。[RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)

实现中使用 Boot 管理的 Spring AMQP 依赖和 `spring-boot-starter-amqp`。拟采用 `publisher-confirm-type=correlated`、`publisher-returns=true`、template mandatory=true，监听容器采用 MANUAL ACK。精确配置、超时和回调实现需随锁定版本做集成验证。[Spring AMQP 发布确认](https://docs.spring.io/spring-amqp/reference/amqp/template.html)

## 5. 消费、群扇出与确认语义

### 5.1 DispatchConsumer

1. 校验 eventVersion/eventType，按 messageId 加载已提交消息；读取当前成员、joinSeq 和 membershipEpoch。
2. 初次事件的 recipientScope 为空时，展开单聊对端/群成员及发送者其他在线设备。来自网关失败的重试事件携带失败用户/设备范围，只在该范围内重新校验权限并读取当前路由，不再全量群扇出。
3. 查询 Redis 当前设备路由；正常查询得到无有效设备表示 OFFLINE，可终结在线分发。Redis 查询失败表示依赖异常，不能当成离线跳过。
4. 按 gatewayRoute 聚合设备，向 im.gateway.x 发布任务，任务携带原 eventId、当前 attempt、目标 userId/deviceId/sessionId、membershipEpoch、消息 ID 和 seq。
5. 所有目标队列均确认接收，或被明确分类为 OFFLINE/STALE 后，手动 ACK 原业务事件。任一未知结果、网络或依赖错误按重试策略处理。

部分目标成功、其余失败时，DispatchConsumer 崩溃恢复可能重放当前事件范围，已成功目标可能收到重复提示；已明确失败的目标可缩小为重试 recipientScope。网关发起重试始终只携带该任务中失败的用户/设备，避免多个失败网关分别触发全量群扇出而放大任务数量。整个链路继承并递增同一 attempt，不因转换任务格式而归零。

不能在任务开始时写全局 eventId“已消费”，导致后续重试漏掉其他接收者。新增有数据库副作用的独立订阅者时，可在其业务事务内用 `(consumer_name, event_id)` 唯一记录保证副作用幂等；这不能直接解决 Socket/MQ 双写问题，也不适合作为部分投递的完成标志。

群消息正文只写一次，MQ 可按节点聚合多个设备的投递指令。首期群人数上限 200，批次仍限制设备数量及 JSON 字节大小；超出时分批。派发节点并发增加后，MQ 不保证客户端最终看到的会话顺序，仍以数据库 conversation seq 为准。

### 5.2 GatewayDeliveryConsumer

- 仅消费自己声明的在线队列。检查目标 gatewayRoute、当前 sessionId、成员周期和消息可见范围，再调用本地 Netty Channel。
- Netty 写入成功只代表完成本次本地网络发送尝试，随后可 ACK 网关任务；不持有 MQ delivery 等待客户端 RECEIVED_ACK 或 READ。
- 确认设备离线、会话已过期、成员权限失效或在线任务超过期限时，终结该在线任务并 ACK，依靠同步恢复合法消息。陈旧路由可触发一次同步提示/路由刷新，不向原队列无限 requeue。
- 临时数据库/网络依赖错误转换为 message.created 重试格式，保留原 eventId，继承并递增 attempt，recipientScope 只含失败用户/设备；重新计算的是这些对象的当前权限和路由。格式错误进入 DLQ。转交成功确认后才 ACK 网关原件。
- 慢连接按 Netty 写缓冲水位进行限流，必要时关闭连接促使重连同步，不能无限堆积内存或 AMQP 未确认消息。

Netty Channel 与 AMQP Channel 是不同对象。MQ ACK 必须使用该 delivery 所属的 AMQP Channel，异步 Socket 回调需要交回受控消费者上下文或使用框架支持的异步确认，不能任意线程并发操作共享 AMQP Channel。

### 5.3 分开的五个状态

| 状态 | 含义 |
| --- | --- |
| SEND_ACK / PERSISTED | 消息和 outbox 已提交 MySQL |
| Publisher confirm | MQ 接受该次发布；仍需同时检查 returns |
| Consumer ACK | 当前消费阶段处理或责任转交完成 |
| RECEIVED_ACK | 指定设备已持久化收到的消息 |
| READ | 用户的会话已读进度推进 |

消费者应限制 prefetch 和并发，起始可用小 prefetch（例如 20）再压测调整；未 ACK 数量不是越大越好。[RabbitMQ 消费者说明](https://www.rabbitmq.com/docs/consumers)

## 6. 重试、死信与恢复

### 6.1 有限重试

应用可重试错误使用三次延迟重试：5 秒、30 秒、120 秒，之后进入 DLQ。数值是首期配置建议。失败处理将原业务事件和递增的 attempt 发布到对应 retry queue，**confirm 成功且无 return 后才 ACK 原件**；不能先 ACK 再发送重试消息。

每个重试队列使用固定 queue-level TTL，配置 DLX 回 im.message.x / message.created；到期不是精确的任务调度时间，不将它用于毫秒级时序控制。Gateway 失败转重试时将任务转换为标准业务事件加 attempt/recipientScope 元数据，不能把网关 envelope 原样投进只识别 message.created 的消费者；recipientScope 不携带过时的会话快照，必须重新解析当前路由。

所有承担重试转交的 quorum 队列显式配置 `dead-letter-strategy=at-least-once`、`overflow=reject-publish`、正确的 dead-letter exchange/routing key，并验证对应版本的必要功能已启用。普通默认 DLX 转交不自动具备相同保证。[RabbitMQ dead lettering](https://www.rabbitmq.com/docs/dlx)、[Quorum dead-letter 配置](https://www.rabbitmq.com/docs/quorum-queues#dead-lettering)

im.dispatch.q 另外设置明确的 broker delivery-limit（建议 5）并配置 DLX 到 im.dead.x / dispatch.dead，兜底进程反复崩溃形成的毒消息。这个计数与应用 attempt 分开：应用 attempt 控制定时重试，broker 限制反复未正常确认的投递。

retry/DLQ 的再次发布失败时不 ACK 原件，采用有界退避。对持久业务队列，必要时暂停或关闭消费 channel，使未确认任务重新可投递，避免持续 NACK(requeue=true) 形成热循环。对独占、auto-delete 网关队列，应尽量保持原连接与未 ACK 任务；如果最后消费者或连接失效导致队列被删除，在线任务可能丢失，只能依赖 MySQL 游标同步恢复，不能承诺它返回持久队列。持久 DLX 目标不可用可能反向造成积压，需监控和恢复目标队列。

### 6.2 死信处理

无法解析的版本/格式、持续缺失的消息引用、次数耗尽等进入 im.dispatch.dlq。记录原 eventId、messageId、阶段、错误码、首次/最后失败时间和 attempt。DLQ 不是静默删除入口，也不是自动无限循环队列。

排查并修复原因后，按范围、限速回放到 im.message.x / message.created。保留业务 eventId/messageId 和原 recipientScope，额外生成 replayId 并记录操作审计；受控回放可重置 attempt，但不伪造新的业务消息或默认扩大接收范围。重新读取当前成员和设备路由，不能重用过时的设备快照。

## 7. 网关队列生命周期与多节点

RabbitMQ exclusive 队列绑定 AMQP 连接，不等同于 JVM 进程生命周期。节点可能保有 Netty 客户端连接，但 RabbitMQ 消费连接已经断开并重建。

网关 routing key 设计为 `gw.<nodeId>.<bootId>.<gatewayEpoch>`：bootId 标识 JVM 本轮启动，gatewayEpoch 每次 MQ 消费连接重建时更新。队列名由 broker 分配，应用创建 binding、启动消费者成功后，才将 gatewayRoute 写入 Redis。[RabbitMQ 队列生命周期](https://www.rabbitmq.com/docs/queues)

| 情况 | 行为 |
| --- | --- |
| 正常启动 | 声明队列 → 绑定 → consumer 就绪 → 发布有效设备路由 |
| MQ 消费连接断开 | 暂停续约旧 gatewayRoute；本地认证与 Netty 状态按有效期保留 |
| MQ 恢复但 JVM 未重启 | 新建队列和 gatewayEpoch，更新现有活跃设备路由并触发游标校验 |
| mandatory return 显示旧队列不可达 | Dispatcher 刷新一次路由；有新路由则重发，明确离线则终结，依赖失败则延迟重试 |
| 旧队列已接收但随后被删除 | 可能失去在线提示；数据库消息仍可补拉，不能宣称 MQ 端到端零丢失 |
| 节点退役 | 停止接入和路由续约，关闭/迁移客户端连接，排空可处理任务后关闭 AMQP 连接 |

本系统目标是可重试的分发和可恢复的设备同步。其恢复依赖 MySQL 持久化、本地缓存、序号和同步契约，不靠临时节点队列长期保存离线消息。

## 8. 事件样例与幂等标识

```json
{
  "eventVersion": 1,
  "eventType": "message.created",
  "eventId": "outbox-uuid",
  "occurredAt": "2026-09-07T12:00:00Z",
  "messageId": "987654321012345678",
  "conversationId": "123456789012345678",
  "seq": "42",
  "attempt": 0,
  "recipientScope": null
}
```

- clientMsgId：同一发送意图的客户端重试标识。
- messageId / conversation seq：服务端消息身份与会话内顺序。
- eventId：一次已提交业务事件的稳定标识，MQ 重试沿用。
- attempt / recipientScope：投递重试次数与范围。null 表示首次全量展开；失败重试可以是 `[{"userId":"123","deviceId":"desktop-1"}]`，只限制接收范围，不固定旧路由或跳过当前权限检查。
- gatewayRoute + sessionId + membershipEpoch：本次在线设备任务的合法路由范围。
- deliveryId：可由 eventId 与目标设备/session 派生，辅助去重和诊断；客户端仍以 messageId 去重。

MQ 事件优先传消息引用；正文与当前权限通过 Service 查询权威数据，不在持久队列里保存连接凭证。AMQP message_id 对应 eventId，JSON 内 64 位业务 ID 和 seq 仍用字符串。

后续好友申请结果、群成员变更、已读进度变化可新增独立事件类型；图片处理、通知或搜索索引各自绑定独立队列。订阅者以数据库当前状态和事件版本为依据，重放不能重新授权已失效成员。

## 9. 监控与验收

需要观察：outbox 待发布数量与最老记录年龄、publisher confirm 延迟/NACK/return、dispatch ready/unacked、消费者耗时和重投递率、各级 retry/DLQ 数量、网关队列重建与旧路由比例、群扇出目标数、消息保存到设备接收的延迟，以及 broker 磁盘/内存告警。

MQ 不可用时允许在配置的 outbox 积压阈值内继续保存；阈值之外对新发送返回可重试过载错误。恢复时限制补发速度和消费者并发，避免把积压一次性压向数据库、Redis 和 Netty。

最低验收用例：

1. 数据库提交后立刻退出，恢复后 outbox 继续发布。
2. confirm 丢失/更新 PUBLISHED 前退出，重复事件只产生一条业务消息。
3. 错误 routing key 引起 return，不能误记为成功。
4. 群消息部分节点成功后消费进程退出，剩余目标仍可收到或补拉，已成功目标去重。
5. 消费者异常触发三档重试；多个网关失败只重试各自失败设备，attempt 不归零；毒消息最终进 DLQ，回放重新校验权限。
6. retry/DLQ 转发失败，不提前 ACK 原消息。
7. Redis 故障不会把所有设备误判为离线。
8. MQ 连接重建而 Netty 连接仍存活，新 epoch 和路由可恢复投递。
9. 节点退役、旧成员周期、旧 session 任务不会写入错误连接。
10. 网关在线提示被丢弃、设备离线或慢消费后，按连续 seq 同步补齐消息。

这些是消息功能阶段的验证要求。骨架的配置校验不能代替上述 MQ 业务与故障测试。
