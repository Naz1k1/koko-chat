package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 此异常要求重新登录；普通连接失败由会话管理器退避重试。 */
class ImAuthenticationLost : Exception("登录已失效或已在同设备重新登录")
data class ImEvent(val sessionId:String,val envelope:JsonObject)
class ImCommandFailure(val code:String,message:String):Exception(message)
interface ImConnector {
    val events:Flow<ImEvent> get()=emptyFlow()
    suspend fun request(sessionId:String,envelope:JsonObject):JsonObject = error("IM connection unavailable")
    /** 仅收到并验证 AUTH_OK 后调用回调；方法持续运行直到连接关闭或被取消。 */
    suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit)
}

/** 使用 Ktor CIO 保持 WebSocket；退出时取消并等待连接任务，不依赖 UI 重组生命周期。 */
class KtorImConnector(private val client: HttpClient) : ImConnector {
    private data class Active(val sessionId:String,val socket:DefaultClientWebSocketSession)
    @Volatile private var active:Active?=null
    private val responses=ConcurrentHashMap<String,CompletableDeferred<JsonObject>>()
    private val received=MutableSharedFlow<ImEvent>(extraBufferCapacity=64)
    override val events:Flow<ImEvent> = received.asSharedFlow()
    override suspend fun request(sessionId:String,envelope:JsonObject):JsonObject {
        val connection=active ?: error("IM 未连接")
        require(connection.sessionId==sessionId) { "账号连接已变更" }
        val id=UUID.randomUUID().toString();val result=CompletableDeferred<JsonObject>()
        responses[id]=result
        try {
            connection.socket.send(JsonObject(envelope+mapOf("v" to JsonPrimitive(1),"requestId" to JsonPrimitive(id))).toString())
            val response=withTimeout(10_000) { result.await() }
            if(response["type"]?.jsonPrimitive?.content=="ERROR") throw ImCommandFailure(response["code"]?.jsonPrimitive?.content?:"UNKNOWN",response["message"]?.jsonPrimitive?.content?:"请求失败")
            return response
        } finally { responses.remove(id) }
    }
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
            active=Active(expected.sessionId,socket)
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
                    value["requestId"]?.jsonPrimitive?.content?.let { responses.remove(it)?.complete(value) }
                    if(value["type"]?.jsonPrimitive?.content in setOf("MESSAGE","READ_UPDATE") && !received.tryEmit(ImEvent(expected.sessionId,value))) error("IM 推送积压，重连后补拉")
                    if (value["type"]?.jsonPrimitive?.content == "PONG" && value["requestId"]?.jsonPrimitive?.content == heartbeatId) lastPong.set(System.nanoTime())
                    if (value["type"]?.jsonPrimitive?.content == "ERROR" && value["code"]?.jsonPrimitive?.content == "UNAUTHENTICATED") throw ImAuthenticationLost()
                }
                if (withTimeoutOrNull(1_000) { socket.closeReason.await() }?.code?.toInt() == 1008) throw ImAuthenticationLost()
                error("IM connection closed")
            } finally { heartbeat.cancelAndJoin() }
        } finally {
            if(active?.socket===socket) active=null
            responses.values.forEach { it.completeExceptionally(IllegalStateException("聊天连接已断开，请使用原消息编号重试")) };responses.clear()
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
