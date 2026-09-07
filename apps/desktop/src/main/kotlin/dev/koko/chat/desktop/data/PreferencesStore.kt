package dev.koko.chat.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.generated.DesktopDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Only non-sensitive preferences exist at this stage; account message stores come later. */
class PreferencesStore(private val file: Path, private val dispatcher: CoroutineDispatcher) {
    private var driver: JdbcSqliteDriver? = null
    private var database: DesktopDatabase? = null

    private fun database(): DesktopDatabase {
        database?.let { return it }
        Files.createDirectories(file.parent)
        val opened = JdbcSqliteDriver("jdbc:sqlite:$file", Properties(), DesktopDatabase.Schema)
        driver = opened
        return DesktopDatabase(opened).also { database = it }
    }

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
        db.transaction {
            db.preferencesQueries.putValue("api_base_url", validated.apiBaseUrl)
            db.preferencesQueries.putValue("im_url", validated.imUrl)
        }
    }

    suspend fun close() = withContext(dispatcher) {
        driver?.close()
        driver = null
        database = null
    }
}
