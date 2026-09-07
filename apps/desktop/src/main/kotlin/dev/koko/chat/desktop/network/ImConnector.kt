package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 此异常要求重新登录；普通连接失败由会话管理器退避重试。 */
class ImAuthenticationLost : Exception("登录已失效或已在同设备重新登录")
interface ImConnector {
    /** 仅收到并验证 AUTH_OK 后调用回调；方法持续运行直到连接关闭或被取消。 */
    suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit)
}

/** 使用 Ktor CIO 保持 WebSocket；退出时取消并等待连接任务，不依赖 UI 重组生命周期。 */
class KtorImConnector(private val client: HttpClient) : ImConnector {
    override suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit) = coroutineScope {
        val socket = withTimeout(10_000) {
            client.webSocketSession {
                url(settings.forAuthentication().imUrl)
                // 长连接的存活由协议心跳控制，不能沿用普通 HTTP 的 5 秒读取超时。
                timeout { socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            }
        }
        try {
            val requestId = UUID.randomUUID().toString()
            socket.send(buildJsonObject { put("v",1); put("type","AUTH"); put("requestId",requestId); put("ticket",ticket) }.toString())
            val response = withTimeout(10_000) { readObject(socket.incoming.receive()) }
            if (response["type"]?.jsonPrimitive?.content == "ERROR") {
                if (response["code"]?.jsonPrimitive?.content == "UNAUTHENTICATED") throw ImAuthenticationLost()
                error("IM authentication unavailable")
            }
            require(response["type"]?.jsonPrimitive?.content == "AUTH_OK" && response["requestId"]?.jsonPrimitive?.content == requestId
                && response["sessionId"]?.jsonPrimitive?.content == expected.sessionId
                && response["userId"]?.jsonPrimitive?.content == expected.user.id
                && response["deviceId"]?.jsonPrimitive?.content == deviceId) { "IM authentication response mismatch" }
            onAuthenticated()
            val lastPong = AtomicLong(System.nanoTime())
            val heartbeatId = UUID.randomUUID().toString()
            val heartbeat = launch {
                while (isActive) {
                    socket.send(buildJsonObject { put("v",1); put("type","PING"); put("requestId",heartbeatId) }.toString())
                    delay(25_000)
                    if (System.nanoTime() - lastPong.get() > 60_000_000_000L) error("IM heartbeat timed out")
                }
            }
            try {
                for (frame in socket.incoming) {
                    val value = readObject(frame)
                    if (value["type"]?.jsonPrimitive?.content == "PONG" && value["requestId"]?.jsonPrimitive?.content == heartbeatId) lastPong.set(System.nanoTime())
                    if (value["type"]?.jsonPrimitive?.content == "ERROR" && value["code"]?.jsonPrimitive?.content == "UNAUTHENTICATED") throw ImAuthenticationLost()
                }
                if (withTimeoutOrNull(1_000) { socket.closeReason.await() }?.code?.toInt() == 1008) throw ImAuthenticationLost()
                error("IM connection closed")
            } finally { heartbeat.cancelAndJoin() }
        } finally {
            // 无论认证失败、远端关闭还是账号退出，都释放 CIO 会话及其子任务。
            withContext(NonCancellable) {
                withTimeoutOrNull(1_000) { socket.close(CloseReason(CloseReason.Codes.NORMAL,"Client session closed")) }
                socket.coroutineContext[Job]?.cancelAndJoin()
            }
        }
    }
    private fun readObject(frame: Frame): JsonObject {
        require(frame is Frame.Text) { "Expected JSON text" }
        val result = Json.parseToJsonElement(frame.readText()).jsonObject
        require(result["v"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported IM version" }
        return result
    }
}
