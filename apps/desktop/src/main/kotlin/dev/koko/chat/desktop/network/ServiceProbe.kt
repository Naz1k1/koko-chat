package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SystemInfo(
    val name: String,
    val version: String,
    val stage: String,
    val httpPort: Int,
    val imPort: Int,
    val imPath: String,
)

@Serializable
data class HealthInfo(val status: String)

data class ProbeResult(val system: SystemInfo, val health: HealthInfo)

interface ServiceProbe {
    suspend fun check(settings: ServiceSettings): ProbeResult
}

/** Shared client owned by the application, never instantiated during composition. */
fun createHttpClient(): HttpClient = HttpClient(CIO) { configureClient() }

/** Test injection: the caller owns the explicitly supplied engine. */
internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configureClient() }

private fun HttpClientConfig<*>.configureClient() {
    expectSuccess = true
    followRedirects = false
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 3_000
        socketTimeoutMillis = 5_000
    }
    install(WebSockets) { maxFrameSize = 16 * 1024 }
}

class KtorServiceProbe(private val client: HttpClient) : ServiceProbe {
    override suspend fun check(settings: ServiceSettings): ProbeResult {
        val base = settings.validated().apiBaseUrl
        val system = client.get("$base/api/system/info").body<SystemInfo>()
        require(system.name == "koko-chat") { "目标地址不是 koko-chat 服务" }
        val health = client.get("$base/actuator/health").body<HealthInfo>()
        return ProbeResult(system, health)
    }
}
