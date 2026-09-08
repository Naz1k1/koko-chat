# 数据库文件与迁移说明

数据库采用 MySQL 8.4、InnoDB、utf8mb4，首个 Flyway 版本建立 9 张业务表。SQL 包含中文说明、索引及约束，旧迁移保持不变。V2 增加认证字段，V3 新增 group_command，V4 新增 attachment，V5 增加附件过期清理字段，V6 新增 call_session / call_signal，V7 新增 ops_dead_letter / ops_action，当前共 15 张业务及运维表。

当前新增迁移：[V5 附件回收](../server/src/main/resources/db/migration/V5__expire_unsent_attachments.sql)、[V6 语音通话](../server/src/main/resources/db/migration/V6__create_voice_calls.sql)。

## 文件入口

| 文件 | 用途 |
| --- | --- |
| [00-create-database.sql](../deploy/mysql/00-create-database.sql) | 在已有 MySQL 实例中创建 koko_chat 空库，不创建用户和密码 |
| [V1__create_chat_schema.sql](../server/src/main/resources/db/migration/V1__create_chat_schema.sql) | 首批 9 张业务表，由 Flyway 执行并记录版本 |
| [V3__create_group_command.sql](../server/src/main/resources/db/migration/V3__create_group_command.sql) | 群操作去重记录，与群及成员修改同事务提交 |
| [V4__create_attachments.sql](../server/src/main/resources/db/migration/V4__create_attachments.sql) | 附件元数据、状态约束与 message.attachment_id 外键 |
| [MySqlSchemaTest.java](../server/src/test/java/dev/koko/chat/database/MySqlSchemaTest.java) | 显式启用的 MySQL 8.4 迁移和约束验证，使用独立临时库 |
| [Preferences.sq](../apps/desktop/src/main/sqldelight/dev/koko/chat/desktop/data/Preferences.sq) | 桌面 SQLite 设置表，由 SQLDelight 生成建表及查询代码 |

不提交运行时的 MySQL 数据目录或桌面 `.db` 文件。桌面设置库之外已建立账号独立的消息缓存、连续游标与待发送队列，群和单聊复用 ChatCache.sq；群管理的待确认操作当前只保存在内存。

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
| group_command | 成功群操作的请求摘要和群 ID | 用户+client_command_id 唯一；群外键约束 |
| attachment | 上传元数据、文件摘要、所属会话/成员周期与绑定消息 | UUID 主键；object_key、message_id 唯一；状态/大小 CHECK；用户及会话外键 |
| message_outbox | 与消息同事务提交的待发布事件 | 消息+事件类型唯一；待发布、过期租约、已发布清理索引 |
| call_session | 呼叫、设备归属、连接确认及双方租约 | UUID 主键；参与者/状态与到期索引；用户及会话外键 |
| call_signal | 有限信令邮箱，通话结束删除 | 自增 ID；call_id+sender_session+signal_id 唯一；按通话补拉 |

好友申请的唯一键只约束 PENDING 记录。申请被接受、拒绝或取消后，生成列变为 NULL，允许未来再次申请；相反方向的新申请也不能绕过待处理限制。群聊共享 message 正文，不建立独立群消息表或离线消息正文表。

## 字段约定与事务边界

- 用户/会话/消息 ID 为应用分配的正数 BIGINT，JSON 使用十进制字符串；seq 同样使用字符串。附件、通话和信令幂等编号为 UUID；call_signal.id 是自增 BIGINT 邮箱游标，Java/Kotlin 使用 Long，协议使用整数。
- 登录会话、成员周期、事件和认领令牌采用 UUID；设备标识和 client_msg_id 最大 64 个 ASCII 字符。协议标识按二进制排序规则精确比较，账号按 utf8mb4_0900_ai_ci 不区分大小写和重音，应用须统一规范化账号。
- 密码使用带算法参数的安全密码哈希。refresh_token_hash 与 body_hash 使用 BINARY(32) 保存 SHA-256 原始摘要；刷新凭证应是高熵随机值。应用可用十六进制显示摘要，但不能把 64 字符十六进制文本直接当成 32 字节值。
- DATETIME(3) 统一保存 UTC。当前 JDBC URL 已设置连接时区；手工写入数据的连接也需使用 UTC。
- 外键只约束真实用户、会话、消息引用，采用默认限制删除的行为。message 的 sender_membership_epoch 不引用当前成员周期，确保用户离群后重新加入不会破坏旧消息和旧 ACK。
- 当前成员行只保留最近一次关系。重新加入时更新 membership_epoch、join_seq、joined_at，重置 last_read_seq 为 join_seq - 1；旧设备游标可以留存，但不能参与新周期同步。

数据库约束不能代替业务事务：

1. 创建单聊时，应用按数值排序双方 ID，生成 direct_key，并在同一事务写入两位成员。群人数上限、群主身份与成员角色一致性由持有会话行锁的 Service 校验。
2. 新消息先在认证发送者范围内检查幂等，再在会话锁内校验当前发言权限和成员周期，更新 latest_seq、插入 message 与 message_outbox，提交后才返回 SEND_ACK。
3. 好友关系的两个方向必须同事务创建/解除；处理申请时同时设置 status 与 handled_at。退出群时同时设置成员状态与 left_at。
4. 已读和设备接收进度必须校验当前成员周期、可见起点及 latest_seq，并使用单调更新。READ 还须不超过当前设备 received_seq，读取共享已读进度不能推进本机接收游标。SQL CHECK 只验证本行范围，不检查另一张表或阻止后续较小值覆盖。
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

## V2：认证访问令牌

