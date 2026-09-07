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
}
