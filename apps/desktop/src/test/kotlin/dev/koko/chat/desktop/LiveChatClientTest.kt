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

/** 两个真实客户端走 RabbitMQ → Netty 链路，并验证确认丢失、离线补拉与本地落盘。 */
class LiveChatClientTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `real clients receive MQ push retry lost acknowledgement and recover offline messages`() {
        val prefix=System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX")
        assumeTrue("Use verify-desktop-auth.sh for full integration",!prefix.isNullOrEmpty())
        require(prefix!!.matches(Regex("dsk_[a-f0-9]{20}")))
        runBlocking {
            val settings=ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"),System.getenv("KOKO_CHAT_TEST_IM_URL"))
            val clientA=createHttpClient();val clientB=createHttpClient()
            val rawA=KtorImConnector(clientA);val wireB=KtorImConnector(clientB)
            val sentIds=mutableListOf<String>()
            // 故意丢弃第一次 SEND_ACK，并关闭甲的推送订阅，确保原编号真正经过一次重发。
            val wireA=object:ImConnector by rawA {
                override val events:Flow<ImEvent> = emptyFlow()
                override suspend fun request(sessionId:String,envelope:JsonObject):JsonObject {
                    val result=rawA.request(sessionId,envelope)
                    if(envelope["type"]?.jsonPrimitive?.content=="SEND") {
                        sentIds+=envelope.getValue("clientMsgId").jsonPrimitive.content
                        if(sentIds.size==1) error("Injected lost acknowledgement")
                    }
                    return result
                }
            }
            val apiA=KtorAuthApi(clientA);val apiB=KtorAuthApi(clientB)
            val deviceA=UUID.randomUUID().toString();val deviceB=UUID.randomUUID().toString()
            val alice=SessionManager(this,apiA,wireA) { deviceA };val bob=SessionManager(this,apiB,wireB) { deviceB }
            val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val chatA=ChatModel(this,alice,wireA,KtorChatApi(clientA),temp.root.toPath().resolve("a"),dispatcher)
            val chatB=ChatModel(this,bob,wireB,KtorChatApi(clientB),temp.root.toPath().resolve("b"),dispatcher)
            val pushed=CompletableDeferred<Unit>()
            val observer=launch { wireB.events.collect { if(it.envelope["type"]?.jsonPrimitive?.content=="MESSAGE") pushed.complete(Unit) } }
            try {
                alice.signIn(settings,"${prefix}c","Live-chat-password!","单聊甲",true)
                bob.signIn(settings,"${prefix}d","Live-chat-password!","单聊乙",true)
                withTimeout(20_000) { alice.state.first { it.phase==SessionState.ONLINE };bob.state.first { it.phase==SessionState.ONLINE } }
                chatA.create("${prefix}d")
                withTimeout(10_000) { chatA.state.first { it.selectedId!=null } }
                chatA.send("你好，来自 RabbitMQ 的消息")
                assertNotNull(withTimeoutOrNull(15_000) { pushed.await() },
                    "未收到 MQ 推送；甲=${chatA.state.value.notice} pending=${chatA.state.value.pending.map { it.status to it.error }} saved=${chatA.state.value.messages.size} sends=${sentIds.size}；乙=${chatB.state.value.notice}")
                withTimeout(15_000) { chatA.state.first { it.messages.size==1 && it.pending.isEmpty() };chatB.state.first { it.messages.size==1 } }
                assertTrue(sentIds.size>=2,"确认丢失后应实际重发")
                assertEquals(1,sentIds.take(2).distinct().size)
                assertEquals(chatA.state.value.messages.single().id,chatB.state.value.messages.single().id)
                bob.logout()
                withTimeout(5_000) { chatB.state.first { it.messages.isEmpty() } }
                chatA.send("你离线时发送的第二条消息")
                withTimeout(15_000) { chatA.state.first { it.messages.size==2 && it.pending.isEmpty() } }
                bob.signIn(settings,"${prefix}d","Live-chat-password!",null,false)
                withTimeout(20_000) { bob.state.first { it.phase==SessionState.ONLINE };chatB.state.first { it.messages.size==2 } }
                assertEquals(listOf("1","2"),chatB.state.value.messages.map { it.seq })
                assertEquals("你离线时发送的第二条消息",chatB.state.value.messages.last().text)
            } finally {
                observer.cancelAndJoin();chatA.close();chatB.close();alice.close();bob.close()
                clientA.close();clientB.close();clientA.coroutineContext[Job]?.join();clientB.coroutineContext[Job]?.join();dispatcher.close()
            }
        }
    }
}
