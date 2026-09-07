# 一对一语音通话

`local` 的系统阶段为 `voice-calls`。桌面使用 Kotlin / Compose 与原生 WebRTC；后端继续采用 Java 21、Spring Boot 3 和常规 Service / Mapper 分层。Netty 承担认证与通话控制，RabbitMQ 唤醒在线设备，WebRTC 传音频，coturn 在直连不可用时中继。RustFS 不接收实时音频，也没有录音功能。

## 状态与设备归属

`RINGING → CONNECTING → ACTIVE → ENDED`，前三个状态均可结束。

- 发起时验证有效单聊和成员周期，按用户 ID 顺序锁定双方用户，任意一方占线时返回 `CALL_BUSY`。每位主叫每分钟最多创建 10 次呼叫。
- 主叫绑定当前登录 session；被叫所有在线设备可看到来电，首个成功接听的 session 获得通话归属。其他设备随后同步到空状态，不能操作已接听的通话。
- 响铃 45 秒未接听，或接听后 45 秒未双方连接成功，结束为 TIMEOUT。双方都报告 CONNECTED 后进入 ACTIVE。
- ACTIVE 使用双方最近同步时间中较早者加 30 秒作为租约；一端退出或断网不会被另一端续活。服务端每 2 秒处理过期记录，客户端断连即释放本机媒体；重新登录不恢复旧媒体协商。
- END 在响铃期间分别表示主叫取消 CANCELLED、被叫拒接 REJECTED；其他状态表示 HANGUP。ENDED 是终态，晚到的接听或信令不会重新激活通话。

## Netty 命令

所有命令复用已认证的 `/im` 连接，外层包含 `v:1`、`type:"CALL"`、`requestId`、`action`。`requestId` 仅匹配响应，幂等使用固定的 `callId` / `signalId`。

| action | 额外字段 | 行为 |
| --- | --- | --- |
| CREATE | callId（小写 UUID）、conversationId（十进制字符串）、membershipEpoch | 创建或找回同编号呼叫；同编号归属不一致返回 CALL_CONFLICT |
| SYNC | 可选 callId、after（非负整数，默认 0） | 不带 callId 时查当前通话；返回当前状态和对端 ID 大于 after 的最多 64 条信令，并续当前设备租约 |
| ACCEPT | callId | 被叫接听；同设备重试幂等，其他设备返回 CALL_ANSWERED_ELSEWHERE |
| SIGNAL | callId、signalId（小写 UUID）、kind、payload（字符串） | 保存 OFFER / ANSWER / ICE；同编号同内容重试幂等，不同内容返回 SIGNAL_CONFLICT |
| CONNECTED | callId | 报告本机 WebRTC 连接成功，可重试；双方确认才进入 ACTIVE |
| END | callId | 取消、拒接或挂断；重复结束返回既有终态 |

成功响应为 `{"v":1,"type":"CALL_ACK","requestId":"…","snapshot":{"call":{…},"signals":[]}}`。无当前通话时 call 为 null。call 字段包括 id、conversationId、callerId、calleeId、callerSession、calleeSession、state、reason、expiresAt（UTC ISO 时间）；用户/会话 ID 为字符串。signals 的每项包含 id（数据库自增整数）、signalId、kind、payload。同步游标只在信令成功交给媒体引擎后推进；非 SYNC 响应也可能携带从 0 开始的信令，客户端必须按 id 去重。

一通电话最多 256 条信令，单条 payload UTF-8 最多 6144 字节，Netty 入站帧仍为 16 KiB。主叫只发 OFFER，被叫只发 ANSWER，每种 SDP 一次；当前不支持 ICE restart 或重新协商。信令只允许绑定设备读取和写入，结束时删除；call_session 保留状态元数据，不包含音频。

状态提交后，通过 Redis 在线路由将 `CALL_CHANGED` 提示交给 RabbitMQ 当前网关队列，再由 Netty 唤醒客户端。提示不含 SDP，也不直接触发响铃；客户端重新 SYNC 后展示状态。提示丢失由每 2 秒 SYNC 补齐，避免积压的旧邀请重新响铃。音频和高频媒体包不进入 MQ。

## TURN 凭证

认证 `GET /api/calls/config` 返回 `{"iceServers":[{"urls":["turn:…"],"username":"…","credential":"…"}]}`。服务端读取 `KOKO_TURN_URLS` 与 `KOKO_TURN_SECRET`，使用 TURN REST 的限时 HMAC 凭证；用户名包含一小时后的到期秒数及当前用户 ID。客户端拿不到共享密钥。未配置 URL 时返回空列表，仅尝试直连；配置 URL 而无密钥时返回 503 TURN_UNAVAILABLE。

开发环境的 TURN 配置见 [部署说明](../deploy/README.md)。生产入口需使用 HTTPS/WSS，TURN 应部署在客户端可访问的地址并按网络条件配置 UDP/TCP/TLS 和防火墙；本仓库的回环地址模板仅用于本机测试。

## 桌面操作与边界

打开单聊，点击“语音通话”；被叫选择接听或拒绝。连接后可静音、取消静音和挂断。媒体引擎在对方接听后才创建，释放媒体先解除发送轨道再关闭原生连接，防止底层引用未释放。退出账号和连接失效停止本机采集。

当前实现语音单聊；没有视频画面、群通话、屏幕共享、通话历史页面、铃声或系统来电通知。摄像头仅做过原生设备枚举验证。真实麦克风/扬声器权限、双机人声质量、公网 NAT 场景和其他操作系统仍需设备验收，详见 [本轮验证](../docs/voice-verification.md)。
