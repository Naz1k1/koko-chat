# koko-chat

桌面即时通信项目。客户端采用 **Kotlin + Compose Desktop**；服务端采用 **Java 21 + Spring Boot 3.5.x + Netty + RabbitMQ + MySQL + Redis + RustFS**，按 Controller / Handler → Service → Mapper 常规分层组织。

当前已完成账号认证、好友与文本单聊/群聊闭环：注册登录、令牌刷新、Netty 认证与心跳、好友申请与接受/拒绝、联系人列表、按准确账号建立单聊、建群与邀请/移除/退出/解散、消息与 Outbox 同事务保存、RabbitMQ 分发、设备接收回执、离线补拉，以及中文桌面聊天界面与账号独立的 SQLite 消息缓存。现已支持用户已读回执、会话未读计数、跨设备读进度同步、断线补报，以及历史消息向前分页和阅读位置保持；现已接入 RustFS 私有存储，支持图片/文件上传、消息引用、权限下载、桌面图片预览与断线重试。现已补齐私有缩略图、未发送附件过期回收及一对一语音通话（原生 WebRTC + 可选 coturn）。后端已增加受保护的监控指标、死信归档与审计重放，并验证双进程节点退出后的恢复。桌面端采用 MVVM + StateFlow、Ktor HTTPS/WSS、SQLDelight + SQLite，两端统一使用 JDK 21。

## 后端启动

安装 JDK 21，设置 JAVA_HOME，使用工程自带的 Maven Wrapper：

```bash
cd server
./mvnw verify
./mvnw spring-boot:run
```

默认骨架模式无需启动数据库或消息队列：

- HTTP 系统信息：`http://127.0.0.1:8080/api/system/info`
- 进程健康检查：`http://127.0.0.1:8080/actuator/health`
- Netty WebSocket：`ws://127.0.0.1:8081/im`

这里的明文地址用于本机开发；部署时配置 HTTPS/WSS 入口。心跳成功只证明传输可用，不能视为登录成功或消息已送达。

后端配置、协议探针和测试见 [后端说明](server/README.md)。需要连接 MySQL、Redis、RabbitMQ、RustFS 时，按 [本机中间件说明](deploy/README.md) 启动服务并启用 `local` 配置。

## 桌面端启动

在仓库根目录另开终端，使用有效的 JDK 21 与工程自带的 Gradle Wrapper：

```bash
cd apps/desktop
./gradlew test
./gradlew run
```

桌面端默认检查 `http://127.0.0.1:8080`，可在服务设置中修改地址并保存到本机 SQLite。界面区分 HTTP 可达、正在登录、IM 在线和重连状态；注册与登录需要后端启用 `local`。密码和令牌只保存在内存，退出或关闭应用时清理。

生成携带 Java 运行时的桌面应用：

```bash
./gradlew createDistributable
./gradlew runDistributable
# 在 macOS 上生成 DMG
./gradlew packageDmg
```

版本、数据目录和平台要求见 [桌面端说明](apps/desktop/README.md)。首次构建需要联网下载依赖；Windows 使用对应的 `mvnw.cmd` / `gradlew.bat`。

## 设计文档

- [当前协议与 JSON Schema](contracts/README.md)
- [认证 HTTP 接口与 Netty 票据契约](contracts/auth.md)
- [单聊、发送确认与历史同步契约](contracts/chat.md)
- [单聊阶段验收记录与界面](docs/chat-verification.md)
- [好友申请与联系人接口](contracts/contacts.md)
- [好友阶段验收记录与界面](docs/contact-verification.md)
- [群管理与成员周期协议](contracts/groups.md)
- [群聊阶段验收记录与界面](docs/group-verification.md)
- [已读回执、未读计数与多设备验收](docs/read-verification.md)
- [历史分页、阅读位置与批量消息验收](docs/history-verification.md)
- [RustFS 附件接口与权限契约](contracts/attachments.md)
- [附件阶段验收与界面](docs/attachment-verification.md)
- [语音通话协议](contracts/voice-calls.md)
- [语音、缩略图及过期清理验收](docs/voice-verification.md)
- [后台运维手册](docs/operations.md)
- [运维接口契约](contracts/operations.md)
- [监控、死信及多节点验收](docs/operations-verification.md)
- [数据库建表文件、字段与迁移方法](docs/database.md)
- [架构设计、技术选型与实施顺序](docs/architecture.md)
- [Kotlin 桌面端：模块、状态、同步与打包](docs/desktop.md)
- [RabbitMQ 设计：队列拓扑、确认、重试与死信](docs/messaging.md)
- [首期架构图](docs/diagrams/koko-chat-architecture.png)
- [多节点扩展图](docs/diagrams/koko-chat-scale.png)
- [可编辑 draw.io 源文件（两页）](docs/diagrams/koko-chat-architecture.drawio)

旧 IM 项目只作为功能与实现经验参考；新文档、代码和配置均在本仓库维护。

RabbitMQ 已参与单聊与群聊主链路：Outbox → 持久分发队列 → 当前网关队列 → Netty；发布使用 confirm + mandatory，失败经过 5/30/120 秒重试及死信处理。服务端保存确认、MQ 确认和设备接收分别定义，用户已读独立使用 READ / READ_ACK，在线变化经 RabbitMQ 提示，快照同步负责补齐。

## 本机认证、好友与聊天验收

先准备 `deploy/.env` 并启动中间件，再运行：

```bash
./scripts/verify-database.sh
./scripts/verify-auth.sh
./scripts/verify-desktop-auth.sh
```

第三个脚本使用已构建的后端 JAR，在 18080/18081 启动临时服务，验证双客户端认证、好友申请与拒绝/接受、联系人聊天、三客户端群聊和成员隔离、真实 MQ 推送、确认丢失重试与离线补拉、三客户端已读同步、READ_ACK 丢失重试和通知丢失后的快照恢复、325 条消息的历史分页、RustFS 附件上传响应丢失后的重试和接收方保存/预览，以及双客户端语音通话与确认丢失恢复，然后退出并定向清理本次账号、聊天数据和附件对象。原有服务端口被占用时会拒绝启动；可用 `KOKO_CHAT_VERIFY_HTTP_PORT` / `KOKO_CHAT_VERIFY_IM_PORT` 更换测试端口。
