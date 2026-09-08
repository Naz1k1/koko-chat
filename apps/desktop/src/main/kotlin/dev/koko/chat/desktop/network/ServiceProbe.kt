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

/** 与后端系统探针对齐的传输 DTO，未知新增字段由 JSON 配置忽略。 */
@Serializable
data class SystemInfo(
    val name: String,
    val version: String,
    val stage: String,
    val httpPort: Int,
    val imPort: Int,
    val imPath: String,
)

/** Actuator 的最小健康响应；只表示被检查服务的健康状态。 */
@Serializable
data class HealthInfo(val status: String)

/** 同时保留服务身份与健康结果，供页面展示，不改变 IM 登录状态。 */
data class ProbeResult(val system: SystemInfo, val health: HealthInfo)

/** 探针接口隔离页面与 Ktor，测试可以注入受控实现。 */
interface ServiceProbe {
    suspend fun check(settings: ServiceSettings): ProbeResult
}

/** 应用共用的客户端，不在 Compose 重组中创建；关闭 HttpClient 时一并释放内部 CIO 引擎。 */
fun createHttpClient(): HttpClient = HttpClient(CIO) { configureClient() }

/** 测试注入入口：显式传入的引擎由调用方负责释放。 */
internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configureClient() }

private fun HttpClientConfig<*>.configureClient() {
    // HTTP 错误状态进入异常分支；不跟随重定向，避免把其他站点误识别为服务。
    expectSuccess = true
    followRedirects = false
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 3_000
        socketTimeoutMillis = 5_000
    }
    install(WebSockets) { maxFrameSize = 128 * 1024 }
}

/** 依次检查服务身份与健康，整个过程不会创建认证 WebSocket。 */
class KtorServiceProbe(private val client: HttpClient) : ServiceProbe {
    override suspend fun check(settings: ServiceSettings): ProbeResult {
        val base = settings.validated().apiBaseUrl
        val system = client.get("$base/api/system/info").body<SystemInfo>()
        require(system.name == "koko-chat") { "目标地址不是 koko-chat 服务" }
        val health = client.get("$base/actuator/health").body<HealthInfo>()
        return ProbeResult(system, health)
    }
}
