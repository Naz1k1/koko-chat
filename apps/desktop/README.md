# koko-chat 桌面端

Kotlin/JVM 21 + Compose Desktop 桌面客户端。已接入中文注册/登录界面、令牌刷新、WebSocket 票据认证、心跳、断线重连与退出清理，以及准确账号发起单聊、文本收发、待发送重试和离线同步，以及好友申请、接受/拒绝和联系人聊天入口；群聊、已读与托盘尚未实现。HTTP 检查成功不会被显示为 IM 在线。

## 启动与验证

先将 `JAVA_HOME` 指向本机 JDK 21，然后在此目录执行：

```sh
./gradlew test
./gradlew run
./gradlew createDistributable
./gradlew runDistributable
./gradlew packageDistributionForCurrentOS
```

构建默认使用正常的 Gradle 用户缓存；如需临时隔离缓存，可为命令设置 `GRADLE_USER_HOME=/private/tmp/koko-chat-build/gradle`。不需要全局安装 Gradle。

默认 HTTP 地址为 `http://127.0.0.1:8080`，IM 地址为 `ws://127.0.0.1:8081/im`。检查服务依次请求 `GET /api/system/info` 与 `GET /actuator/health`，要求系统名为 `koko-chat`。远程部署应使用 HTTPS/WSS。

后端启动后，可显式运行真实 CIO 联调测试（未设置环境变量时按 JUnit Assume 跳过）。使用 `--rerun-tasks` 避免沿用之前跳过或成功的缓存结果：

```sh
KOKO_CHAT_TEST_API_BASE=http://127.0.0.1:8080 ./gradlew test --tests dev.koko.chat.desktop.LiveServiceProbeTest --rerun-tasks
```

该测试验证真实 HTTP 链路。另有 Compose 离屏渲染检查，覆盖登录、注册切换、聊天工作区以及 1120×760 / 960×640 窗口布局；尚未在系统窗口中自动执行键盘、焦点和窗口关闭操作。

设置库保存服务地址和设备 UUID；`accounts/` 下按服务地址与用户 ID 的 SHA-256 分文件保存会话、消息和待发送记录。缓存未加密，注销只清除页面和凭证，保留本机历史供下次登录恢复。数据默认位于当前用户的应用数据目录：macOS `~/Library/Application Support/koko-chat`，Windows `%APPDATA%/koko-chat`，Linux `$XDG_DATA_HOME/koko-chat`（未设置则为 `~/.local/share/koko-chat`）。设置环境变量 `KOKO_CHAT_DATA_DIR` 可覆盖目录，便于测试；不要指向仓库的源代码目录。

## 实现边界

- `AppRuntime` 在 Compose 之外创建，拥有应用作用域、Ktor 与 SQLite IO 执行器。
- `DesktopScreenModel` 是普通 Kotlin 类，以 `StateFlow` 发布页面状态；同一时间只执行一次服务检查。
- `SessionManager` 管理单一账号连接循环，以 1/2/4/8/16/30 秒间隔重连；只在收到并核对 AUTH_OK 后显示在线。临近过期的访问令牌在申请新票据前串行刷新，刷新结果不明确则要求重新登录。
- SQLite 使用 SQLDelight 生成 schema/query，设置库与账号消息库分别生成 schema/query。消息事务落盘后才报告连续接收游标；乱序消息先保存，填平缺口后再推进。
- 修改服务地址会先退出账号；关闭窗口会取消页面任务、关闭 IM 连接、尝试服务端注销并清空内存凭证，再关闭网络和数据库。断网时会提示撤销未确认。当前没有“关闭到托盘”。

## 锁定版本与分发

版本目录锁定 Kotlin/Compose Compiler 2.4.10、Compose 1.12.0、Material3 1.9.0、Ktor 3.5.2、Coroutines 1.11.0、Serialization 1.11.0、SQLDelight 2.3.2，Wrapper 锁定 Gradle 9.5.0（含官方 SHA-256）。2026-09-07 已核对官方发布资料及 Maven Central 元数据。Material3 独立发布，使用其稳定版本；不使用 RC 或动态版本。

- [Kotlin 与 Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Compose 1.12.0 发布说明](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0)
- [Ktor 发布说明](https://github.com/ktorio/ktor/releases/tag/3.5.2)
- [SQLDelight 发布说明](https://github.com/sqldelight/sqldelight/releases/tag/2.3.2)
- [Compose 安装包与 JDK 模块](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)

编译 toolchain、JVM target 与打包 Java runtime 均设为 21。安装包包含运行时，在对应系统构建；macOS/Windows/Linux 安装包不能在单一平台上交叉打包。macOS 的安装包编号单独从 `1.0.0` 开始，以兼容已验证的 JDK 21.0.2 jpackage 对首位数字的限制；工程与界面版本仍为 `0.1`。正式发布还需签名、公证和各系统验收。

## 双客户端认证联调

在根目录先运行 `./scripts/verify-auth.sh` 构建后端，再运行 `./scripts/verify-desktop-auth.sh`。此测试覆盖两个独立 CIO 客户端并发注册、登录、票据认证、错误密码拒绝、35 秒心跳保活和退出，测试完成后清理临时账号与服务进程。普通 Gradle 测试仍默认跳过实时联调。

同一数据目录共享设备 UUID，因此同账号的多个应用实例会相互替换连接。验证多设备时给两个实例设置不同的 `KOKO_CHAT_DATA_DIR`。密码、访问令牌及刷新令牌不写入 SQLite，暂未接入系统钥匙串，重启应用后需要重新登录。

客户端连接 API 依据：[Ktor WebSocket 文档](https://ktor.io/docs/client-websockets.html)。

界面预览：[登录](../../docs/screenshots/auth-login.png)、[最小窗口注册](../../docs/screenshots/auth-register-960.png)。这些 PNG 来自实际 Compose 组件的离屏渲染。

## 联系人管理

工作区点击“联系人”可添加准确账号、填写附言、查看好友及收发申请。收到的待处理申请可接受或拒绝，好友卡片可直接打开单聊。ContactModel 将周期刷新和修改串行处理，每 10 秒更新一次，也支持手动刷新。联系人当前只保存在账号内存快照中，注销清空；聊天历史仍使用独立 SQLite 缓存。好友变更暂不走 MQ 推送，未实现取消、删除、拉黑或备注编辑。
