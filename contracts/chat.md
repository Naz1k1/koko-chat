# 文本聊天协议（单聊与群聊已实现）

适用于 `local` 配置，当前系统信息 `stage=attachments`（包含上一阶段单聊能力）。HTTP 使用访问令牌；WebSocket 先按 [认证契约](auth.md) 取得 `AUTH_OK`。用户/会话/消息 ID、seq 均为十进制字符串，客户端按整数比较；当前范围为 Java 正数 Long。时间为 UTC ISO-8601。

## 会话与补拉

| 请求 | 参数 / 行为 |
| --- | --- |
| `POST /api/conversations/direct` | JSON `{"account":"bob"}`，按准确账号查找；禁止自己。双方的数值 ID 排序生成唯一键，重复请求返回同一会话，成功 HTTP 200。当前无需好友关系。群聊通过 [群管理接口](groups.md) 创建。 |
| `GET /api/conversations` | `afterId=0&limit=50`；limit 1–100，按会话 ID 升序返回。包含当前有效单聊与群聊。 |
| `GET /api/conversations/{id}` | 返回一个当前可访问的会话摘要；供新增会话和缺失目录项核验。 |
| `GET /api/conversations/{id}/messages` | `afterSeq=0&toSeq=20&limit=50`；limit 1–100，返回 `(afterSeq,toSeq]` 可见消息，按 seq 升序。首次可省略 toSeq，后续沿用响应上界。 |

会话对象：

```json
{"id":"10","peerId":"2","account":"bob","nickname":"小波","membershipEpoch":"b6a403c9-334c-4f7b-af1c-019bcd8ca15c","visibleFromSeq":"1","latestSeq":"20","type":"DIRECT","ownerId":null,"lastReadSeq":"15","unreadCount":"3","peerLastReadSeq":"18"}
```

会话列表响应为 `{"conversations":[...],"nextCursor":"10","hasMore":false}`。ID 为随机正 Long，分页期间新建且 ID 小于游标的会话可能到下轮全量目录同步才出现；目录不是快照令牌。

历史响应为 `{"messages":[...],"membershipEpoch":"...","visibleFromSeq":"1","toSeq":"20","nextCursor":"20","hasMore":false}`。toSeq 限制为当前最大序号，成员可见范围不能越过 joinSeq。上界固定后，新消息留到下轮拉取。设备服务端游标只表明设备报告过的进度，不能据此跳过本机没有的数据。

## 发送与保存确认

```json
{"v":1,"type":"SEND","requestId":"rpc-1","conversationId":"10","membershipEpoch":"b6a403c9-334c-4f7b-af1c-019bcd8ca15c","clientMsgId":"d3375653-a09a-4353-951a-c640dc45008b","text":"你好"}
```

- requestId 最长 128 字符，只关联当前请求响应。客户端每次重试生成新的 requestId。
- clientMsgId 为 1–64 位字母、数字、下划线或连字符，在同一发送者下唯一。客户端先持久保存发送意图；超时或断线后保留原 clientMsgId、会话、成员周期和文本重发。
- 文本非空且最多 4096 UTF-8 字节，编码后的 body JSON 最多 8192 字节；WebSocket 整帧最多 16 KiB。当前只支持 TEXT。
- 服务端锁定会话行，原子递增 seq、保存 message 和 message_outbox。事务提交后才返回 SEND_ACK；重复内容返回原消息，编号复用但内容/会话/成员周期不同则拒绝。

确认示例：

```json
{"v":1,"type":"SEND_ACK","requestId":"rpc-1","serverTime":"2026-09-08T00:00:00Z","message":{"id":"101","conversationId":"10","seq":"1","senderId":"1","clientMsgId":"d3375653-a09a-4353-951a-c640dc45008b","type":"TEXT","text":"你好","serverTime":"2026-09-08T00:00:00Z"}}
```

这里表示服务器已保存，既不表示 MQ 已投递，也不表示对方收到或已读。

## 推送、接收与离线

```json
{"v":1,"type":"MESSAGE","membershipEpoch":"接收方自己的成员周期","message":{"id":"101","conversationId":"10","seq":"1","senderId":"1","clientMsgId":"d3375653-a09a-4353-951a-c640dc45008b","type":"TEXT","text":"你好","serverTime":"2026-09-08T00:00:00Z"}}
```

同一用户的在线设备都可能收到推送，包括发送设备。客户端按 messageId、会话/周期/seq 去重；MESSAGE 和 SEND_ACK 无先后承诺，重复事件是允许的。客户端将消息提交到 SQLite，连续序号补齐后发送：

```json
{"v":1,"type":"RECEIVED_ACK","requestId":"rpc-2","conversationId":"10","membershipEpoch":"接收方自己的成员周期","receivedSeq":"1"}
```

服务端返回 `{"v":1,"type":"RECEIVED_ACK_OK","requestId":"rpc-2","serverTime":"..."}`，设备游标以 GREATEST 单调更新，不能超过服务端 latestSeq。设备确认不推进用户已读。接收方离线时，历史消息仍在 MySQL，不依赖 Redis Pub/Sub 或网关临时队列保存；重新登录和周期补拉使用同一历史接口。

