# Kotlin 桌面客户端设计

状态：架构基线 v1，当前已实现认证、好友管理、文本单聊与群聊、账号独立 SQLite 缓存、历史补拉、展示分页、已读/未读同步和 RustFS 图片/文件收发，运行及验证范围见 [桌面工程说明](../apps/desktop/README.md) 和 [单聊验收](chat-verification.md)。本文描述完整目标；全文检索等仍为后续实现契约，与 [整体架构](architecture.md) 和 [RabbitMQ 设计](messaging.md) 配套。

## 1. 技术与构建边界

| 部分 | 确定选型 |
| --- | --- |
| 桌面运行 | Kotlin/JVM、JDK 21 |
| UI | Compose Desktop，声明式界面 |
| 页面状态 | MVVM、普通 Kotlin ScreenModel、StateFlow |
| 异步任务 | Kotlin Coroutines、结构化并发 |
| HTTP / 长连接 | Ktor Client、CIO engine、HTTPS / WSS |
| 协议 | kotlinx.serialization、JSON v1 |
| 本地数据 | SQLDelight、SQLite JVM driver |
| 构建与安装包 | Gradle Kotlin DSL、Compose 插件、jpackage / jlink |

桌面端独立放在 apps/desktop，使用 Gradle Wrapper 和版本目录管理依赖；服务端独立使用 Maven Wrapper。两端通过 contracts 中的 JSON Schema、HTTP 契约和样例约定字段，不直接共享 Spring 实体或 Java 序列化对象。

首期使用 Kotlin/JVM 桌面目标，先在当前 macOS 开发环境验证，再建立 Windows/Linux 构建与验收任务。最低系统版本、CPU 架构及安装格式随最终锁定的 Compose 版本记录，不能将“支持桌面”理解为所有历史系统都能运行。[Compose 兼容要求](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)

客户端通过 Ktor 调用 Java 服务端的 HTTP API 和 Netty WSS；RabbitMQ、MySQL、Redis 均由服务端访问，客户端不持有其中的账号或连接。

## 2. 客户端结构

建议在一个桌面工程中按职责分包，先不拆多个库：

```text
apps/desktop/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── src/main/
    ├── kotlin/.../koko/desktop/
    │   ├── app/                 # Main、窗口、AppScope、依赖装配
    │   ├── ui/
    │   │   ├── login/
    │   │   ├── chat/            # 会话列表、消息列表、输入框
    │   │   ├── contacts/
    │   │   ├── groups/
    │   │   ├── settings/
    │   │   └── components/
    │   ├── presentation/        # ScreenModel、UiState、UiAction
    │   ├── session/             # SessionManager、凭证刷新、生命周期
    │   ├── network/             # Ktor API、WsConnection、JSON DTO
    │   ├── messaging/           # 顺序事件处理器、OutgoingSender
    │   ├── sync/                # SyncCoordinator、缺口检测、增量拉取
    │   ├── data/                # MessageStore、SQLDelight 查询适配
    │   ├── platform/            # 托盘、通知、凭证存储、目录、文件选择
    │   └── config/              # 服务地址、超时、主题等配置
    ├── sqldelight/              # 本地表、查询、迁移
    └── resources/
```

以上目录是完整客户端的目标结构。目前已有构建脚本、窗口、ScreenModel、Ktor 探针、SessionManager 生命周期占位与 SQLite 设置存储；账号消息库、发送队列和同步协调器随业务阶段补充。

调用关系为：**Compose 页面 → ScreenModel → MessageStore / SessionManager → Ktor 或 SQLite**。返回的数据库结果和会话状态形成 UiState，再由 Compose 渲染。

- Compose 组件展示状态、发出用户动作；不在组件函数内执行登录、建连或数据库查询。
- ScreenModel 维护页面状态与页面级任务，不持有全局 WebSocket；关闭页面仅取消页面任务。
- MessageStore 负责本地消息合并和发送意图；SyncCoordinator 负责按权限与游标补拉。
- SessionManager 持有当前账号的网络会话，协调认证、连接、重连、同步和发送队列。
- PlatformServices 封装平台差异；业务和 UI 不散落操作系统判断。

这是客户端 MVVM 分层，服务端继续采用 Controller / Handler → Service → Mapper，不引入 DDD。

## 3. 功能页面与交互

| 页面 | 首期内容 |
| --- | --- |
| 登录 / 注册 | 服务地址、账号密码、登录状态、明确的错误反馈 |
| 聊天主窗口 | 会话列表、当前消息列表、输入区、连接状态提示 |
| 联系人 | 搜索用户、好友申请、同意/拒绝、发起单聊 |
| 群组 | 建群、成员列表、邀请、退出、按权限移除成员 |
| 设置 | 用户资料、主题、通知开关、关闭窗口行为、缓存管理 |

