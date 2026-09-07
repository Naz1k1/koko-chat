package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/** 单聊传输契约；排序和比较时将 seq 转为 Long，不能按字符串字典序排序。 */
@Serializable data class ConversationInfo(val id:String,val peerId:String?,val account:String?,val nickname:String,
    val membershipEpoch:String,val visibleFromSeq:String,val latestSeq:String,val type:String="DIRECT",val ownerId:String?=null,
    val lastReadSeq:String="0",val unreadCount:String="0",val peerLastReadSeq:String?=null)
@Serializable data class ConversationPage(val conversations:List<ConversationInfo>,val nextCursor:String,val hasMore:Boolean)
@Serializable data class ChatMessage(val id:String,val conversationId:String,val seq:String,val senderId:String,
    val clientMsgId:String,val type:String,val text:String,val serverTime:String)
@Serializable data class MessagePage(val messages:List<ChatMessage>,val membershipEpoch:String,val visibleFromSeq:String,
    val toSeq:String,val nextCursor:String,val hasMore:Boolean)
@Serializable private data class DirectBody(val account:String)
interface ChatApi {
    suspend fun summary(settings:ServiceSettings,token:String,id:String):ConversationInfo
    suspend fun conversations(settings:ServiceSettings,token:String,after:String):ConversationPage
    suspend fun direct(settings:ServiceSettings,token:String,account:String):ConversationInfo
    suspend fun history(settings:ServiceSettings,token:String,id:String,after:String,to:String):MessagePage
}
class KtorChatApi(private val client:HttpClient):ChatApi {
    override suspend fun summary(settings:ServiceSettings,token:String,id:String):ConversationInfo = client.get("${settings.forAuthentication().apiBaseUrl}/api/conversations/$id") { bearerAuth(token) }.body()
    override suspend fun conversations(settings:ServiceSettings,token:String,after:String):ConversationPage = client.get("${settings.forAuthentication().apiBaseUrl}/api/conversations") {
        bearerAuth(token);parameter("afterId",after);parameter("limit",100)
    }.body()
    override suspend fun direct(settings:ServiceSettings,token:String,account:String):ConversationInfo = client.post("${settings.forAuthentication().apiBaseUrl}/api/conversations/direct") {
        bearerAuth(token);contentType(ContentType.Application.Json);setBody(DirectBody(account))
    }.body()
    override suspend fun history(settings:ServiceSettings,token:String,id:String,after:String,to:String):MessagePage = client.get("${settings.forAuthentication().apiBaseUrl}/api/conversations/$id/messages") {
        bearerAuth(token);parameter("afterSeq",after);parameter("toSeq",to);parameter("limit",50)
    }.body()
}
