# koko-chat 后端骨架

Java 21、Spring Boot 3.5.16、Netty、MyBatis starter 3.0.5。按功能分包，采用 Controller / Handler → Service → Mapper；目前只有系统探针和连接层，没有登录或聊天业务。

## 构建与默认启动

在 `server/` 目录执行，系统需有 JDK 21；Maven Wrapper 3.3.4 首次运行会下载固定的 Maven 3.9.14，并验证发行包 SHA-256。

```bash
./mvnw verify
./mvnw spring-boot:run
```

也可执行 `java -jar target/koko-chat-server-0.1.0-SNAPSHOT.jar`。默认 `skeleton` profile 不创建 MySQL、Redis、RabbitMQ 客户端，因此无需启动中间件。

- `GET http://127.0.0.1:8080/api/system/info`：`{name, version, stage, httpPort, imPort, imPath}`，当前 `stage` 为 `skeleton`。
- `GET http://127.0.0.1:8080/actuator/health`：进程及 Netty 正常时返回 `{"status":"UP"}`。
- `ws://127.0.0.1:8081/im`：WebSocket 握手、控制帧 PING/PONG、JSON v1 应用心跳。TLS 由后续部署入口终止，本地骨架使用 HTTP / WS。

请求：`{"v":1,"type":"PING","requestId":"probe-1"}`。

响应：`{"v":1,"type":"PONG","requestId":"probe-1","serverTime":"UTC ISO-8601"}`。

`AUTH` 返回 `ERROR / NOT_IMPLEMENTED`；`SEND`、`RECEIVED_ACK`、`READ` 返回 `ERROR / UNAUTHENTICATED`。没有 `AUTH_OK` 或 `SEND_ACK`，不会把连接成功伪装成认证或消息保存成功。错误响应字段为 `v/type/requestId?/serverTime/code/message`。

单帧和完整聚合消息均限制为 16 KiB；只接收 JSON 文本。握手后未认证连接在 30 秒关闭，PING 不延长认证期限；读空闲 75 秒关闭。Netty EventLoop 仅做轻量协议解析；后续 JDBC/Redis/密码校验应提交到有界 `imBusinessExecutor`，过载拒绝由调用方转换为可重试错误。

## local profile 与中间件

先按仓库 `deploy/` 的说明启动基础设施，并把本机环境变量导出到当前 shell，再执行：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

| 变量 | 默认值 / 要求 |
| --- | --- |
| `HTTP_HOST` / `HTTP_PORT` | `127.0.0.1` / `8080` |
| `IM_HOST` / `IM_PORT` | `127.0.0.1` / `8081` |
| `MYSQL_HOST` / `MYSQL_PORT` | `127.0.0.1` / `3306` |
| `MYSQL_DATABASE` / `MYSQL_USERNAME` | `koko_chat` / `koko` |
| `MYSQL_PASSWORD` | 必须从环境变量提供 |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` |
| `REDIS_PASSWORD` | 必须从环境变量提供 |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `127.0.0.1` / `5672` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `koko` / 密码必须从环境变量提供 |

`local` 启用数据源、Redis、RabbitMQ 和 Flyway；首次迁移创建账号、好友、会话、消息、游标与 Outbox 共 9 张业务表，详情见 [数据库文件与迁移说明](../docs/database.md)。当前尚无业务 Mapper、队列声明或消费者。RabbitMQ 已配置 correlated confirms、returns、mandatory 和 manual ACK，为后续实现预留。Actuator health 会反映 local 中间件连接状态；健康探针不代表 MQ 拓扑或聊天业务已经就绪。

版本依据：[Spring Boot 3.5 官方要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis 官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 验证范围

`./mvnw verify` 包含 10 项常规自动测试：真实随机端口 HTTP/Actuator 与 WebSocket 探针、应用/控制帧心跳、未实现认证与未认证 SEND 拒绝、非法 JSON/版本、分片聚合及大小限制、二进制拒绝、错误握手路径、未认证连接期限、关闭连接并释放端口、端口占用导致 Spring 启动失败且不残留 EventLoop 线程。另有 MySqlSchemaTest，只有显式提供测试连接时才运行，默认跳过；配置方法见数据库说明。

这批测试已在 JDK 21 上通过，未连接外部中间件。`local` 的真实 MySQL/Redis/RabbitMQ 连通性及 MQ 拓扑、登录和聊天业务不在本次骨架验证范围内。
