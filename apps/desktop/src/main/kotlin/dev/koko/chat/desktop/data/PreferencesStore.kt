package dev.koko.chat.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.generated.DesktopDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** 只保存非敏感设置；调用方提供串行 IO 执行器，账号消息库在后续业务阶段单独实现。 */
class PreferencesStore(private val file: Path, private val dispatcher: CoroutineDispatcher) {
    private var driver: JdbcSqliteDriver? = null
    private var database: DesktopDatabase? = null

    /** 延迟打开数据库，由 SQLDelight 生成的 Schema 管理建表，禁止在 UI 线程直接调用。 */
    private fun database(): DesktopDatabase {
        database?.let { return it }
        Files.createDirectories(file.parent)
        val opened = JdbcSqliteDriver("jdbc:sqlite:$file", Properties(), DesktopDatabase.Schema)
        driver = opened
        return DesktopDatabase(opened).also { database = it }
    }

    /** 没有保存值时使用本机默认地址，读取结果仍需通过统一地址校验。 */
    suspend fun load(): ServiceSettings = withContext(dispatcher) {
        val queries = database().preferencesQueries
        val defaults = ServiceSettings()
        ServiceSettings(
            queries.selectValue("api_base_url").executeAsOneOrNull() ?: defaults.apiBaseUrl,
            queries.selectValue("im_url").executeAsOneOrNull() ?: defaults.imUrl,
        ).validated()
    }

    suspend fun save(settings: ServiceSettings) = withContext(dispatcher) {
        val validated = settings.validated()
        val db = database()
        // 两个地址在同一同步事务中写入，事务体内不挂起、不切线程、不发网络请求。
        db.transaction {
            db.preferencesQueries.putValue("api_base_url", validated.apiBaseUrl)
            db.preferencesQueries.putValue("im_url", validated.imUrl)
        }
    }

    /** 首次启动生成设备 UUID；INSERT OR IGNORE 避免另一实例覆盖同一数据目录的设备身份。 */
    suspend fun deviceId(): String = withContext(dispatcher) {
        val queries = database().preferencesQueries
        queries.putIfAbsent("device_id", java.util.UUID.randomUUID().toString())
        queries.selectValue("device_id").executeAsOne()
    }

    /** 由应用退出流程调用；应先停止所有使用此存储的任务。 */
    suspend fun close() = withContext(dispatcher) {
        driver?.close()
        driver = null
        database = null
    }
}