当前实现：默认展示最近 200 条本地消息，每次向前加载最多 50 条；展开范围在当前会话/成员周期内保留，返回最新或切换会话时重置。服务器历史仍正向补拉到 SQLite，展示分页不修改接收/已读进度。消息列表使用惰性列表和稳定 key；保留阅读位置，向前加载历史时不突然跳到列表底部。只有用户接近底部或主动点击“新消息”时滚动到底部。发送消息后立即出现本地气泡，显示发送状态；离线时明确提示消息排队。

草稿按账号、会话保存。输入法正在组合文字时 Enter 不触发发送；Enter/Shift+Enter、复制文本、键盘焦点与不同缩放比例在桌面验收中检查。首期支持文字和固定表情，文件图片已通过认证 HTTP 接入，详见 [附件契约](../contracts/attachments.md)。

## 4. 状态与事件分开处理

StateFlow 用于 ConnectionState、UiState、当前会话、未读数量等“当前状态”。它会合并更新，不保证观察者收到每个中间值，因此不能用 `StateFlow<Message?>` 承载每一条消息、ACK 或游标变更。[StateFlow 语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)

WSS 事件、HTTP 同步结果和本地发送动作进入同一个受控顺序处理器，再提交本地事务。事件通道采用有限容量和挂起背压，不使用丢弃旧值的策略。持续超载或读循环失败时关闭本次连接，之后通过数据库游标恢复，而不是静默丢失消息后继续推进游标。

本地数据库是消息列表的唯一持久来源；UI 的发送状态、服务器 ACK 和同步结果最终合并到同一条本地记录。StateFlow 发布合并后的 UI 状态，不再维护另一套独立的“网络消息列表”。

界面相关任务使用桌面 UI dispatcher；ScreenModel 若依赖 Dispatchers.Main，显式引入 kotlinx-coroutines-swing 并验证可用性。数据库和阻塞操作切到受控 IO 执行器，不能因收集 StateFlow 而把查询放回 UI 线程。

## 5. 会话与连接生命周期

连接状态定义为：

```text
SIGNED_OUT → CONNECTING → AUTHENTICATING → SYNCING → ONLINE
                   ↓            ↓           ↓         ↓
                         BACKOFF → CONNECTING

凭证失效且刷新失败 → RELOGIN_REQUIRED
用户注销 / 程序退出 → CLOSING → SIGNED_OUT
```

登录会话与当前网络连接分别建模。已登录用户断网时可以查看缓存、保存草稿并排队发送；未登录用户不能以旧账号身份恢复发送。

每个账号会话只有一套 SessionScope、一条活跃 WSS 和一个重连任务。连接的读循环、写循环与心跳作为同一连接任务的子任务，任何一个异常都结束本次连接并由统一管理器决定下一步。Composable 的重组和不同页面的 LaunchedEffect 不拥有这套全局连接。

