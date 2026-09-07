# koko-chat

桌面即时通信项目。客户端采用 **Kotlin + Compose Desktop**；服务端采用 **Java 21 + Spring Boot 3.5.x + Netty + RabbitMQ + MySQL + Redis**，按 Controller / Handler → Service → Mapper 常规分层组织。

当前阶段为工程骨架。后端提供系统信息、健康检查和 Netty WebSocket 心跳入口；桌面端提供中文基础窗口、服务检查与 SQLite 设置保存。登录、消息持久化、群聊、MQ 分发和离线同步按架构文档逐步实现。桌面端采用 MVVM + StateFlow、Ktor HTTPS/WSS、SQLDelight + SQLite，两端统一使用 JDK 21。

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

后端配置、协议探针和测试见 [后端说明](server/README.md)。需要连接 MySQL、Redis、RabbitMQ 时，按 [本机中间件说明](deploy/README.md) 启动服务并启用 `local` 配置。

## 桌面端启动

在仓库根目录另开终端，使用有效的 JDK 21 与工程自带的 Gradle Wrapper：

```bash
cd apps/desktop
./gradlew test
./gradlew run
```

桌面端默认检查 `http://127.0.0.1:8080`，可在服务设置中修改地址并保存到本机 SQLite。界面会区分 HTTP 可达与 IM 登录状态；当前尚未实现登录和聊天连接。

生成携带 Java 运行时的桌面应用：

```bash
./gradlew createDistributable
./gradlew runDistributable
# 在 macOS 上生成 DMG
./gradlew packageDmg
```

版本、数据目录和平台要求见 [桌面端说明](apps/desktop/README.md)。首次构建需要联网下载依赖；Windows 使用对应的 `mvnw.cmd` / `gradlew.bat`。

## 设计文档

- [当前骨架协议与 JSON Schema](contracts/README.md)
- [架构设计、技术选型与实施顺序](docs/architecture.md)
- [Kotlin 桌面端：模块、状态、同步与打包](docs/desktop.md)
- [RabbitMQ 设计：队列拓扑、确认、重试与死信](docs/messaging.md)
- [首期架构图](docs/diagrams/koko-chat-architecture.png)
- [多节点扩展图](docs/diagrams/koko-chat-scale.png)
- [可编辑 draw.io 源文件（两页）](docs/diagrams/koko-chat-architecture.drawio)

旧 IM 项目只作为功能与实现经验参考；新文档、代码和配置均在本仓库维护。

RabbitMQ 从首期承担异步分发、群消息扇出和节点定向投递；消息与 outbox 同事务保存，服务端确认、MQ 确认、设备接收和用户已读分别定义。
