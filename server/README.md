# koko-chat 后端

Java 21、Spring Boot 3.5.16、Netty、MyBatis starter 3.0.5。按功能分包，采用 Controller / Handler → Service → Mapper；已实现系统探针、注册登录、令牌会话与 Netty 票据认证、单聊持久化、历史分页、设备回执及 RabbitMQ 两级分发，以及好友申请/接受/拒绝与双向联系人关系。群管理支持创建、邀请好友、移除、退出和解散，消息通过统一会话与 MQ 链路分发。已读进度复用 conversation_member.last_read_seq，按用户/会话/成员周期共享，未读数排除自己发送的消息。

## 构建与默认启动

在 `server/` 目录执行，系统需有 JDK 21；Maven Wrapper 3.3.4 首次运行会下载固定的 Maven 3.9.14，并验证发行包 SHA-256。

```bash
./mvnw verify
./mvnw spring-boot:run
```

也可执行 `java -jar target/koko-chat-server-0.1.0-SNAPSHOT.jar`。默认 `skeleton` profile 不创建 MySQL、Redis、RabbitMQ 客户端，因此无需启动中间件。

- `GET http://127.0.0.1:8080/api/system/info`：`{name, version, stage, httpPort, imPort, imPath}`，`stage` 在默认模式为 `skeleton`，`local` 为 `attachments`。
- `GET http://127.0.0.1:8080/actuator/health`：进程及 Netty 正常时返回 `{"status":"UP"}`。
- `ws://127.0.0.1:8081/im`：WebSocket 握手、控制帧 PING/PONG、JSON v1 应用心跳。TLS 由后续部署入口终止，本地骨架使用 HTTP / WS。

请求：`{"v":1,"type":"PING","requestId":"probe-1"}`。

响应：`{"v":1,"type":"PONG","requestId":"probe-1","serverTime":"UTC ISO-8601"}`。

默认骨架模式的 `AUTH` 返回 `NOT_IMPLEMENTED`。`local` 模式使用一次性 Redis 票据，成功返回 `AUTH_OK`；未认证的消息命令返回 `UNAUTHENTICATED`，认证后支持 `SEND` / `SEND_ACK`、`MESSAGE` 和 `RECEIVED_ACK`，以及 `READ` / `READ_ACK` / `READ_UPDATE`。单聊接口见 [聊天契约](../contracts/chat.md)。错误响应字段为 `v/type/requestId?/serverTime/code/message`。

单帧和完整聚合消息均限制为 16 KiB；只接收 JSON 文本。握手后未认证连接在 30 秒关闭，PING 不延长认证期限；读空闲 75 秒关闭。Netty EventLoop 仅做轻量协议解析；票据验证和会话有效性查询通过有界 `imBusinessExecutor` 执行，过载关闭 1013，防止阻塞 Netty 事件循环。

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

`local` 启用数据源、Redis、RabbitMQ 和 Flyway；首次迁移创建账号、好友、会话、消息、游标与 Outbox 共 9 张业务表，详情见 [数据库文件与迁移说明](../docs/database.md)。认证 Mapper 已接入账号与登录会话，V2 增加访问令牌摘要，V3 增加群操作去重表，V4 增加附件元数据及消息引用，当前共 11 张业务表。RabbitMQ 已声明持久分发、重试、死信与临时网关队列，配置 correlated confirms、returns、mandatory 和 manual ACK。Actuator health 会反映 local 中间件连接状态；健康探针不代表 MQ 拓扑或聊天业务已经就绪。

版本依据：[Spring Boot 3.5 官方要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis 官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 验证范围

`./mvnw verify` 包含 10 项常规自动测试：真实随机端口 HTTP/Actuator 与 WebSocket 探针、应用/控制帧心跳、未实现认证与未认证 SEND 拒绝、非法 JSON/版本、分片聚合及大小限制、二进制拒绝、错误握手路径、未认证连接期限、关闭连接并释放端口、端口占用导致 Spring 启动失败且不残留 EventLoop 线程。另有 MySqlSchemaTest，只有显式提供测试连接时才运行，默认跳过；配置方法见数据库说明。

认证阶段新增真实中间件测试：根目录 `./scripts/verify-auth.sh` 验证账号注册、密码拒绝、并发刷新、不同新用户并发登录、同设备替换、双客户端票据认证、重放/过期/伪造票据拒绝和注销关闭连接。`./scripts/verify-database.sh` 验证 MySQL 迁移与约束。详细接口见 [认证契约](../contracts/auth.md)。

MySQL、Redis、RabbitMQ 的开发实例已通过真实健康检查；健康检查只表示运行状态；消息持久化、MQ 推送、群成员边界和离线同步由集成测试另行验证，尚无多节点压测结论。

好友 HTTP 端点、重试语义和权限规则见 [联系人契约](../contracts/contacts.md)。好友关系写入复用 V1 表，未修改已应用的迁移。

群管理协议见 [群聊契约](../contracts/groups.md)。V3 新增 group_command，与群操作同事务写入，保证成功操作的重试不会重复产生副作用。

## RustFS 附件

`attachment` 包采用 Controller → Service → Mapper 常规分层，`RustFsStorage` 通过 AWS SDK v2 连接私有桶。上传校验长度、摘要与图片格式；MySQL 附件绑定和消息/Outbox 同事务；下载重新校验当前成员及可见序号。HTTP 转传文件字节，Netty 和 RabbitMQ 仍负责消息引用。参数和错误码见 [附件契约](../contracts/attachments.md)，环境和物理存储位置见 [部署说明](../deploy/README.md)。

真实附件测试包含在 `verify-auth.sh`，需要 RustFS 启动且 `.env` 有对应凭证。`AttachmentTestCleanup` 仅供桌面联调脚本定向清理本次随机测试账号的对象，不是运行时接口或通用垃圾回收器。
