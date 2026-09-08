# 一对一音视频通话

`local` 的系统阶段为 `voice-calls`。桌面使用 Kotlin / Compose 与原生 WebRTC；后端继续采用 Java 21、Spring Boot 3 和常规 Service / Mapper 分层。Netty 承担认证与通话控制，RabbitMQ 唤醒在线设备，WebRTC 传音视频，coturn 在直连不可用时中继。RustFS 不接收实时音视频，也没有录音录像功能。

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
| CREATE | callId（小写 UUID）、conversationId（十进制字符串）、membershipEpoch、mediaType（AUDIO/VIDEO，省略默认 AUDIO） | 创建或找回同编号呼叫；同编号归属不一致返回 CALL_CONFLICT |
| SYNC | 可选 callId、after（非负整数，默认 0） | 不带 callId 时查当前通话；返回当前状态和对端 ID 大于 after 的最多 64 条信令，并续当前设备租约 |
| ACCEPT | callId | 被叫接听；同设备重试幂等，其他设备返回 CALL_ANSWERED_ELSEWHERE |
| SIGNAL | callId、signalId（小写 UUID）、kind、payload（字符串） | 保存 OFFER / ANSWER / ICE；同编号同内容重试幂等，不同内容返回 SIGNAL_CONFLICT |
| MEDIA | callId、cameraEnabled（布尔值） | 已绑定设备上报自身摄像头状态；仅 VIDEO 接听后允许，重复上报幂等 |
| CONNECTED | callId | 报告本机 WebRTC 连接成功，可重试；双方确认才进入 ACTIVE |
| END | callId | 取消、拒接或挂断；重复结束返回既有终态 |

成功响应为 `{"v":1,"type":"CALL_ACK","requestId":"…","snapshot":{"call":{…},"signals":[]}}`。无当前通话时 call 为 null。call 字段包括 id、conversationId、callerId、calleeId、callerSession、calleeSession、state、reason、expiresAt（UTC ISO 时间）、mediaType、callerCamera、calleeCamera；用户/会话 ID 为字符串。signals 的每项包含 id（数据库自增整数）、signalId、kind、payload。同步游标只在信令成功交给媒体引擎后推进；非 SYNC 响应也可能携带从 0 开始的信令，客户端必须按 id 去重。

一通电话最多 256 条信令，单条 payload UTF-8 最多 12 KiB，Netty 入站单帧和完整聚合消息最多 32 KiB。快照每次最多 64 条且 payload 原始字节合计不超过 12 KiB，客户端按 id 继续补拉；Ktor 收包限制为 128 KiB，留出 JSON 转义和元数据空间。主叫只发 OFFER，被叫只发 ANSWER，每种 SDP 一次；当前不支持 ICE restart 或重新协商。信令只允许绑定设备读取和写入，结束时删除；call_session 保留状态元数据，不包含媒体帧。

状态提交后，通过 Redis 在线路由将 `CALL_CHANGED` 提示交给 RabbitMQ 当前网关队列，再由 Netty 唤醒客户端。提示不含 SDP，也不直接触发响铃；客户端重新 SYNC 后展示状态。提示丢失由每 2 秒 SYNC 补齐，避免积压的旧邀请重新响铃。音视频和高频媒体包不进入 MQ。

## TURN 凭证

认证 `GET /api/calls/config` 返回 `{"iceServers":[{"urls":["turn:…"],"username":"…","credential":"…"}]}`。服务端读取 `KOKO_TURN_URLS` 与 `KOKO_TURN_SECRET`，使用 TURN REST 的限时 HMAC 凭证；用户名包含一小时后的到期秒数及当前用户 ID。客户端拿不到共享密钥。未配置 URL 时返回空列表，仅尝试直连；配置 URL 而无密钥时返回 503 TURN_UNAVAILABLE。

开发环境的 TURN 配置见 [部署说明](../deploy/README.md)。生产入口需使用 HTTPS/WSS，TURN 应部署在客户端可访问的地址并按网络条件配置 UDP/TCP/TLS 和防火墙；本仓库的回环地址模板仅用于本机测试。

## 桌面操作与边界

单聊标题栏分别提供“语音通话”和“视频通话”。视频来电明确显示类型，可选择“开启摄像头接听”或“仅语音接听”；主叫等待接听时不采集摄像头，接听后才创建媒体引擎。视频通话可静音、开关和切换摄像头、挂断；仅语音接听者随后可以主动开启摄像头。语音通话不会自动升级为视频。

视频轨道首次协商即存在，摄像头通过自定义视频源切换，开关与换设备不增加 OFFER/ANSWER，不触发重复协商。MEDIA 独立更新呼叫行，不占用有限信令邮箱；其状态是客户端实际采集状态的上报，不是服务器对物理设备的检测。关闭会停止采集并清空本机预览，对端同步状态后显示占位；结束通话清除两端摄像头标记。

视频面板显示对方画面与本机镜像预览，处理旋转；按 15fps、640×480 以内选择摄像头能力，实际采集取决于驱动。渲染每方向最多约 15fps、最长尺寸限制在 640×480（旋转后可能互换）；超过 1080p 像素数的输入不渲染。最新像素通过独立 StateFlow 传递，不把每帧塞入控制事件队列，也不写 SQLite/MySQL/RustFS。等待画面或停止收到画面时显示占位，不将旧图一直显示为实时视频。

摄像头缺失、启动异常或连续 5 秒没有采集帧时停止视频，语音继续；可检查权限后重新开启。麦克风/原生通话引擎初始化失败则结束通话。退出账号、认证连接失效和挂断释放本机采集、原生轨道、连接与画面。

当前支持一对一语音和视频；没有群通话、屏幕共享、录制、通话历史页面、铃声或系统来电通知。系统阶段字符串沿用 voice-calls，实际呼叫类型以 mediaType 判断。旧客户端可继续发起省略 mediaType 的语音呼叫，视频功能需双方升级客户端与后端。

真实摄像头/麦克风权限、物理双机、公网 NAT、Windows/Linux 仍需设备验收；自动化只使用合成媒体，验证范围见 [视频验收](../docs/video-verification.md)。
