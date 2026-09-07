package dev.koko.chat.desktop

import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.KtorServiceProbe
import dev.koko.chat.desktop.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 验证地址校验、SQLite 重开持久化及 HTTP 契约，不依赖外部后端。 */
class DesktopFoundationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `service settings normalize URLs and reject embedded credentials`() {
        assertEquals("http://127.0.0.1:8080", ServiceSettings(" http://127.0.0.1:8080/ ").validated().apiBaseUrl)
        assertFailsWith<IllegalArgumentException> { ServiceSettings("http://user:secret@localhost:8080").validated() }
        assertFailsWith<IllegalArgumentException> { ServiceSettings("file:///tmp/server").validated() }
        assertFailsWith<IllegalArgumentException> { ServiceSettings("http://localhost:8080/api").validated() }
    }

    @Test fun `generated SQLite schema persists both endpoints across reopening`() = runTest {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val file = temporaryFolder.root.toPath().resolve("settings/preferences.db")
        val settings = ServiceSettings("https://chat.example.test", "wss://chat.example.test/im")
        val first = PreferencesStore(file, dispatcher)
        var deviceId = ""
        try {
            assertEquals(ServiceSettings(), first.load())
            first.save(settings)
            deviceId = first.deviceId()
            assertEquals(deviceId, first.deviceId())
        } finally { first.close() }
        val reopened = PreferencesStore(file, dispatcher)
        try { assertEquals(settings, reopened.load()); assertEquals(deviceId, reopened.deviceId()) }
        finally { reopened.close(); dispatcher.close() }
    }

    @Test fun `probe reads actual system and health contracts without an IM connection`() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(
                if (request.url.encodedPath == "/api/system/info")
                    """{"name":"koko-chat","version":"0.1.0-SNAPSHOT","stage":"skeleton","httpPort":8080,"imPort":8081,"imPath":"/im","futureField":true}"""
                else """{"status":"UP"}""",
                headers = headersOf("Content-Type", "application/json"),
            )
        })
        try {
            val result = KtorServiceProbe(client).check(ServiceSettings())
            assertEquals("skeleton", result.system.stage)
            assertEquals("UP", result.health.status)
            assertEquals(listOf("/api/system/info", "/actuator/health"), paths)
        } finally { client.close(); client.engine.close() }
    }

    @Test fun `probe does not accept a server error as a healthy response`() = runTest {
        val client = createHttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
        try { assertFailsWith<ResponseException> { KtorServiceProbe(client).check(ServiceSettings()) } }
        finally { client.close(); client.engine.close() }
    }
}
