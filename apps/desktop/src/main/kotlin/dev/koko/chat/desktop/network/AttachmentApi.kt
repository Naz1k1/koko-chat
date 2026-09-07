package dev.koko.chat.desktop.network

import dev.koko.chat.desktop.config.ServiceSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** 附件字节走认证 HTTP；消息只携带不可变引用，S3 密钥始终留在后端。 */
@Serializable data class AttachmentReference(val id:String,val name:String,val size:Long,val sha256:String,val kind:String,val contentType:String)
@Serializable data class UploadCreate(val clientUploadId:String,val membershipEpoch:String,val name:String,val size:Long,val sha256:String,val kind:String)
@Serializable data class UploadView(val attachment:AttachmentReference,val status:String)
interface AttachmentApi {
    suspend fun create(settings:ServiceSettings,token:String,conversationId:String,body:UploadCreate):UploadView
    suspend fun upload(settings:ServiceSettings,token:String,id:String,bytes:ByteArray):UploadView
    suspend fun download(settings:ServiceSettings,token:String,attachment:AttachmentReference):ByteArray
    suspend fun thumbnail(settings:ServiceSettings,token:String,id:String):ByteArray? = null
}
internal fun fileHash(bytes:ByteArray):String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
class KtorAttachmentApi(private val client:HttpClient):AttachmentApi {
    override suspend fun create(settings:ServiceSettings,token:String,conversationId:String,body:UploadCreate):UploadView =
        client.post("${settings.forAuthentication().apiBaseUrl}/api/conversations/$conversationId/attachments") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(body)
        }.body()
    override suspend fun upload(settings:ServiceSettings,token:String,id:String,bytes:ByteArray):UploadView =
        client.put("${settings.forAuthentication().apiBaseUrl}/api/attachments/$id/content") {
            bearerAuth(token);contentType(ContentType.Application.OctetStream);setBody(bytes)
            timeout { requestTimeoutMillis=60_000;socketTimeoutMillis=30_000 }
        }.body()
    override suspend fun thumbnail(settings:ServiceSettings,token:String,id:String):ByteArray =
        client.prepareGet("${settings.forAuthentication().apiBaseUrl}/api/attachments/$id/thumbnail") { bearerAuth(token) }.execute { response ->
            val expected=response.headers["X-Content-SHA256"] ?: error("缺少缩略图校验值")
            val channel=response.bodyAsChannel();val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val count=channel.readAvailable(buffer,0,buffer.size);if(count<0) break;require(output.size()+count<=131072);output.write(buffer,0,count) }
            output.toByteArray().also { require(fileHash(it)==expected) }
        }
    override suspend fun download(settings:ServiceSettings,token:String,attachment:AttachmentReference):ByteArray {
        require(attachment.size in 1..10*1024*1024) { "附件大小超出限制" }
        val bytes=client.prepareGet("${settings.forAuthentication().apiBaseUrl}/api/attachments/${attachment.id}/content") {
            bearerAuth(token);timeout { requestTimeoutMillis=60_000;socketTimeoutMillis=30_000 }
        }.execute { response ->
            val channel=response.bodyAsChannel();val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            // 流式读取并在超过声明大小时停止，不能先把未知响应整体装入内存。
            while(true) {
                val count=channel.readAvailable(buffer,0,buffer.size)
                if(count<0) break
                require(output.size().toLong()+count<=attachment.size) { "下载大小超出声明" }
                output.write(buffer,0,count)
            }
            output.toByteArray()
        }
        require(bytes.size.toLong()==attachment.size && fileHash(bytes)==attachment.sha256) { "文件校验失败，请重新下载" }
        return bytes
    }
}
