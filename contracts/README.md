# 当前协议

客户端与服务端各自定义 DTO，通过这里的 JSON Schema 和协议样例保持一致。当前文件用于开发和契约校验，尚未接入运行时 Schema 校验；服务端已有对应的基础字段、版本、帧大小检查。

| 接口 | 当前行为 |
| --- | --- |
| GET /api/system/info | 返回 name、version、stage、httpPort、imPort、imPath；见 [系统信息 Schema](system-info.schema.json) |
| GET /actuator/health | 使用 Spring Boot Actuator 标准格式，健康时为 `{"status":"UP"}` |
| WebSocket /im | JSON v1 心跳与 local 票据认证；探针子集见 [探针 Schema](im-probe.schema.json)，认证见 [认证契约](auth.md) |
| /api/conversations/*、SEND、RECEIVED_ACK、READ | 单聊创建、列表、文字/附件发送、历史补拉及已读/未读状态；见 [单聊契约](chat.md) |
| /api/conversations/{id}/attachments、/api/attachments/{id}/content | RustFS 附件登记、上传与认证下载；见 [附件契约](attachments.md) |
| CALL / CALL_ACK / CALL_CHANGED、GET /api/calls/config | 一对一音视频、摄像头状态、可补拉信令与限时 TURN 凭证；见 [音视频契约](voice-calls.md) |
| /api/groups/* | 建群、邀请、移除、退出与解散；见 [群聊契约](groups.md) |
| /api/friend-requests/*、GET /api/friends | 好友申请、接受/拒绝与联系人；见 [联系人契约](contacts.md) |
| /internal/ops/* | 独立运维凭据：监控、指标、死信查询、重放和审计；见 [运维契约](operations.md) |
| /api/auth/*、POST /api/im/tickets | 注册、登录、刷新、注销和一次性票据；见 [认证契约](auth.md) |

应用级心跳样例：

```json
{"v":1,"type":"PING","requestId":"probe-1"}
```

```json
{"v":1,"type":"PONG","requestId":"probe-1","serverTime":"2026-09-07T12:00:00Z"}
```

心跳不携带登录身份，不能获得业务权限。默认 skeleton 对 AUTH 返回 NOT_IMPLEMENTED；local 可返回 AUTH_OK。未认证消息命令返回 UNAUTHENTICATED，认证后支持 SEND_ACK 与设备接收回执；READ 返回 READ_ACK，已读变化通过 READ_UPDATE 提示。requestId 用于匹配请求和响应，不是消息幂等 ID；无法解析请求时错误响应可以省略 requestId。

认证、好友、文本单聊、群聊、已读、RustFS 附件（含缩略图和过期清理）及音视频契约已实现；其余能力按 [整体架构](../docs/architecture.md) 逐步补充；不将文档中的目标协议当成当前已实现能力。
