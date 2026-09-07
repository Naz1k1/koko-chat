# RustFS 附件与图片消息

`local` 配置已实现，系统阶段为 `attachments`。附件字节经认证 HTTP 在桌面端、后端和 RustFS 之间传输；Netty / RabbitMQ 只传消息引用。开发限制为单文件 1 字节至 10 MiB，每个后端实例最多同时处理 4 个上传/下载。IMAGE 仅接受可解码的 PNG/JPEG，最多 4,194,304 像素；FILE 按 `application/octet-stream` 保存。

## 登记与上传

以下 HTTP 请求都必须携带 `Authorization: Bearer <accessToken>`，响应设置 `Cache-Control: no-store`。

1. `POST /api/conversations/{conversationId}/attachments`，JSON 正文：

```json
{
  "clientUploadId": "7df5f5d7-cb5e-49f7-8ba9-54228d88fd18",
  "membershipEpoch": "当前成员周期 UUID",
  "name": "项目说明.pdf",
  "size": 32768,
  "sha256": "文件字节的64位小写十六进制SHA-256",
  "kind": "FILE"
}
```

`clientUploadId` 为客户端生成的小写 UUID。文件名最长 180 个字符，禁止控制字符、`/` 和 `\`。编号相同且全部元数据相同的登记返回当前状态；内容或所属用户、会话、成员周期不同返回 409 `UPLOAD_CONFLICT`。附件大小为 JSON 数字，用户/会话/消息 ID 和 seq 仍是十进制字符串。

响应形状为 `{ "attachment": { "id", "name", "size", "sha256", "kind", "contentType" }, "status": "PENDING" }`。IMAGE 在上传校验之前的 contentType 也是 `application/octet-stream`。

2. `PUT /api/attachments/{id}/content`，`Content-Type: application/octet-stream`，正文为原始字节。后端先验证本人、会话和周期，再读取有限大小的正文，验证长度、SHA-256 和图片类型，将对象写入 RustFS，最后更新元数据为 READY。相同内容再次 PUT 返回当前状态，READY/ATTACHED 时不重复写对象。客户端可先重复登记，若已 READY/ATTACHED，直接恢复 SEND。

状态变化：`PENDING → READY → ATTACHED`。RustFS 写入不持有数据库事务，也不与 MySQL 构成分布式事务；写入失败保留 PENDING，写入成功但确认失败则按原编号、原对象键覆盖重试。上传后权限再次校验失败会保留可追踪的记录，不会成为可发送附件。

## 发送消息

```json
{
  "v": 1,
  "type": "SEND",
  "requestId": "本次请求编号",
  "conversationId": "123",
  "membershipEpoch": "当前成员周期 UUID",
  "clientMsgId": "7df5f5d7-cb5e-49f7-8ba9-54228d88fd18",
  "attachmentId": "7df5f5d7-cb5e-49f7-8ba9-54228d88fd18"
}
```

有 attachmentId 时不能同时发送非空 text。附件必须属于本人、当前会话及成员周期，并且已经上传。服务端从元数据生成 IMAGE/FILE 消息和 `[图片] 文件名` / `[文件] 文件名` 摘要，客户端不能指定任意对象路径。

附件绑定、消息、会话序号和 Outbox 在同一 MySQL 事务中提交。重复 clientMsgId 返回同一 SEND_ACK；把同一附件用于第二条消息返回 409 `ATTACHMENT_USED`，不会消耗会话序号。摘要的多字段 JSON 按键排序，使 JVM 重启后的幂等校验保持一致。

SEND_ACK、MESSAGE 和历史列表的 message 增加可空的 `attachment` 引用，字段与上传响应相同。文字消息 attachment 为 null。MQ 事件仍引用 messageId，不传文件字节；已读、未读与离线补拉复用现有聊天链路。

## 下载与权限

`GET /api/attachments/{id}/content` 返回字节、Content-Length、服务端识别的 Content-Type、UTF-8 文件名的 `Content-Disposition: attachment` 及 `X-Content-Type-Options: nosniff`。不返回公开 S3 地址或预签名链接。

每次下载在请求开始时重新校验登录、会话状态、当前有效成员和绑定消息的 seq 是否在当前 join_seq 范围内。未发送附件不可下载；退群/移除/解散后被拒绝；重新入群也不能读取新可见起点以前的附件。已开始的传输不会因中途成员变化被强制中断，用户此前已保存到本机的文件也不会被远程删除。

桌面端流式读取并限制累计大小，验证 SHA-256 后才预览或保存；保存位置由系统对话框选择，临时文件完整写入后替换目标，不自动执行文件。选择发送时先复制到账号专属目录，SQLite 记录元数据和发送意图；确认本机自己的消息成功落盘后才删除副本。永久失败保留副本与失败意图，重试不会改变成员周期。

## 错误与当前边界

| HTTP / 业务码 | 含义 |
| --- | --- |
| 400 INVALID_ATTACHMENT / INVALID_IMAGE / FILE_HASH_MISMATCH | 元数据、图片或摘要校验失败 |
| 401 UNAUTHENTICATED | 未登录或凭证失效 |
| 403 NOT_A_MEMBER / ATTACHMENT_NOT_SENT | 无当前访问权限或尚未发送 |
| 404 ATTACHMENT_NOT_FOUND | 附件不存在，或不是当前上传者的记录 |
| 409 UPLOAD_CONFLICT / MEMBERSHIP_CHANGED | 原编号元数据不一致或成员周期变化 |
| 409 ATTACHMENT_NOT_READY / ATTACHMENT_USED | 尚未上传、归属不符或重复绑定 |
| 413 FILE_SIZE_MISMATCH | 实际大小与声明不一致；过滤器拒绝超限请求时可只返回 HTTP 413 |
| 429 TRANSFER_BUSY / 503 STORAGE_UNAVAILABLE | 保留原编号稍后重试 |

本轮没有实现缩略图、分片续传、病毒扫描、存储配额、弃用附件的定时清理和远程撤回本地文件。PENDING/READY 元数据和无绑定对象目前保留，生产使用前需制定清理期限与配额；不能直接删除所有无绑定记录，因为上传与重试可能仍在执行。
