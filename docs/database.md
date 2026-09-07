# 数据库文件与迁移说明

数据库采用 MySQL 8.4、InnoDB、utf8mb4，首个 Flyway 版本建立 9 张业务表。SQL 中已包含中文表说明、字段说明、索引及约束。本次落地的是表结构；认证、好友、消息 Service/Mapper 与 MQ 生产消费逻辑仍待实现。

## 文件入口

| 文件 | 用途 |
| --- | --- |
| [00-create-database.sql](../deploy/mysql/00-create-database.sql) | 在已有 MySQL 实例中创建 koko_chat 空库，不创建用户和密码 |
| [V1__create_chat_schema.sql](../server/src/main/resources/db/migration/V1__create_chat_schema.sql) | 唯一的业务建表来源，由 Flyway 执行并记录版本 |
| [MySqlSchemaTest.java](../server/src/test/java/dev/koko/chat/database/MySqlSchemaTest.java) | 显式启用的 MySQL 8.4 迁移和约束验证，使用独立临时库 |
| [Preferences.sq](../apps/desktop/src/main/sqldelight/dev/koko/chat/desktop/data/Preferences.sq) | 桌面 SQLite 设置表，由 SQLDelight 生成建表及查询代码 |

不提交运行时的 MySQL 数据目录或桌面 `.db` 文件。桌面消息缓存、同步游标与待发送队列将在客户端对应功能阶段添加；当前 SQLite 只存服务设置。

## 表与查询路径

| 表 | 数据职责 | 关键索引或约束 |
| --- | --- | --- |
| app_user | 账号、密码哈希、昵称、头像、状态 | account 唯一；用户 ID 为正数 |
| auth_session | 用户/设备登录会话、刷新凭证摘要、到期与撤销时间 | 刷新凭证摘要唯一；按用户设备检索、按到期时间清理 |
| friend_request | 好友申请及处理历史 | pending_pair 生成列保证每对用户最多一条待处理申请；收件箱/发件箱索引 |
| friendship | 两个方向的好友关系与各自备注 | user_id + friend_id 联合主键；禁止添加自己 |
| conversation | 单聊/群聊元信息及最新消息序号 | direct_key 唯一；群聊的 direct_key 为 NULL |
| conversation_member | 当前成员关系、角色、可见起点、成员周期、用户读进度 | 会话+用户联合主键；用户会话列表和群扇出索引 |
| message | 在线、离线共用的消息正文及原发送者周期 | sender_id + client_msg_id、conversation_id + seq 两组唯一约束 |
| device_cursor | 各设备在特定成员周期的连续接收进度镜像 | 用户+设备+会话+成员周期联合主键 |
| message_outbox | 与消息同事务提交的待发布事件 | 消息+事件类型唯一；待发布、过期租约、已发布清理索引 |

好友申请的唯一键只约束 PENDING 记录。申请被接受、拒绝或取消后，生成列变为 NULL，允许未来再次申请；相反方向的新申请也不能绕过待处理限制。群聊共享 message 正文，不建立独立群消息表或离线消息正文表。

## 字段约定与事务边界

- 业务 ID 为应用分配的正数 BIGINT，不依赖自增；范围与 Java Long 一致。JSON 中的 ID 和 seq 使用字符串，客户端按数值比较 seq。
- 登录会话、成员周期、事件和认领令牌采用 UUID；设备标识和 client_msg_id 最大 64 个 ASCII 字符。协议标识按二进制排序规则精确比较，账号按 utf8mb4_0900_ai_ci 不区分大小写和重音，应用须统一规范化账号。
- 密码使用带算法参数的安全密码哈希。refresh_token_hash 与 body_hash 使用 BINARY(32) 保存 SHA-256 原始摘要；刷新凭证应是高熵随机值。应用可用十六进制显示摘要，但不能把 64 字符十六进制文本直接当成 32 字节值。
- DATETIME(3) 统一保存 UTC。当前 JDBC URL 已设置连接时区；手工写入数据的连接也需使用 UTC。
- 外键只约束真实用户、会话、消息引用，采用默认限制删除的行为。message 的 sender_membership_epoch 不引用当前成员周期，确保用户离群后重新加入不会破坏旧消息和旧 ACK。
- 当前成员行只保留最近一次关系。重新加入时更新 membership_epoch、join_seq、joined_at，重置 last_read_seq 为 join_seq - 1；旧设备游标可以留存，但不能参与新周期同步。

