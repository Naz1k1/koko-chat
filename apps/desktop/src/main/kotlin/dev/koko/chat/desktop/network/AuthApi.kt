package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.net.URI

/** 认证 DTO 不写入 SQLite；密码和令牌只在当前请求及账号会话内短期持有。 */
@Serializable data class UserProfile(val id: String, val account: String, val nickname: String)
@Serializable data class AuthTokens(val accessToken: String, val refreshToken: String, val accessExpiresAt: String,
    val refreshExpiresAt: String, val sessionId: String, val user: UserProfile)
@Serializable data class ImTicket(val ticket: String, val expiresInSeconds: Long)
@Serializable private data class RegisterBody(val account: String, val password: String, val nickname: String)
@Serializable private data class LoginBody(val account: String, val password: String, val deviceId: String)
@Serializable private data class RefreshBody(val refreshToken: String)
@Serializable private data class ApiError(val code: String, val message: String)

class AuthFailure(val status: Int, val code: String, message: String) : Exception(message)

interface AuthApi {
    suspend fun register(settings: ServiceSettings, account: String, password: String, nickname: String)
    suspend fun login(settings: ServiceSettings, account: String, password: String, deviceId: String): AuthTokens
    suspend fun refresh(settings: ServiceSettings, refreshToken: String): AuthTokens
    suspend fun logout(settings: ServiceSettings, refreshToken: String)
    suspend fun ticket(settings: ServiceSettings, accessToken: String): ImTicket
}

/** 回环地址允许明文本机调试；远程认证同时要求 HTTPS 和 WSS。 */
fun ServiceSettings.forAuthentication(): ServiceSettings = validated().also { settings ->
    for (address in listOf(settings.apiBaseUrl, settings.imUrl)) {
        val uri = URI(address)
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        require(loopback || uri.scheme.lowercase() in setOf("https", "wss")) { "远程登录需要 HTTPS 和 WSS 地址" }
    }
}

/** 将状态码转换为稳定的用户提示，不把响应正文或底层异常直接显示给用户。 */
class KtorAuthApi(private val client: HttpClient) : AuthApi {
    private suspend fun <T> call(block: suspend () -> T): T = try { block() } catch (error: ResponseException) {
        val status = error.response.status.value
        val code = try { error.response.body<ApiError>().code } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { "HTTP_ERROR" }
        val message = when (code) {
            "ACCOUNT_EXISTS" -> "账号已存在，请直接登录或更换账号"
            "INVALID_CREDENTIALS" -> "账号或密码错误"
            "UNAUTHENTICATED" -> "登录已失效，请重新登录"
            "RATE_LIMITED" -> "操作过于频繁，请一分钟后重试"
            "INVALID_REQUEST" -> "请检查账号、密码和昵称格式"
            else -> if (status == 404) "当前服务未启用认证，请启动 local 配置" else "认证服务暂不可用，请稍后重试"
        }
        throw AuthFailure(status, code, message)
    }
    override suspend fun register(settings: ServiceSettings, account: String, password: String, nickname: String) = call {
        client.post("${settings.forAuthentication().apiBaseUrl}/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(RegisterBody(account, password, nickname))
        }.body<UserProfile>()
        Unit
    }
    override suspend fun login(settings: ServiceSettings, account: String, password: String, deviceId: String): AuthTokens = call {
        client.post("${settings.forAuthentication().apiBaseUrl}/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(LoginBody(account, password, deviceId))
        }.body()
    }
    override suspend fun refresh(settings: ServiceSettings, refreshToken: String): AuthTokens = call {
        client.post("${settings.forAuthentication().apiBaseUrl}/api/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshBody(refreshToken))
        }.body()
    }
    override suspend fun logout(settings: ServiceSettings, refreshToken: String) {
        call {
            client.post("${settings.forAuthentication().apiBaseUrl}/api/auth/logout") {
                contentType(ContentType.Application.Json); setBody(RefreshBody(refreshToken))
            }
        }
    }
    override suspend fun ticket(settings: ServiceSettings, accessToken: String): ImTicket = call {
        client.post("${settings.forAuthentication().apiBaseUrl}/api/im/tickets") { bearerAuth(accessToken) }.body()
    }
}
