package dev.koko.chat.desktop.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.koko.chat.desktop.data.chat.generated.ChatDatabase
import dev.koko.chat.desktop.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** 独立账号消息库，所有 SQLite 操作在串行 IO 调度器执行；确认仅在事务提交后返回。 */
class ChatStore(directory:Path,server:String,private val userId:String,private val dispatcher:CoroutineDispatcher) {
    private val key=MessageDigest.getInstance("SHA-256").digest("$server\u0000$userId".toByteArray()).joinToString("") { "%02x".format(it) }
    private val file=directory.resolve("$key.db")
    private var driver:JdbcSqliteDriver?=null
    private var database:ChatDatabase?=null
    private val json=Json { ignoreUnknownKeys=true }
    data class Pending(val clientMsgId:String,val conversationId:String,val epoch:String,val text:String,val status:String,val error:String?,val attachmentId:String?=null)
    private fun db():ChatDatabase {
        database?.let { return it }
        Files.createDirectories(file.parent)
        return ChatDatabase(JdbcSqliteDriver("jdbc:sqlite:$file",Properties(),ChatDatabase.Schema).also { driver=it }).also { database=it }
    }
    suspend fun conversations():List<ConversationInfo> = withContext(dispatcher) {
        val q=db().chatCacheQueries
        q.conversations().executeAsList().map { row ->
            val info=json.decodeFromString<ConversationInfo>(row.payload)
            val local=q.pendingRead(info.id).executeAsOneOrNull()?.takeIf { it.epoch==info.membershipEpoch }?.read_seq ?: 0L
            val read=maxOf(info.lastReadSeq.toLong(),local)
            val incoming=q.messagesAfter(info.id,info.membershipEpoch,info.lastReadSeq.toLong()).executeAsList()
                .filter { json.decodeFromString<ChatMessage>(it.payload).senderId!=userId }
            // 快照计数减去本机新阅读的前缀，再加尚未进入快照的新推送；自己的消息不计未读。
            val newlyRead=incoming.count { it.seq<=read && it.seq<=info.latestSeq.toLong() }
            val newlyArrived=incoming.count { it.seq>maxOf(read,info.latestSeq.toLong()) }
            info.copy(lastReadSeq=read.toString(),unreadCount=maxOf(0L,info.unreadCount.toLong()-newlyRead+newlyArrived).toString())
        }
    }
    suspend fun saveConversations(values:List<ConversationInfo>) = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transaction {
            for(value in values) {
                val previous=q.conversation(value.id).executeAsOneOrNull()
                val same=previous?.epoch==value.membershipEpoch
                if(previous!=null && !same) { q.clearMessages(value.id);q.removeRead(value.id);q.invalidatePending(value.id,value.membershipEpoch) }
                val old=previous?.let { json.decodeFromString<ConversationInfo>(it.payload) }
                // HTTP 与推送可能交错：同周期读位置和快照上界均不允许回退。
                var accepted=if(same && (value.latestSeq.toLong()<old!!.latestSeq.toLong() || value.lastReadSeq.toLong()<old.lastReadSeq.toLong())) old else value
                if(same && old?.peerLastReadSeq!=null) accepted=accepted.copy(peerLastReadSeq=maxOf(old.peerLastReadSeq.toLong(),accepted.peerLastReadSeq?.toLong()?:0L).toString())
                q.putConversation(value.id,value.membershipEpoch,json.encodeToString(accepted),if(same) previous.contiguous_seq else value.visibleFromSeq.toLong()-1)
                val pending=q.pendingRead(value.id).executeAsOneOrNull()
                if(pending!=null && pending.epoch==accepted.membershipEpoch && pending.read_seq<=accepted.lastReadSeq.toLong()) q.removeRead(value.id)
            }
        }
    }
    suspend fun cursor(id:String):Long = withContext(dispatcher) { db().chatCacheQueries.conversation(id).executeAsOne().contiguous_seq }
    suspend fun saveMessages(id:String,epoch:String,messages:List<ChatMessage>):Long = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        val completed=mutableListOf<String>()
        val cursor=db.transactionWithResult {
            val conversation=q.conversation(id).executeAsOne()
            require(conversation.epoch==epoch) { "成员周期不一致，请重新同步" }
            val info=json.decodeFromString<ConversationInfo>(conversation.payload)
            for(message in messages) {
                require(message.conversationId==id && message.seq.toLong()>=info.visibleFromSeq.toLong())
                val old=q.messageAt(id,epoch,message.seq.toLong()).executeAsOneOrNull()
                require(old==null || old.id==message.id) { "同一序号收到不同消息" }
                q.putMessage(message.id,id,epoch,message.seq.toLong(),json.encodeToString(message))
                if(message.senderId==userId) {
                    if(q.upload(message.clientMsgId).executeAsOneOrNull()!=null) completed.add(message.clientMsgId)
                    q.removePending(message.clientMsgId);q.removeUpload(message.clientMsgId)
                }
            }
            var cursor=conversation.contiguous_seq
            // 乱序推送不能直接取 MAX(seq)，必须等缺口补齐后才推进连续游标。
            while(cursor<Long.MAX_VALUE && q.messageAt(id,epoch,cursor+1).executeAsOneOrNull()!=null) cursor++
            q.advance(cursor,id,epoch)
            cursor
        }
        // 只能在事务提交后删除本机副本，回滚时仍可重发；删除失败不影响已落盘的消息。
        for(upload in completed) runCatching { Files.deleteIfExists(uploadPath(upload)) }
        cursor
    }
    data class MessageWindow(val messages:List<ChatMessage>,val hasOlder:Boolean)
    /** 默认展示最近 200 条；用户展开历史后固定起点，刷新与新消息不能收回已加载的内容。 */
    suspend fun messageWindow(info:ConversationInfo,fromSeq:Long?=null):MessageWindow = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transactionWithResult {
            require(q.conversation(info.id).executeAsOneOrNull()?.epoch==info.membershipEpoch) { "成员周期不一致，请重新同步" }
            val messages=if(fromSeq==null) q.messages(info.id,info.membershipEpoch).executeAsList()
                .map { json.decodeFromString<ChatMessage>(it.payload) }.reversed()
            else q.messagesFrom(info.id,info.membershipEpoch,maxOf(fromSeq,info.visibleFromSeq.toLong())).executeAsList()
                .map { json.decodeFromString<ChatMessage>(it.payload) }
            val hasOlder=messages.firstOrNull()?.let {
                q.hasMessagesBefore(info.id,info.membershipEpoch,info.visibleFromSeq.toLong(),it.seq.toLong()).executeAsOne()
            } ?: false
            MessageWindow(messages,hasOlder)
        }
    }
    suspend fun messages(info:ConversationInfo):List<ChatMessage> = messageWindow(info).messages
    /** 向前查询至多一页，不修改本地消息、已读进度或设备接收游标。 */
    suspend fun olderMessages(info:ConversationInfo,beforeSeq:Long,limit:Int=50):List<ChatMessage> = withContext(dispatcher) {
        require(limit in 1..100 && beforeSeq>0) { "历史分页参数不合法" }
        val db=db();val q=db.chatCacheQueries
        db.transactionWithResult {
            require(q.conversation(info.id).executeAsOneOrNull()?.epoch==info.membershipEpoch) { "成员周期不一致，请重新同步" }
            q.olderMessages(info.id,info.membershipEpoch,info.visibleFromSeq.toLong(),beforeSeq,limit.toLong()).executeAsList()
                .map { json.decodeFromString<ChatMessage>(it.payload) }.reversed()
        }
    }
    suspend fun enqueue(info:ConversationInfo,text:String):String = withContext(dispatcher) {
        require(text.isNotBlank() && text.toByteArray().size<=4096) { "消息不能为空，且最多 4096 UTF-8 字节" }
        val id=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        db().chatCacheQueries.putPending(id,info.id,info.membershipEpoch,text,now,now);id
    }
    private fun uploadPath(id:String):Path {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return file.parent.resolve("$key-files").resolve("$id.upload")
    }
    /** 先复制文件再提交发送意图，后续用户修改或删除原文件也不会改变重试内容。 */
    suspend fun enqueueFile(info:ConversationInfo,path:Path,kind:String):String = withContext(dispatcher) {
        require(kind in setOf("IMAGE","FILE"))
        require(Files.isRegularFile(path) && Files.size(path) in 1..10*1024*1024) { "请选择 1 字节至 10 MiB 的文件" }
        val bytes=Files.newInputStream(path).use { it.readNBytes(10*1024*1024+1) }
        require(bytes.size in 1..10*1024*1024) { "文件大小已变化，请重新选择" }
        val name=path.fileName.toString()
        require(name.length<=180 && name.none { it.isISOControl() || it=='/' || it=='\\' }) { "文件名过长或含不支持的字符" }
        val id=UUID.randomUUID().toString();val target=uploadPath(id);Files.createDirectories(target.parent)
        val metadata=UploadCreate(id,info.membershipEpoch,name,bytes.size.toLong(),fileHash(bytes),kind)
        try {
            Files.write(target,bytes,java.nio.file.StandardOpenOption.CREATE_NEW)
            val db=db();val q=db.chatCacheQueries;val now=System.currentTimeMillis()
            db.transaction {
                require(q.conversation(info.id).executeAsOneOrNull()?.epoch==info.membershipEpoch) { "成员周期已变化，请重新选择" }
                q.putPending(id,info.id,info.membershipEpoch,(if(kind=="IMAGE") "[图片] " else "[文件] ")+name,now,now)
                q.setAttachment(id,id);q.putUpload(id,json.encodeToString(metadata))
            }
        } catch(error:Exception) { Files.deleteIfExists(target);throw error }
        id
    }
    data class Upload(val metadata:UploadCreate,val bytes:ByteArray)
    suspend fun upload(id:String):Upload = withContext(dispatcher) {
        val metadata=json.decodeFromString<UploadCreate>(db().chatCacheQueries.upload(id).executeAsOne())
        val bytes=Files.newInputStream(uploadPath(id)).use { it.readNBytes(10*1024*1024+1) }
        require(bytes.size.toLong()==metadata.size && fileHash(bytes)==metadata.sha256) { "本机附件副本损坏，请重新选择文件" }
        Upload(metadata,bytes)
    }
    suspend fun pending(dueOnly:Boolean=false):List<Pending> = withContext(dispatcher) {
        val q=db().chatCacheQueries
        if(dueOnly) q.duePending(System.currentTimeMillis()).executeAsList().map { Pending(it.client_msg_id,it.conversation_id,it.epoch,it.text,it.status,it.error,it.attachment_id) }
        else q.pending().executeAsList().map { Pending(it.client_msg_id,it.conversation_id,it.epoch,it.text,it.status,it.error,it.attachment_id) }
    }
    suspend fun retry(id:String,permanent:Boolean,error:String?) = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transaction {
            val pending=q.pendingById(id).executeAsOneOrNull()
            if(pending!=null) {
                val valid=q.conversation(pending.conversation_id).executeAsOneOrNull()?.epoch==pending.epoch
                q.retryPending(System.currentTimeMillis()+2000,if(permanent || !valid) "FAILED" else "PENDING",
                    if(valid) error else "已退出或成员周期变化，请重新发送",id)
            }
        }
    }
    /** 撤销访问后清除收到的缓存，保留失败发送意图；重入必须使用新周期重新同步。 */
    suspend fun removeConversation(id:String) = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transaction { q.clearMessages(id);q.removeRead(id);q.invalidateAllPending(id);q.removeConversation(id) }
    }
    data class PendingRead(val conversationId:String,val epoch:String,val seq:Long)
    /** 只有 UI 的可见消息回调才能创建阅读意图；同步、选中或收到消息都不能代替阅读。 */
    suspend fun markRead(id:String,epoch:String,visibleSeq:Long) = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transaction {
            val row=q.conversation(id).executeAsOneOrNull()
            if(row!=null && row.epoch==epoch) {
                val info=json.decodeFromString<ConversationInfo>(row.payload)
                val seq=minOf(visibleSeq,row.contiguous_seq)
                val pending=q.pendingRead(id).executeAsOneOrNull()?.read_seq ?: 0L
                if(seq>maxOf(info.lastReadSeq.toLong(),pending)) q.putRead(id,epoch,seq)
            }
        }
    }
    suspend fun pendingReads():List<PendingRead> = withContext(dispatcher) {
        db().chatCacheQueries.pendingReads().executeAsList().map { PendingRead(it.conversation_id,it.epoch,it.read_seq) }
    }
    /** READ_ACK/READ_UPDATE 不得恢复已退出会话或覆盖重入后的新成员周期。 */
    suspend fun saveReadSnapshot(info:ConversationInfo) = withContext(dispatcher) {
        if(db().chatCacheQueries.conversation(info.id).executeAsOneOrNull()?.epoch==info.membershipEpoch) saveConversations(listOf(info))
    }
    suspend fun close() = withContext(dispatcher) { driver?.close();driver=null;database=null }
}