## 用户已读与未读计数

```json
{"v":1,"type":"READ","requestId":"read-1","conversationId":"10","membershipEpoch":"b6a403c9-334c-4f7b-af1c-019bcd8ca15c","readSeq":"20"}
```

服务端返回 `{"v":1,"type":"READ_ACK","requestId":"read-1","serverTime":"...","conversation":{...}}`。conversation 与 HTTP 会话摘要相同，包含：

| 字段 | 语义 |
| --- | --- |
| lastReadSeq | 当前用户、会话、成员周期共享的最大已读位置，非负 Long 字符串 |
| unreadCount | 当前可见范围内 seq 大于 lastReadSeq 且 senderId 不等于当前用户的消息条数，非负 Long 字符串 |
| peerLastReadSeq | 单聊对方的已读位置；群聊为 null，不表示所有成员已读 |

READ 必须在 `[joinSeq-1, min(latestSeq, 当前设备的 receivedSeq)]` 范围内；设备尚无确认记录时上界为 joinSeq-1。身份从已认证连接取得，不能代替其他用户阅读。与发送和成员变更共用会话锁，使用 GREATEST 推进。同周期的重复或较小有效进度只返回当前摘要，不倒退也不重复发通知。其他设备已读到更远位置时，READ_ACK 的 lastReadSeq 可以大于本次请求；这不能推进本机接收游标。

成功提交后，服务端异步路由 RabbitMQ 提示到阅读者全部在线设备，单聊另通知对方在线设备。接入节点复核登录身份和成员周期，查询当前摘要，再推送 `{"v":1,"type":"READ_UPDATE","conversation":{...}}`。群聊不向其他成员广播读位置。

READ_UPDATE 是可丢失的状态提示，复用有界临时网关队列，不写 message_outbox、不走聊天消息的重试/死信责任链。READ_ACK 的成功只取决于 MySQL 提交；MQ 不可用或提示丢失时，各端每 10 秒从 HTTP 快照恢复。快照和待上报阅读位置按成员周期单调合并，旧周期通知不得恢复已退出会话。

客户端只在有焦点且未被其他面板遮挡的聊天区，根据实际可见消息保存“读到此处”的位置；滚动经过不会立即上报。意图持久化后再发送，网络异常保留原位置重试。未读计数不能用 latestSeq-lastReadSeq 代替，因为这会把自己发送的消息计算在内。

## 错误与客户端行为

错误沿用 `v/type=ERROR/requestId?/serverTime/code/message`。HTTP 错误使用相同业务 code/message（HTTP 结构见认证契约）。

| code | 行为 |
| --- | --- |
| UNAUTHENTICATED | 身份无效，重新登录或重新取得票据 |
| USER_NOT_FOUND / SELF_CHAT | 检查对方账号 |
| INVALID_READ | 阅读范围非法；先补齐本机消息并确认设备连续游标，不能越过本设备已接收位置 |
| INVALID_MESSAGE | 修正参数，不自动生成新编号重发原错误 |
| NOT_A_MEMBER / MEMBERSHIP_CHANGED | 禁止旧成员周期继续操作，重新同步 |
| IDEMPOTENCY_CONFLICT | 停止自动重试，原编号不能绑定新内容 |
| BUSY / OUTBOX_FULL / SERVICE_UNAVAILABLE | 稍后使用同一 clientMsgId 重试 |

客户端目前每 2 秒检查到期待发送记录、每 10 秒补拉；请求超时 10 秒。失败待发送记录可手动重试，但不会篡改原内容或原成员周期。登录账号切换时取消旧任务、关闭旧库，再打开该服务和用户的独立 SQLite 文件。

群会话的 peerId/account 为 null，nickname 为群展示名，type=GROUP，ownerId 为群主 ID。成员变更与历史/回执检查共用会话锁，详情见 [群协议](groups.md)。

## 桌面历史展示分页

HTTP 历史补拉接口保持不变。桌面默认显示最近 200 条已缓存消息，“加载更早消息”按当前最小 seq 向前查询至多 50 条，在会话和成员周期内保留已展开范围。该展示操作独立于 HTTP 补拉游标、RECEIVED_ACK 与 READ，不创建新网络命令或修改服务端进度。

收到新消息或 READ_UPDATE 后刷新已展开范围，消息稳定 key 保持阅读位置；切换会话/账号/成员周期或显式返回最新时重置。未同步到本地的历史仍通过已有的正向分页补齐，不把本地暂时无更早数据解释成服务端没有历史。

## 附件扩展

SEND 可使用 attachmentId 代替 text，响应与历史消息增加可空的 attachment；IMAGE/FILE 复用本协议的消息序号、幂等、接收和已读语义。具体上传、私有下载和重试规则见 [附件契约](attachments.md)。附件 UUID 不属于正数 Long ID。
