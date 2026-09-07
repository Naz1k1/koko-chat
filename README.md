# koko-chat

桌面即时通信项目。客户端采用 **Kotlin + Compose Desktop**；服务端采用 **Java 21 + Spring Boot 3.5.x + Netty + RabbitMQ + MySQL + Redis**，按 Controller / Handler → Service → Mapper 常规分层组织。

技术选型已确定，当前交付架构基线，业务代码尚未实现。桌面端使用 MVVM + StateFlow、Ktor HTTPS/WSS、SQLDelight + SQLite，构建运行统一使用 JDK 21；后端保留 Netty，并以 RabbitMQ 承担异步分发。

- [架构设计、技术选型与实施顺序](docs/architecture.md)
- [Kotlin 桌面端：模块、状态、同步与打包](docs/desktop.md)
- [RabbitMQ 设计：队列拓扑、确认、重试与死信](docs/messaging.md)
- [首期架构图](docs/diagrams/koko-chat-architecture.png)
- [多节点扩展图](docs/diagrams/koko-chat-scale.png)
- [可编辑 draw.io 源文件（两页）](docs/diagrams/koko-chat-architecture.drawio)

旧 IM 项目只作为功能与实现经验参考；新文档、代码和配置均在本仓库维护。

RabbitMQ 从首期承担异步分发、群消息扇出和节点定向投递；消息与 outbox 同事务保存，服务端确认、MQ 确认、设备接收和用户已读分别定义。
