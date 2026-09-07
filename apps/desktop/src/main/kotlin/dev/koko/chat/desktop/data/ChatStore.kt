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
    data class Pending(val clientMsgId:String,val conversationId:String,val epoch:String,val text:String,val status:String,val error:String?)
    private fun db():ChatDatabase {
        database?.let { return it }
        Files.createDirectories(file.parent)
        return ChatDatabase(JdbcSqliteDriver("jdbc:sqlite:$file",Properties(),ChatDatabase.Schema).also { driver=it }).also { database=it }
    }
    suspend fun conversations():List<ConversationInfo> = withContext(dispatcher) {
        db().chatCacheQueries.conversations().executeAsList().map { json.decodeFromString<ConversationInfo>(it.payload) }
    }
    suspend fun saveConversations(values:List<ConversationInfo>) = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transaction {
            for(value in values) {
                val previous=q.conversation(value.id).executeAsOneOrNull()
                val same=previous?.epoch==value.membershipEpoch
                if(previous!=null && !same) { q.clearMessages(value.id);q.invalidatePending(value.id,value.membershipEpoch) }
                q.putConversation(value.id,value.membershipEpoch,json.encodeToString(value),if(same) previous.contiguous_seq else value.visibleFromSeq.toLong()-1)
            }
        }
    }
    suspend fun cursor(id:String):Long = withContext(dispatcher) { db().chatCacheQueries.conversation(id).executeAsOne().contiguous_seq }
    suspend fun saveMessages(id:String,epoch:String,messages:List<ChatMessage>):Long = withContext(dispatcher) {
        val db=db();val q=db.chatCacheQueries
        db.transactionWithResult {
            val conversation=q.conversation(id).executeAsOne()
            require(conversation.epoch==epoch) { "成员周期不一致，请重新同步" }
            val info=json.decodeFromString<ConversationInfo>(conversation.payload)
            for(message in messages) {
                require(message.conversationId==id && message.seq.toLong()>=info.visibleFromSeq.toLong())
                val old=q.messageAt(id,epoch,message.seq.toLong()).executeAsOneOrNull()
                require(old==null || old.id==message.id) { "同一序号收到不同消息" }
                q.putMessage(message.id,id,epoch,message.seq.toLong(),json.encodeToString(message))
                if(message.senderId==userId) q.removePending(message.clientMsgId)
            }
            var cursor=conversation.contiguous_seq
            // 乱序推送不能直接取 MAX(seq)，必须等缺口补齐后才推进连续游标。
            while(cursor<Long.MAX_VALUE && q.messageAt(id,epoch,cursor+1).executeAsOneOrNull()!=null) cursor++
            q.advance(cursor,id,epoch)
            cursor
        }
    }
    suspend fun messages(info:ConversationInfo):List<ChatMessage> = withContext(dispatcher) {
        db().chatCacheQueries.messages(info.id,info.membershipEpoch).executeAsList().map { json.decodeFromString<ChatMessage>(it.payload) }.reversed()
    }
    suspend fun enqueue(info:ConversationInfo,text:String):String = withContext(dispatcher) {
        require(text.isNotBlank() && text.toByteArray().size<=4096) { "消息不能为空，且最多 4096 UTF-8 字节" }
        val id=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        db().chatCacheQueries.putPending(id,info.id,info.membershipEpoch,text,now,now);id
    }
    suspend fun pending(dueOnly:Boolean=false):List<Pending> = withContext(dispatcher) {
        val q=db().chatCacheQueries
        if(dueOnly) q.duePending(System.currentTimeMillis()).executeAsList().map { Pending(it.client_msg_id,it.conversation_id,it.epoch,it.text,it.status,it.error) }
        else q.pending().executeAsList().map { Pending(it.client_msg_id,it.conversation_id,it.epoch,it.text,it.status,it.error) }
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
        db.transaction { q.clearMessages(id);q.invalidateAllPending(id);q.removeConversation(id) }
    }
    suspend fun close() = withContext(dispatcher) { driver?.close();driver=null;database=null }
}
