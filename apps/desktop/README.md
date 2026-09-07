# koko-chat 桌面端

Kotlin/JVM 21 + Compose Desktop 工程骨架。当前提供中文基础窗口、真实 HTTP 服务检查和 SQLite 非敏感服务设置；登录、WebSocket 认证、联系人、聊天收发、同步及托盘尚未实现。HTTP 检查成功不会被显示为 IM 在线。

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

默认 HTTP 地址为 `http://127.0.0.1:8080`，预留 IM 地址为 `ws://127.0.0.1:8081/im`。检查服务依次请求 `GET /api/system/info` 与 `GET /actuator/health`，要求系统名为 `koko-chat`。远程部署应使用 HTTPS/WSS。

后端启动后，可显式运行真实 CIO 联调测试（未设置环境变量时按 JUnit Assume 跳过）。使用 `--rerun-tasks` 避免沿用之前跳过或成功的缓存结果：

```sh
KOKO_CHAT_TEST_API_BASE=http://127.0.0.1:8080 ./gradlew test --tests dev.koko.chat.desktop.LiveServiceProbeTest --rerun-tasks
```

该测试验证真实 HTTP 链路，不覆盖 GUI 交互。本次图形工具受系统权限限制，按钮、设置保存及窗口退出尚未自动验收。

数据库只存服务地址，默认位于当前用户的应用数据目录：macOS `~/Library/Application Support/koko-chat`，Windows `%APPDATA%/koko-chat`，Linux `$XDG_DATA_HOME/koko-chat`（未设置则为 `~/.local/share/koko-chat`）。设置环境变量 `KOKO_CHAT_DATA_DIR` 可覆盖目录，便于测试；不要指向仓库的源代码目录。

## 实现边界

- `AppRuntime` 在 Compose 之外创建，拥有应用作用域、Ktor 与 SQLite IO 执行器。
- `DesktopScreenModel` 是普通 Kotlin 类，以 `StateFlow` 发布页面状态；同一时间只执行一次服务检查。
- `SessionManager` 仅预留生命周期并保持未登录，不伪造聊天连接。
- SQLite 使用 SQLDelight 生成 schema/query，只持久化 `app_preferences`，未提前创建账号消息缓存。
- 关闭窗口会取消页面任务、关闭网络与数据库，再退出。当前没有“关闭到托盘”。

## 锁定版本与分发

版本目录锁定 Kotlin/Compose Compiler 2.4.10、Compose 1.12.0、Material3 1.9.0、Ktor 3.5.2、Coroutines 1.11.0、Serialization 1.11.0、SQLDelight 2.3.2，Wrapper 锁定 Gradle 9.5.0（含官方 SHA-256）。2026-09-07 已核对官方发布资料及 Maven Central 元数据。Material3 独立发布，使用其稳定版本；不使用 RC 或动态版本。

- [Kotlin 与 Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Compose 1.12.0 发布说明](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0)
- [Ktor 发布说明](https://github.com/ktorio/ktor/releases/tag/3.5.2)
- [SQLDelight 发布说明](https://github.com/sqldelight/sqldelight/releases/tag/2.3.2)
- [Compose 安装包与 JDK 模块](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)

编译 toolchain、JVM target 与打包 Java runtime 均设为 21。安装包包含运行时，在对应系统构建；macOS/Windows/Linux 安装包不能在单一平台上交叉打包。macOS 的安装包编号单独从 `1.0.0` 开始，以兼容已验证的 JDK 21.0.2 jpackage 对首位数字的限制；工程与界面版本仍是 `0.1` 骨架。正式发布还需签名、公证和各系统验收。
