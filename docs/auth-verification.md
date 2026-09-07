# 账号认证阶段验收记录

日期：2026-09-08。操作目录为 koko-chat，旧 IM 项目未修改。保持 Java 21 + Spring Boot 3.5.16 + Netty，采用普通 Controller / Handler → Service → Mapper 分层；客户端使用 Kotlin + Compose Desktop。

## 已验证结果

| 检查 | 结果与边界 |
|---|---|
| MySQL 8.4、Redis 7.4、RabbitMQ 4.2 | Compose 三个服务均健康；本机 MySQL 映射到 3307，避开已有训练数据库 |
| 数据库 | V1/V2 迁移、重复迁移、唯一键/外键/状态约束、事务回滚均通过；独立测试库已清理 |
| 后端 | 10 项原有测试 + 3 项认证集成测试 + 1 项独立数据库测试，分批通过 |
| Kotlin | 4 项基础测试 + 1 项页面模型测试 + 5 项会话测试 + 1 项渲染检查 + 2 项实时联调，分批通过 |
| 双客户端 | 两个独立 CIO 客户端并发注册与登录，经 HTTP 获取 Redis 票据后认证 Netty；保持在线 35 秒后退出 |
| 认证一致性 | 错误密码拒绝；并发刷新只有一次成功；同设备替换；重复/过期/伪造票据拒绝；注销后关闭旧连接 |
| 客户端生命周期 | 连接超时退避重试；刷新结果不明要求重登；退出关闭连接与清除身份；旧回调不能恢复已退出账号 |
| 界面 | 实际 Compose 组件离屏渲染，检查常规和最小窗口；修复小窗口注册按钮超出可视区域 |
| 构建产物 | Java 后端 JAR、携带 Java 21 的 macOS .app 和 DMG 均构建成功 |

验证入口分别为 `scripts/verify-database.sh`、`scripts/verify-auth.sh`、`scripts/verify-desktop-auth.sh`。后两个真实链路测试只有显式启用时运行；普通离线测试默认跳过依赖本机服务的用例。离屏界面检查通过 `KOKO_CHAT_RENDER_DIR=/tmp/koko-preview ./gradlew test --tests '*DesktopRenderTest' --rerun-tasks` 执行。

真实双客户端测试发现并修复了首次登录的 MySQL RR 间隙锁死锁：认证事务改为 RC，保留用户行锁及活动设备唯一约束，并加入不同新用户同时登录的回归测试。未修改消息事务的隔离配置。

临时后端进程及本次实时测试创建的随机账号已清理；开发中间件保留运行，便于继续实现聊天业务。`.env` 使用随机开发密码，未纳入 Git。

## 下一阶段

实现单聊最小闭环：创建会话 → SEND → MySQL 消息与 Outbox 同事务保存 → RabbitMQ 分发 → Netty 推送 → 接收确认 → 断线补拉。当前 RabbitMQ 仅完成基础配置与连通性验证，尚无业务消费者，也未实现 Redis 跨节点在线路由、联系人、群聊或离线同步。

当前验证不包含真实系统窗口的键盘/焦点/关闭交互、签名公证、Windows/Linux 安装包或生产高可用能力。接口及安全边界以 [认证契约](../contracts/auth.md) 为准。
