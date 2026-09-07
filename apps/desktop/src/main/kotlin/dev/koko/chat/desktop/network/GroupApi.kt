package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** 操作对象在重试期间保持不变，服务端按当前用户和 clientCommandId 去重。 */
data class GroupOperation(val kind: String, val groupId: String? = null, val title: String? = null,
    val memberIds: List<String> = emptyList(), val membershipEpoch: String? = null,
    val targetUserId: String? = null, val targetEpoch: String? = null,
    val clientCommandId: String = UUID.randomUUID().toString())
@Serializable data class GroupResult(val groupId: String, val clientCommandId: String)
@Serializable data class GroupMember(val userId: String, val account: String, val nickname: String, val role: String, val membershipEpoch: String)
@Serializable data class GroupDetail(val id: String, val title: String, val ownerId: String, val membershipEpoch: String, val members: List<GroupMember>)
@Serializable private data class GroupError(val code: String)
class GroupFailure(val status: Int, val code: String, message: String) : Exception(message)
interface GroupApi {
    suspend fun detail(settings: ServiceSettings, token: String, id: String): GroupDetail
    suspend fun execute(settings: ServiceSettings, token: String, operation: GroupOperation): GroupResult
}
class KtorGroupApi(private val client: HttpClient) : GroupApi {
    private suspend fun <T> call(block: suspend () -> T): T = try { block() } catch(error: ResponseException) {
        val code = try { error.response.body<GroupError>().code } catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { "HTTP_ERROR" }
        val message = when(code) {
            "FRIEND_REQUIRED" -> "只能邀请自己的有效好友，请刷新联系人"
            "OWNER_REQUIRED" -> "只有群主可以执行此操作"
            "OWNER_CANNOT_LEAVE" -> "群主不能直接退出，可选择解散群聊"
            "GROUP_FULL" -> "群成员已达到 200 人上限"
            "MEMBERSHIP_CHANGED" -> "成员状态已变化，请刷新后操作"
            "GROUP_NOT_FOUND", "NOT_A_MEMBER" -> "群聊已不可用，或你已不在群中"
            "COMMAND_CONFLICT" -> "操作编号与参数不一致，请刷新后重新操作"
            "INVALID_REQUEST" -> "请检查群名、成员选择和操作参数"
            "UNAUTHENTICATED" -> "登录已失效，请重新登录"
            else -> "群操作暂未完成，请稍后重试"
        }
        throw GroupFailure(error.response.status.value,code,message)
    }
    override suspend fun detail(settings: ServiceSettings, token: String, id: String): GroupDetail = call {
        require(id.matches(Regex("[1-9][0-9]{0,18}")))
        client.get("${settings.forAuthentication().apiBaseUrl}/api/groups/$id") { bearerAuth(token) }.body()
    }
    override suspend fun execute(settings: ServiceSettings, token: String, operation: GroupOperation): GroupResult = call {
        val suffix = when(operation.kind) {
            "CREATE" -> ""
            "INVITE" -> "/${operation.groupId}/members"
            "REMOVE" -> "/${operation.groupId}/members/${operation.targetUserId}/remove"
            "LEAVE" -> "/${operation.groupId}/leave"
            "CLOSE" -> "/${operation.groupId}/close"
            else -> error("Unknown group operation")
        }
        client.post("${settings.forAuthentication().apiBaseUrl}/api/groups$suffix") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("clientCommandId",operation.clientCommandId)
                operation.membershipEpoch?.let { put("membershipEpoch",it) }
                operation.targetEpoch?.let { put("targetEpoch",it) }
                if(operation.kind=="CREATE") put("title",operation.title)
                if(operation.kind in setOf("CREATE","INVITE")) put("memberIds",JsonArray(operation.memberIds.map(::JsonPrimitive)))
            })
        }.body()
    }
}
