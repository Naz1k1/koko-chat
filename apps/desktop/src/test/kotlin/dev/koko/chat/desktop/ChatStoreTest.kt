package dev.koko.chat.desktop

import dev.koko.chat.desktop.data.ChatStore
import dev.koko.chat.desktop.network.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import kotlin.test.*

/** 验证 SQLite 乱序去重、连续游标、待发送重开及账号隔离，不用内存列表冒充持久化。 */
class ChatStoreTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `persistent cache keeps gaps pending intents and accounts separate`():Unit = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val folder=temp.root.toPath()
        val info=ConversationInfo("10","2","bob","小波","epoch-1","1","2")
        var store=ChatStore(folder,"http://localhost","1",dispatcher)
        try {
            store.saveConversations(listOf(info))
            val intent=store.enqueue(info,"待发送内容")
            store.close();store=ChatStore(folder,"http://localhost","1",dispatcher)
            assertEquals(intent,store.pending().single().clientMsgId)
            val second=ChatMessage("102","10","2","2",intent,"TEXT","对方消息","2026-09-08T00:00:00Z")
            assertEquals(0,store.saveMessages("10","epoch-1",listOf(second)))
            assertEquals(1,store.pending().size) // 对方碰巧复用编号，不能删除本机待发送消息。
            val first=second.copy(id="101",seq="1",senderId="1",text="待发送内容")
            assertEquals(2,store.saveMessages("10","epoch-1",listOf(first)))
            assertEquals(2,store.saveMessages("10","epoch-1",listOf(first,second)))
            assertTrue(store.pending().isEmpty());assertEquals(2,store.messages(info).size)
            val another=ChatStore(folder,"http://localhost","2",dispatcher)
            try { assertTrue(another.conversations().isEmpty()) } finally { another.close() }
            val pending=store.enqueue(info,"旧周期发送")
            store.saveConversations(listOf(info.copy(membershipEpoch="epoch-2",visibleFromSeq="5")))
            assertEquals(4,store.cursor("10"));assertEquals("FAILED",store.pending().single { it.clientMsgId==pending }.status)
            assertFailsWith<IllegalArgumentException> { store.saveMessages("10","epoch-1",listOf(first)) }
            store.removeConversation("10")
            assertTrue(store.conversations().isEmpty())
            store.retry(pending,false,null) // 迟到的网络失败不能把已失去权限的意图恢复为自动重试。
            assertEquals("FAILED",store.pending().single().status)
            store.saveConversations(listOf(info.copy(membershipEpoch="epoch-3",visibleFromSeq="6")))
            assertTrue(store.messages(info.copy(membershipEpoch="epoch-3")).isEmpty())
            assertEquals(5,store.cursor("10"))
        } finally { store.close();dispatcher.close() }
    }
    @Test fun `read intents survive restart merge snapshots and reset on membership change`():Unit = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var store=ChatStore(temp.root.toPath(),"http://localhost","1",dispatcher)
        val info=ConversationInfo("10","2","bob","小波","epoch-1","1","2",lastReadSeq="0",unreadCount="1",peerLastReadSeq="0")
        val one=ChatMessage("101","10","1","2","one","TEXT","收到的消息","2026-09-08T00:00:00Z")
        val two=one.copy(id="102",seq="2",senderId="1",clientMsgId="two")
        try {
            store.saveConversations(listOf(info))
            store.saveMessages("10","epoch-1",listOf(two))
            store.markRead("10","epoch-1",2)
            assertTrue(store.pendingReads().isEmpty(),"缺口未补齐时不能越过连续游标")
            store.saveMessages("10","epoch-1",listOf(one))
            store.markRead("10","epoch-1",99)
            assertEquals(2,store.pendingReads().single().seq)
            assertEquals("0",store.conversations().single().unreadCount)
            store.close();store=ChatStore(temp.root.toPath(),"http://localhost","1",dispatcher)
            assertEquals(2,store.pendingReads().single().seq)
            val newer=one.copy(id="103",seq="3",clientMsgId="three")
            store.saveMessages("10","epoch-1",listOf(newer))
            assertEquals("1",store.conversations().single().unreadCount,"快照以外的新消息应立即计未读")
            store.saveReadSnapshot(info.copy(latestSeq="3",lastReadSeq="2",unreadCount="1",peerLastReadSeq="2"))
            assertTrue(store.pendingReads().isEmpty())
            store.saveReadSnapshot(info)
            assertEquals("2",store.conversations().single().lastReadSeq,"乱序快照不能回退已读")
            assertEquals("2",store.conversations().single().peerLastReadSeq)
            assertEquals(3,store.cursor("10"),"阅读更新不能改变接收进度")
            store.markRead("10","epoch-1",3)
            store.saveConversations(listOf(info.copy(membershipEpoch="epoch-2",visibleFromSeq="4",latestSeq="3",lastReadSeq="3",unreadCount="0")))
            assertTrue(store.pendingReads().isEmpty())
            store.saveReadSnapshot(info.copy(lastReadSeq="2"))
            assertEquals("epoch-2",store.conversations().single().membershipEpoch)
            store.removeConversation("10")
            store.saveReadSnapshot(info)
            assertTrue(store.conversations().isEmpty(),"迟到的 READ_ACK 不得复活已退出的会话")
        } finally { store.close();dispatcher.close() }
    }

    @Test fun `version one cache upgrades without losing existing messages`():Unit = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var store=ChatStore(temp.root.toPath(),"http://localhost","1",dispatcher)
        val info=ConversationInfo("10","2","bob","小波","epoch-1","1","1")
        try {
            store.saveConversations(listOf(info))
            store.saveMessages("10","epoch-1",listOf(ChatMessage("101","10","1","2","one","TEXT","旧缓存","2026-09-08T00:00:00Z")))
            store.close()
            val file=java.nio.file.Files.list(temp.root.toPath()).use { it.filter { path -> path.toString().endsWith(".db") }.findFirst().orElseThrow() }
            // 恢复升级前真实的 v1 表结构与 user_version，再通过生产驱动打开迁移。
            java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { statement -> statement.execute("DROP TABLE pending_read");statement.execute("DROP TABLE pending_upload");statement.execute("ALTER TABLE pending_message DROP COLUMN attachment_id");statement.execute("PRAGMA user_version=1") }
            }
            store=ChatStore(temp.root.toPath(),"http://localhost","1",dispatcher)
            assertEquals("旧缓存",store.messages(info).single().text)
            store.markRead("10","epoch-1",1)
            assertEquals(1,store.pendingReads().single().seq)
            assertEquals(1,store.cursor("10"))
        } finally { store.close();dispatcher.close() }
    }

    @Test fun `attachment copy survives restart and source deletion until own acknowledgement`():Unit = runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var store=ChatStore(temp.root.toPath(),"http://localhost","1",dispatcher)
        val info=ConversationInfo("10","2","bob","小波","epoch-1","1","2")
        val source=temp.root.toPath().resolve("测试文件.bin");val bytes=ByteArray(16384) { (it%127).toByte() }
        java.nio.file.Files.write(source,bytes)
        try {
            store.saveConversations(listOf(info));val id=store.enqueueFile(info,source,"FILE")
            java.nio.file.Files.delete(source);store.close();store=ChatStore(temp.root.toPath(),"http://localhost","1",dispatcher)
            assertEquals(id,store.pending().single().attachmentId);assertContentEquals(bytes,store.upload(id).bytes)
            val peer=ChatMessage("101","10","1","2",id,"TEXT","对方碰巧使用同编号","2026-09-08T00:00:00Z")
            store.saveMessages("10","epoch-1",listOf(peer));assertContentEquals(bytes,store.upload(id).bytes)
            val metadata=store.upload(id).metadata
            val own=peer.copy(id="102",seq="2",senderId="1",type="FILE",attachment=AttachmentReference(id,metadata.name,metadata.size,metadata.sha256,"FILE","application/octet-stream"))
            store.saveMessages("10","epoch-1",listOf(own));assertTrue(store.pending().isEmpty())
            assertEquals(0,java.nio.file.Files.walk(temp.root.toPath()).use { it.filter { file -> file.toString().endsWith(".upload") }.count() })
            java.nio.file.Files.write(source,bytes);val stale=store.enqueueFile(info,source,"FILE")
            store.saveConversations(listOf(info.copy(membershipEpoch="epoch-2",visibleFromSeq="3")))
            store.retry(stale,false,null);assertEquals("FAILED",store.pending().single().status)
            assertContentEquals(bytes,store.upload(stale).bytes)
        } finally { store.close();dispatcher.close() }
    }

}