Ktor CIO 支持本方案的 HTTPS 和 WebSocket。HTTP 请求设置超时，WSS 由连接状态机和心跳管理长期存活；普通请求的自动重试不能盲目作用于建群、好友申请等非幂等写入。[Ktor CIO](https://ktor.io/docs/client-engines.html#cio)、[WebSocket 客户端](https://ktor.io/docs/client-websockets.html)

凭证刷新和重连各自只允许一个并发任务。并发 HTTP 401、WSS 认证失效和恢复网络事件都交给同一 SessionManager 协调；不假定 HTTP 客户端的刷新插件已经管理 WSS 重连。认证拒绝进入重新登录流程，网络失败使用带随机抖动的指数退避（建议起始 1 秒，上限 30 秒）。每次新建连接重新获取一次性票据。

应用级 PING/PONG 沿用服务端协议约定的 25 秒/75 秒初始参数；它与 WebSocket 控制帧 Ping/Pong 分开计时，避免两套机制互相误判。系统休眠唤醒或网络切换后重新检查连接，不因电脑暂停而直接认定会话永久失效。

### 账号切换与退出

每次登录分配递增的 sessionGeneration，作为网络结果和数据库操作的会话边界。缓存数据库路径按服务器环境与账号隔离，设备标识与本次会话归属一起保存。

切换账号时先标记旧 generation 失效，停止其新发送和重连，再取消并等待旧任务结束，完成已开始的本地事务，关闭旧连接和账号数据库后启用新会话。迟到的 HTTP/WSS 回调在排队和事务入口再次校验 generation，不能写入新账号数据。

访问凭证尽量保存在内存；需要“记住登录”时通过 CredentialStore 适配系统凭证存储。平台适配未完成时不落盘刷新凭证，应用重启重新登录；不把密码或明文刷新凭证放入消息 SQLite、配置文件或日志。

## 6. SQLite 数据与事务

| 本地表 | 用途与关键约束 |
| --- | --- |
| local_conversation | 会话名称、成员周期、最新序号和显示设置 |
| local_message | 稳定 localKey；服务端 messageId 唯一；会话/成员周期/seq；正文与发送状态 |
| outgoing_message | clientMsgId 唯一；目标会话、内容、创建时成员周期、尝试次数、nextAttemptAt |
| sync_cursor | 会话 + membershipEpoch；lastContiguousSeq、同步目标上界 |
| local_read_state | 每会话/成员周期的本地阅读进度及待上报进度 |
| draft | 会话草稿、修改时间 |
| app_preferences | 非敏感界面偏好；不保存账号密码 |

SQLDelight 使用 SQLite JVM driver 生成类型化查询。数据库迁移、查询与写入在 IO 执行器中执行；写入采用单个受控队列，避免多个入口并发修改消息状态和游标。

JDBC 驱动事务使用线程相关状态；一个事务体内只执行同步数据库操作，不发网络请求、不挂起、不切换 dispatcher。应在切到 IO 执行器后完整执行事务，提交后再发送 ACK 或发布 UI 状态。[SQLDelight 事务](https://sqldelight.github.io/sqldelight/2.1.0/jvm_sqlite/transactions/)、[JDBC 驱动实现](https://github.com/sqldelight/sqldelight/blob/main/drivers/jdbc-driver/src/main/kotlin/app/cash/sqldelight/driver/jdbc/JdbcDriver.kt)

本地消息、连续游标和待发送状态的相关修改同事务提交。升级数据库前验证迁移脚本；迁移失败不自动删除缓存或待发送数据。发生磁盘满、只读或数据库错误时显示明确故障并暂停接收确认/新发送，禁止在未持久化的情况下推进游标。

## 7. 发送状态与消息合并

用户发送时，在同一本地事务中写入乐观消息和 outgoing_message，生成稳定 clientMsgId；提交后界面显示“发送中”或“等待网络”。OutgoingSender 从持久队列读取，不靠 UI 内存保存待发送意图。

发送状态区分：

| 状态 | 意义 |
| --- | --- |
| QUEUED | 已保存在本地，等待可用连接 |
| SENDING | 本次发送已发起，等待服务器结果 |
| UNKNOWN | 结果未知，例如 ACK 超时；使用原 clientMsgId 重试确认 |
| PERSISTED | 收到 SEND_ACK 或自身消息的权威回送/同步记录，确认服务端已保存 |
| FAILED | 明确不可恢复错误，例如无权限或非法内容；展示原因并允许用户处理 |

设备接收与用户已读是另外的回执进度，不塞入服务端保存状态。状态合并必须单调：迟到的发送超时不能把 PERSISTED 改回 UNKNOWN/FAILED。

按发送者身份范围内的 clientMsgId 和服务端 messageId 合并 ACK、MESSAGE、HTTP 历史；自身消息回送与同步 DTO 必须携带 clientMsgId。保持同一个 localKey，使消息确认后不会重新生成 UI 气泡或改变列表 key。

同一 clientMsgId 的重试不能修改会话、创建时成员周期或正文；用户编辑重发应生成新 ID。每会话发送任务串行确认，跨会话可以有限并发。若消息结果未知，先重试确认前一条，不直接跨过它造成用户输入顺序变化。

账号切换时停止旧账号发送；原账号恢复登录后再处理其持久队列。群成员周期改变后，未知结果只能使用原 clientMsgId、原 membershipEpoch 和原内容重试 SEND：服务端先返回已接受记录的原 ACK；若未接受且周期已过期，则明确拒绝。已知未发送的旧周期记录直接标为需用户处理，不自动改成新周期发出。旧周期 ACK 可确认本地原记录，但不能推进新周期同步游标。

## 8. 同步与回执

1. AUTH_OK 后先加载有权限访问的会话、当前 membershipEpoch、visibleFromSeq、latestSeq 和服务端 lastReadSeq。
2. 从 SQLite 读取当前周期的连续游标。新周期初始化为 visibleFromSeq - 1；旧周期游标不能复用。
3. HTTP 按 afterSeq 和固定 toSeq 上界分页补拉，同时继续接收 WSS 实时消息。
4. 两路结果交给同一个消息合并入口；按 messageId 去重，seq 解析成数值排序。
5. 同一事务内写入消息并推进连续游标。收到 12 但缺少 11 时不直接跳到 12；SEND_ACK 得到自己的 seq 也遵循同样规则。
6. 提交后上报 RECEIVED_ACK；用户实际查看会话内容且窗口处于前台/具有焦点时，按实际已读范围推进 READ，后台收消息不自动标为已读。

同一成员周期内，按 max 合并服务端 lastReadSeq 与本地待上报阅读进度，并在恢复连接后补报尚未确认的 READ。新周期的阅读起点为 visibleFromSeq - 1，旧周期进度不参与合并。服务端读取快照让新设备、离线设备以及丢失 READ_UPDATE 的客户端恢复阅读状态；它只能更新已读/未读展示，不能推进本地连续游标或冒充设备已经缓存消息。

协议中首期序号由会话事务连续分配且不做物理删除；将来删除、保留期或可见性过滤产生不可读取范围时，服务端必须返回明确的跳过/重置边界，不能让客户端猜测空洞。同步失败时保留已提交游标，下次从该位置继续。

周期校验、恢复前台、重连、休眠唤醒均触发同一个 SyncCoordinator，按会话合并重复同步请求，避免每次事件同时启动全量拉取。缺失最后一条在线推送也由周期校验发现。

服务端保存的设备接收进度只作镜像。新设备或缓存清空后，从合法历史起点重建，不使用镜像高游标跳过本地尚未保存的数据。

## 9. 窗口、托盘与退出

- 关闭窗口：根据设置隐藏到托盘，账号连接继续运行；系统不支持托盘时提供明确退出行为。
- 注销账号：终止账号 SessionScope，撤销服务端登录会话，关闭连接并清理凭证；失败的远端撤销记录可提示，但本地不继续使用旧身份。
- 退出程序：应用级清理任务停止新发送和重连，等待已开始的本地事务完成，关闭 Ktor、WebSocket 和 SQLite，再退出进程。
- 强制结束进程：下次启动依靠持久队列与游标恢复，不要求退出时所有网络任务都发送成功。

清理任务由仍存活的 AppScope 管理，不能放进已经取消的账号 Scope。通知点击回到对应会话；注销后不展示上个账号的消息内容。多窗口共享账号会话，不能每个窗口建立一条独立连接。[Compose 窗口与托盘](https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html)

## 10. 打包与平台验证

客户端构建显式固定 Java toolchain=21、JVM target=21 和打包使用的 javaHome。Kotlin、Compose Compiler、Compose UI 与 Gradle 版本已在工程版本目录和 Wrapper 中锁定，Compose Compiler 插件版本与 Kotlin 插件保持一致；具体版本及官方依据见桌面工程 README。

Compose 的 jpackage/jlink 流程生成包含 Java 运行时的安装包，用户无需自行安装 JDK。官方流程按目标操作系统构建：macOS 生成 dmg/pkg、Windows 生成 msi/exe、Linux 生成 deb/rpm，不假定在一台 macOS 上生成所有系统安装包。[Compose 原生分发](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)

从工程骨架阶段就验证打包后的应用：

1. 使用 runDistributable 检查裁剪运行时，验证 java.sql、TLS 相关模块、SQLite 原生库和资源加载。
2. 在目标 OS/CPU 架构上检查窗口、字体、中文输入、通知、托盘、休眠唤醒和数据目录。
3. 验证升级安装保留本地消息及草稿，数据库迁移失败有明确反馈。
4. 正式分发前配置平台签名/公证；首期先完成本地安装包，不把自动更新当成 jpackage 已自带的完整业务能力。

只有 IDE 运行成功不能作为桌面交付验收结果。

## 11. 客户端最低验收

| 场景 | 预期 |
| --- | --- |
| 页面重复重组、切换会话、多窗口 | 同账号只有一个活跃连接和一个重连任务 |
| 多请求同时凭证过期 | 刷新被合并，失败后进入重新登录 |
| 切账号时旧请求返回 | 不写入新账号缓存，不显示旧账号消息 |
| MESSAGE/ACK/同步结果任意先后 | 一个 localKey、一个气泡，保存状态不倒退 |
| ACK 丢失后重启 | 原 clientMsgId 重试，服务端只保留一条消息 |
| seq 乱序和丢失最后一条提示 | 连续游标不越过缺口，周期同步补齐 |
| 另一设备已读、READ_UPDATE 丢失或重装 | 会话快照修复阅读状态，不跳过本地历史同步 |
| SQLite 提交前后崩溃 | 消息和游标保持一致，待发送记录可恢复 |
| 退出群后重新加入 | 旧周期消息/游标不混入新周期，旧待发送任务不自动发出 |
| 磁盘满、数据库失败 | 暂停不安全的游标/回执推进，展示可理解错误 |
| 托盘隐藏、唤醒、主动退出 | 按定义保留或终止连接，无孤立重连任务 |
| 各平台安装包启动 | JDBC、TLS、字体、图片和通知正常 |

上表为完整聊天客户端的目标验收。当前骨架验证覆盖基础构建、设置存储、服务探针及 macOS 打包，尚未完成认证、消息收发和同步的端到端验收。