数据库约束不能代替业务事务：

1. 创建单聊时，应用按数值排序双方 ID，生成 direct_key，并在同一事务写入两位成员。群人数上限、群主身份与成员角色一致性由持有会话行锁的 Service 校验。
2. 新消息先在认证发送者范围内检查幂等，再在会话锁内校验当前发言权限和成员周期，更新 latest_seq、插入 message 与 message_outbox，提交后才返回 SEND_ACK。
3. 好友关系的两个方向必须同事务创建/解除；处理申请时同时设置 status 与 handled_at。退出群时同时设置成员状态与 left_at。
4. 已读和设备接收进度必须校验当前成员周期、可见起点及 latest_seq，并使用单调更新。SQL CHECK 只验证本行范围，不检查另一张表或阻止后续较小值覆盖。
5. 同一设备重新登录时，由认证事务撤销旧 auth_session，再创建新会话；索引不会自动判断凭证过期或执行会话替换。

Outbox 初始为 PENDING。认领时切到 PUBLISHING，同时写入新的 lease_token 和 lease_until；MQ 发布在事务外进行。结果更新必须匹配 event_id、PUBLISHING 状态及本次 lease_token，避免过期发布者覆盖新认领结果。转回 PENDING 或完成 PUBLISHED 时清空租约；只有完成发布才填写 published_at。attempts 记录 Outbox 发布次数，与 MQ 消费事件中的 attempt 分开。

## 如何执行

默认 skeleton 配置不连接 MySQL，也不会执行 V1；启用 local 才会迁移业务表。

使用项目 Compose 时，MYSQL_DATABASE 会在首次初始化数据卷时建库，之后按 [部署说明](../deploy/README.md) 启动后端 local 配置，由 Flyway 自动执行 V1。不要同时把 V1 挂载到 Docker 初始化目录，避免两套工具重复建表。

使用已有 MySQL 实例时，在仓库根目录先执行建库脚本：

```bash
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -u root -p < deploy/mysql/00-create-database.sql
```

随后由数据库管理员提供具备目标库迁移权限的账号，设置 MYSQL_DATABASE、MYSQL_USERNAME、MYSQL_PASSWORD 等环境变量，再按部署说明启用 local。建库脚本的默认库名为 koko_chat，自定义库名时需同步配置。

V1 不使用 CREATE TABLE IF NOT EXISTS，也不包含 DROP TABLE，以便重复或不兼容的结构立即暴露。已应用的迁移保持不变，后续通过 V2、V3 演进。MySQL DDL 的提交边界不能保证整份多表迁移一起回滚；失败时应检查已创建对象和 Flyway 状态，再处理失败迁移。不要在非空业务库中自动打开 baseline-on-migrate 来掩盖结构差异。

## 验证方法与当前范围

普通 `./mvnw verify` 验证后端编译与现有 HTTP/Netty 测试；没有 MySQL 测试连接时，MySqlSchemaTest 明确跳过，不把跳过当成 SQL 已执行。

有本机 MySQL 8.4 后，在 `server/` 下执行：

```bash
export KOKO_CHAT_MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3306/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true'
export KOKO_CHAT_MYSQL_TEST_USER=root
# 将测试账号密码通过环境提供，避免把真实密码写入仓库。
./mvnw -Dtest=MySqlSchemaTest test
```

密码变量为 KOKO_CHAT_MYSQL_TEST_PASSWORD。该账号必须能够创建及删除测试库；测试使用随机生成的 koko_chat_test_ 前缀库名，结束时只删除它自己创建的库。验证覆盖首次迁移、重复迁移无新增操作、消息双唯一键、成员周期独立、好友申请重新提交、Outbox 状态约束，以及消息/序号/Outbox 同事务回滚。

本次已通过 SQLGlot 28.0.0 的 MySQL 方言静态解析（1 条建库、9 条建表）、约束名检查、迁移资源打包核对和前后端常规测试。当前环境未运行 Docker/MySQL，本次没有对应用数据库执行 SQL；真实迁移测试因缺少连接配置而跳过，静态解析不能代替 MySQL 引擎执行验证。

语法与约束依据：[MySQL 8.4 建表语法](https://dev.mysql.com/doc/refman/8.4/en/create-table.html)、[CHECK 约束](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)、[外键规则](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)。
