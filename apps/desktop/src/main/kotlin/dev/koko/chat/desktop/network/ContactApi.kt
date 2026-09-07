package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** 联系人只包含公开资料；所有 ID 使用字符串，与后端 Long 契约一致。 */
@Serializable data class FriendInfo(val id: String, val account: String, val nickname: String, val remark: String? = null)
@Serializable data class FriendRequestInfo(
    val id: String, val senderId: String, val receiverId: String,
    val senderAccount: String, val senderNickname: String, val receiverAccount: String, val receiverNickname: String,
    val greeting: String?, val status: String, val createdAt: String, val handledAt: String? = null,
)
@Serializable data class FriendPage(val friends: List<FriendInfo>, val nextCursor: String, val hasMore: Boolean)
@Serializable data class FriendRequestPage(val requests: List<FriendRequestInfo>, val nextCursor: String, val hasMore: Boolean)
@Serializable private data class FriendRequestBody(val account: String, val greeting: String)
@Serializable private data class ContactError(val code: String)
class ContactFailure(val code: String, message: String) : Exception(message)

interface ContactApi {
    suspend fun friends(settings: ServiceSettings, token: String, after: String): FriendPage
    suspend fun requests(settings: ServiceSettings, token: String, after: String): FriendRequestPage
    suspend fun apply(settings: ServiceSettings, token: String, account: String, greeting: String): FriendRequestInfo
    suspend fun decide(settings: ServiceSettings, token: String, id: String, accept: Boolean): FriendRequestInfo
}

/** 将业务错误映射为中文提示；网络结果不明确时交给刷新确认，不假装申请已经处理。 */
class KtorContactApi(private val client: HttpClient) : ContactApi {
    private suspend fun <T> call(block: suspend () -> T): T = try { block() } catch (error: ResponseException) {
        val code = try { error.response.body<ContactError>().code }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { "HTTP_ERROR" }
        val message = when (code) {
            "USER_NOT_FOUND" -> "没有找到该账号，请检查后重试"
            "SELF_REQUEST" -> "不能添加自己为好友"
            "ALREADY_FRIENDS" -> "你们已经是好友，可以直接发消息"
            "INCOMING_REQUEST_PENDING" -> "对方已发来申请，请在“收到的申请”中处理"
            "REQUEST_HANDLED" -> "申请已处理，请刷新列表"
            "REQUEST_NOT_FOUND" -> "申请不存在或当前账号无权处理"
            "UNAUTHENTICATED" -> "登录已失效，请重新登录"
            "INVALID_REQUEST" -> "账号为 3–32 位字母、数字或下划线，附言最多 255 字符"
            "RATE_LIMITED" -> "申请过于频繁，请一分钟后重试"
            else -> "联系人服务暂不可用，请稍后刷新"
        }
        throw ContactFailure(code, message)
    }
    override suspend fun friends(settings: ServiceSettings, token: String, after: String): FriendPage = call {
        client.get("${settings.forAuthentication().apiBaseUrl}/api/friends") {
            bearerAuth(token); parameter("afterId", after); parameter("limit", 100)
        }.body()
    }
    override suspend fun requests(settings: ServiceSettings, token: String, after: String): FriendRequestPage = call {
        client.get("${settings.forAuthentication().apiBaseUrl}/api/friend-requests") {
            bearerAuth(token); parameter("afterId", after); parameter("limit", 100)
        }.body()
    }
    override suspend fun apply(settings: ServiceSettings, token: String, account: String, greeting: String): FriendRequestInfo = call {
        client.post("${settings.forAuthentication().apiBaseUrl}/api/friend-requests") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(FriendRequestBody(account, greeting))
        }.body()
    }
    override suspend fun decide(settings: ServiceSettings, token: String, id: String, accept: Boolean): FriendRequestInfo = call {
        require(id.matches(Regex("[1-9][0-9]{0,18}")))
        client.post("${settings.forAuthentication().apiBaseUrl}/api/friend-requests/$id/${if (accept) "accept" else "reject"}") {
            bearerAuth(token)
        }.body()
    }
}