`V2__add_access_tokens.sql` 为 `auth_session` 增加访问令牌摘要和到期时间，并通过生成列约束同用户同设备只能存在一个未撤销会话。旧会话的新增列允许为空，不能凭空获得访问权限，需重新登录。V1 保持不变。

2026-09-07 已在本机 MySQL 8.4 实际执行 V1/V2，验证重复迁移无操作、消息幂等约束、外键、Outbox 状态及事务回滚；测试结束删除独立临时库。

## 已读阶段的数据库演进

MySQL 继续复用 V1 的 conversation_member.last_read_seq 与 device_cursor，无新增业务表或 Flyway 版本，V1–V3 均保持原样。会话摘要按 message(conversation_id,seq) 范围统计其他发送者的未读消息；当前采用精确 COUNT，尚未做大规模会话或积压消息下的性能测试。

桌面消息库增加 [pending_read 建表定义](../apps/desktop/src/main/chatdb/dev/koko/chat/desktop/data/chat/ChatCache.sq) 和 [v1→v2 迁移](../apps/desktop/src/main/chatdb/dev/koko/chat/desktop/data/chat/1.sqm)，保存 conversation_id、epoch、read_seq，每个会话只保留同周期最大待确认位置。确认覆盖后删除，退群/移除/新周期清理；账号和服务文件隔离不变。迁移测试从含消息的真实 v1 SQLite 结构打开生产驱动，验证旧消息保留且新阅读意图可持久化。

## 历史展示分页查询

[ChatCache.sq](../apps/desktop/src/main/chatdb/dev/koko/chat/desktop/data/chat/ChatCache.sq) 增加按 conversation_id、epoch 和 seq 查询的 olderMessages、messagesFrom、hasMessagesBefore，复用现有联合唯一索引。向前分页使用 `seq < beforeSeq ORDER BY seq DESC LIMIT 50`，返回展示前转为升序，避免 OFFSET 随新消息到来发生偏移；visibleFromSeq 和当前成员周期限制仍有效。

展开后的刷新从当前展示起点读取至本地最新，切换会话/周期或返回最新恢复默认 200 条。分页不写消息、不推进 received_seq 或 last_read_seq，只改变当前界面的展示范围。本阶段只有查询变化，无新增 SQLite 表或迁移，消息库版本仍为 v2；MySQL V1–V3 同样未修改。

## V4：RustFS 附件与 SQLite v3

V1–V3 保持原样，V4 新增 attachment，并扩展 message.type 为 TEXT/EMOJI/IMAGE/FILE、增加 attachment_id 外键。文件字节存 RustFS；数据库保存业务归属和不可变引用。attachment.message_id 用唯一键与状态 CHECK 限制绑定形状，不反向声明消息外键以避免循环依赖；实际绑定由 Service 和 message.attachment_id 外键保证，同消息、序号、Outbox 一起提交。按依赖清理测试数据时先删 message，再删 attachment。

SQLite [2.sqm](../apps/desktop/src/main/chatdb/dev/koko/chat/desktop/data/chat/2.sqm) 将消息库从 v2 升为 v3：pending_message 增加 attachment_id，pending_upload 保存可重发元数据；上传字节保存在账号哈希目录。已用包含消息的真实 v1 结构验证连续升级到 v3，原消息和游标保留。

2026-09-08 已在真实 MySQL 8.4 独立临时库验证 V1–V4 首次执行和重复执行，共 11 张业务表；应用 local 数据库也已迁移到 V4。附件测试验证消息/附件/Outbox 回滚、重复发送和群成员可见范围，详见 [附件验收](attachment-verification.md)。本段为当前验证结论，上文首批建表的静态检查记录属于历史阶段。

## V5 / V6：附件回收与语音

V5 为 PENDING/READY 附件设置 expires_at，以 cleanup_at 标记对象回收完成时间。清理与消息绑定争用同一会话锁，只有未绑定对象能进入 EXPIRED；墓碑保留，拒绝旧上传编号。已清理墓碑每小时复扫，删除故障上传的晚到对象，不回收 ATTACHED。

V6 新增 call_session 和 call_signal。CREATE 按双方用户 ID 排序加锁，串行判断占线；ACCEPT 锁定通话行，保证多设备只有一个接听赢家。结束状态与信令删除同事务，MQ 在提交后发状态提示，不存音频。通话元数据暂保留，没有历史查询页面。

2026-09-08 已在独立 MySQL 8.4 临时库执行 V1–V6 与重复迁移验证，13 张业务表；local 库同样已到 V6。SQLite 仍为消息库 v3，缩略图只驻留内存，通话不进入持久化消息队列。结果见 [本轮验收](voice-verification.md)。

## V7：运维归档和审计重放

[迁移文件](../server/src/main/resources/db/migration/V7__create_operations_audit.sql) 增加 ops_dead_letter（按 MQ 命名空间与原始正文摘要归档）及 ops_action（人工确认/重放审计、发布租约）。active_dead_id 生成列的唯一约束保证同一归档最多一条未完成重放。正文只供内部校验，不通过查询接口返回。

归档提交后才 ACK MQ；重放操作先提交再发布，确认写回匹配租约。重放保持原 messageId / eventId，不插入 message 或更新会话序号。原归档和审计均保留，不与业务用户建立删除级联。

2026-09-08 在独立 MySQL 8.4 临时库完成 V1–V7 首次及重复迁移验证，共 15 张表；开发库也已到 V7。两个测试 JVM 另外使用独立随机测试库，结束时删除该库。详见 [运维验收](operations-verification.md)。
