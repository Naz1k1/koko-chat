# 骨架阶段协议

客户端与服务端各自定义 DTO，通过这里的 JSON Schema 和协议样例保持一致。当前文件用于开发和契约校验，尚未接入运行时 Schema 校验；服务端已有对应的基础字段、版本、帧大小检查。

| 接口 | 当前行为 |
| --- | --- |
| GET /api/system/info | 返回 name、version、stage、httpPort、imPort、imPath；见 [系统信息 Schema](system-info.schema.json) |
| GET /actuator/health | 使用 Spring Boot Actuator 标准格式，健康时为 `{"status":"UP"}` |
| WebSocket /im | JSON v1 PING/PONG，以及未实现/未认证错误；见 [探针 Schema](im-probe.schema.json) |

应用级心跳样例：

```json
{"v":1,"type":"PING","requestId":"probe-1"}
```

```json
{"v":1,"type":"PONG","requestId":"probe-1","serverTime":"2026-09-07T12:00:00Z"}
```

心跳不携带登录身份，不能获得业务权限。骨架对 AUTH 返回 NOT_IMPLEMENTED，对 SEND、RECEIVED_ACK、READ 返回 UNAUTHENTICATED，不返回 AUTH_OK 或 SEND_ACK。requestId 用于匹配请求和响应，不是消息幂等 ID；无法解析请求时错误响应可以省略 requestId。

完整认证、聊天、回执和同步契约按 [整体架构](../docs/architecture.md) 在各业务步骤补充；不将文档中的目标协议当成当前已实现能力。
