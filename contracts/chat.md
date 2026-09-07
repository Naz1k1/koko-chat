# 文本单聊协议（已实现）

适用于 `local` 配置，当前系统信息 `stage=contacts`（包含上一阶段单聊能力）。HTTP 使用访问令牌；WebSocket 先按 [认证契约](auth.md) 取得 `AUTH_OK`。所有 ID、seq 均为十进制字符串，客户端按整数比较；当前范围为 Java 正数 Long。时间为 UTC ISO-8601。

## 会话与补拉

| 请求 | 参数 / 行为 |
| --- | --- |
| `POST /api/conversations/direct` | JSON `{"account":"bob"}`，按准确账号查找；禁止自己。双方的数值 ID 排序生成唯一键，重复请求返回同一会话，成功 HTTP 200。当前无需好友关系。 |
| `GET /api/conversations` | `afterId=0&limit=50`；limit 1–100，按会话 ID 升序返回。只包含当前有效单聊。 |
| `GET /api/conversations/{id}/messages` | `afterSeq=0&toSeq=20&limit=50`；limit 1–100，返回 `(afterSeq,toSeq]` 可见消息，按 seq 升序。首次可省略 toSeq，后续沿用响应上界。 |

会话对象：

```json
{"id":"10","peerId":"2","account":"bob","nickname":"小波","membershipEpoch":"b6a403c9-334c-4f7b-af1c-019bcd8ca15c","visibleFromSeq":"1","latestSeq":"20"}
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

服务端返回 `{"v":1,"type":"RECEIVED_ACK_OK","requestId":"rpc-2","serverTime":"..."}`，设备游标以 GREATEST 单调更新，不能超过服务端 latestSeq。READ 尚未实现。接收方离线时，历史消息仍在 MySQL，不依赖 Redis Pub/Sub 或网关临时队列保存；重新登录和周期补拉使用同一历史接口。

## 错误与客户端行为

错误沿用 `v/type=ERROR/requestId?/serverTime/code/message`。HTTP 错误使用相同业务 code/message（HTTP 结构见认证契约）。

| code | 行为 |
| --- | --- |
| UNAUTHENTICATED | 身份无效，重新登录或重新取得票据 |
| USER_NOT_FOUND / SELF_CHAT | 检查对方账号 |
| INVALID_MESSAGE | 修正参数，不自动生成新编号重发原错误 |
| NOT_A_MEMBER / MEMBERSHIP_CHANGED | 禁止旧成员周期继续操作，重新同步 |
| IDEMPOTENCY_CONFLICT | 停止自动重试，原编号不能绑定新内容 |
| BUSY / OUTBOX_FULL / SERVICE_UNAVAILABLE | 稍后使用同一 clientMsgId 重试 |

客户端目前每 2 秒检查到期待发送记录、每 10 秒补拉；请求超时 10 秒。失败待发送记录可手动重试，但不会篡改原内容或原成员周期。登录账号切换时取消旧任务、关闭旧库，再打开该服务和用户的独立 SQLite 文件。
