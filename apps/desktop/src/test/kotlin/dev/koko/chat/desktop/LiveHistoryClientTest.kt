package dev.koko.chat.desktop

import dev.koko.chat.desktop.chat.ChatModel
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

/** 通过真实 Netty 保存 325 条消息，验证 MQ 到达、HTTP 补拉、本地历史分页及已读隔离。 */
class LiveHistoryClientTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `real history crosses two hundred messages without gaps duplicates or implicit reads`() {
        val prefix=System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX")
        assumeTrue("Use verify-desktop-auth.sh for full integration",!prefix.isNullOrEmpty())
        require(prefix!!.matches(Regex("dsk_[a-f0-9]{20}")))
        runBlocking {
            val settings=ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"),System.getenv("KOKO_CHAT_TEST_IM_URL"))
            val clients=List(2) { createHttpClient() };val wires=clients.map { KtorImConnector(it) }
            val devices=List(2) { UUID.randomUUID().toString() }
            val sessions=clients.indices.map { i -> SessionManager(this,KtorAuthApi(clients[i]),wires[i]) { devices[i] } }
            val api=KtorChatApi(clients[1]);val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val chat=ChatModel(this,sessions[1],wires[1],api,temp.root.toPath(),dispatcher)
            val pushed=mutableSetOf<String>()
            val observer=launch { wires[1].events.collect { event ->
                if(event.envelope["type"]?.jsonPrimitive?.content=="MESSAGE") pushed+=event.envelope.getValue("message").jsonObject.getValue("seq").jsonPrimitive.content
            } }
            try {
                sessions[0].signIn(settings,"${prefix}j","Live-history-password!","历史甲",true)
                sessions[1].signIn(settings,"${prefix}k","Live-history-password!","历史乙",true)
                withTimeout(20_000) { sessions.forEach { session -> session.state.first { it.phase==SessionState.ONLINE } } }
                val conversation=sessions[0].withAccess { service,token -> KtorChatApi(clients[0]).direct(service,token,"${prefix}k") }
                withTimeout(30_000) {
                    for(seq in 1..325) {
                        val result=wires[0].request(sessions[0].state.value.sessionId!!,buildJsonObject {
                            put("type","SEND");put("conversationId",conversation.id);put("membershipEpoch",conversation.membershipEpoch)
                            put("clientMsgId",UUID.randomUUID().toString());put("text","历史消息 $seq")
                        })
                        assertEquals(seq.toString(),result.getValue("message").jsonObject.getValue("seq").jsonPrimitive.content)
                    }
                }
                chat.refresh()
                withTimeout(20_000) { chat.state.first { it.messages.lastOrNull()?.seq=="325" && it.messages.size==200 } }
                assertEquals((126..325).map(Int::toString),chat.state.value.messages.map { it.seq })
                for(size in listOf(250,300,325)) {
                    chat.loadOlder();chat.loadOlder()
                    withTimeout(5_000) { chat.state.first { it.messages.size==size && !it.loadingOlder } }
                }
                assertEquals((1..325).map(Int::toString),chat.state.value.messages.map { it.seq })
                assertFalse(chat.state.value.hasOlderMessages)
                chat.refresh()
                withTimeout(10_000) { chat.state.first { it.notice=="消息已同步" } }
                assertEquals(325,chat.state.value.messages.size)
                val summary=sessions[1].withAccess { service,token -> api.summary(service,token,conversation.id) }
                assertEquals("0",summary.lastReadSeq);assertEquals("325",summary.unreadCount)
                chat.showLatest()
                withTimeout(5_000) { chat.state.first { it.latestRevision==1L } }
                assertEquals((126..325).map(Int::toString),chat.state.value.messages.map { it.seq })
                // 等待本批 MQ 通知到达后才结束，避免清理数据库时还留有本批待分发责任。
                withTimeout(60_000) { while(pushed.size<325) delay(100) }
                assertEquals((1..325).map(Int::toString).toSet(),pushed)
            } finally {
                observer.cancelAndJoin();chat.close();sessions.forEach { it.close() }
                clients.forEach { it.close() };clients.forEach { it.coroutineContext[Job]?.join() };dispatcher.close()
            }
        }
    }
}
