# 认证接口 v1

仅 `local` 配置开启。部署到远程机器时，入口必须配置 HTTPS；本机开发使用回环地址 HTTP。所有认证响应 `Cache-Control: no-store`，错误体统一为 `{"code":"...","message":"中文提示"}`，不回显密码和令牌。

| 方法与路径 | 请求 | 成功响应 |
|---|---|---|
| POST /api/auth/register | account、password、nickname | 201，用户 id（字符串）、account、nickname |
| POST /api/auth/login | account、password、deviceId | 200，令牌对与用户信息 |
| POST /api/auth/refresh | refreshToken | 200，新令牌对，旧令牌对立即失效 |
| POST /api/auth/logout | refreshToken | 204，撤销登录会话；重复调用仍为 204 |
| GET /api/auth/me | Authorization: Bearer accessToken | 200，当前用户 |

账号为 3–32 位 ASCII 字母、数字或下划线，服务端统一转为小写；密码为 8–128 个字符；昵称非空，最多 64 字符；deviceId 为 1–64 位字母、数字、下划线或连字符，桌面端使用本地持久化 UUID。

令牌对字段：`accessToken`、`refreshToken`、`accessExpiresAt`、`refreshExpiresAt`、`sessionId`、`user`。令牌由 SecureRandom 生成 32 字节并编码为 43 位 Base64URL；数据库只保存 SHA-256 摘要。访问有效期 15 分钟，登录会话最长 7 天，刷新不延长此绝对期限。客户端只在内存中保存令牌。

密码使用带独立随机盐的 PBKDF2-HMAC-SHA256，由 Spring Security `Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()` 实现，存储带 `{pbkdf2-v1}` 算法标识。选择依据：[Spring Security 密码存储文档](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)。

同账号同设备重新登录时，事务内撤销旧会话并创建新会话；不同设备互不影响。数据库行锁、活动设备唯一约束保证并发操作一致。刷新令牌只能轮换一次；如果刷新成功但响应丢失，客户端要求重新登录，避免自动重放不明确的刷新请求。

校验失败 400；密码错误或令牌失效 401；账号重复 409；频率超限 429；存储暂不可用 503。注册/登录按账号每分钟 12 次、实际对端 IP 每分钟 60 次限制，Redis Lua 保证计数和过期原子执行。代理部署需要先配置可信代理，当前不采信客户端提供的转发地址。

验证：根目录 `./scripts/verify-auth.sh` 使用真实 MySQL、Redis 和 HTTP 服务，测试账号带随机前缀并在测试后定向清理。`./scripts/verify-database.sh` 在独立临时库验证 V1/V2 迁移和数据库约束。

## Netty 一次性票据认证

`POST /api/im/tickets` 携带访问令牌，返回 `ticket` 和 `expiresInSeconds:30`。票据在 Redis 仅保留 30 秒，键使用摘要；`GETDEL` 原子消费后不可重用。每个登录会话每分钟最多签发 30 张票据。

连接 `/im` 后发送 `{"v":1,"type":"AUTH","requestId":"...","ticket":"..."}`。成功响应 `AUTH_OK`，包含服务端认定的 `userId`、`deviceId`、`sessionId` 与 `serverTime`。伪造 userId 字段无效。匿名连接 30 秒未完成认证关闭 1008，PING 不延长期限。

已认证连接支持 `PING → PONG`。同一登录会话仅保留一个本机连接；注销/同设备替换在数据库提交后关闭旧连接。每 5 秒通过业务线程池检查登录有效性，覆盖其他节点注销与认证期间撤销的竞态；最坏延迟还包含数据库查询超时。数据库不可用或业务线程池过载关闭 1013，失效会话关闭 1008。

当前连接登记在本机内存中，尚未实现 Redis 跨节点在线路由、群聊、消息持久化或 RabbitMQ 分发。认证后的 SEND 仍返回 NOT_IMPLEMENTED，不发送虚假的保存成功确认。
