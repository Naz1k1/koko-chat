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

/** 三个真实客户端验证阅读与接收分离、MQ 已读通知、跨设备共享及丢确认后的重试。 */
class LiveReadClientTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `read receipts cross devices retry lost acknowledgement and survive account reconnect`() {
        val prefix=System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX")
        assumeTrue("Use verify-desktop-auth.sh for full integration",!prefix.isNullOrEmpty())
        require(prefix!!.matches(Regex("dsk_[a-f0-9]{20}")))
        runBlocking {
            val settings=ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"),System.getenv("KOKO_CHAT_TEST_IM_URL"))
            val clients=List(3) { createHttpClient() };val wires=clients.map { KtorImConnector(it) }
            val reads=mutableListOf<String>();var blockRead=false;var dropPeerHints=false
            val bobWire=object:ImConnector by wires[1] {
                // 防止通知或周期快照代替重试，让第一次 READ_ACK 丢失确实留下待确认意图。
                override val events=wires[1].events.filter { it.envelope["type"]?.jsonPrimitive?.content!="READ_UPDATE" }
                override suspend fun request(sessionId:String,envelope:JsonObject):JsonObject {
                    if(envelope["type"]?.jsonPrimitive?.content=="READ") {
                        if(blockRead) error("Injected disconnected read path")
                        val result=wires[1].request(sessionId,envelope)
                        reads+=envelope.getValue("readSeq").jsonPrimitive.content
                        if(reads.size==1) error("Injected lost READ_ACK")
                        return result
                    }
                    return wires[1].request(sessionId,envelope)
                }
            }
            val bobApiRaw=KtorChatApi(clients[1])
            val bobApi=object:ChatApi by bobApiRaw {
                override suspend fun conversations(settings:ServiceSettings,token:String,after:String):ConversationPage {
                    val page=bobApiRaw.conversations(settings,token,after)
                    return if(reads.size==1) page.copy(conversations=page.conversations.map { it.copy(lastReadSeq="0",unreadCount="1") }) else page
                }
            }
            fun peerWire(index:Int)=object:ImConnector by wires[index] {
                override val events=wires[index].events.filter { !dropPeerHints || it.envelope["type"]?.jsonPrimitive?.content!="READ_UPDATE" }
            }
            val connectors=listOf(peerWire(0),bobWire,peerWire(2));val devices=List(3) { UUID.randomUUID().toString() }
            val sessions=clients.indices.map { index -> SessionManager(this,KtorAuthApi(clients[index]),connectors[index]) { devices[index] } }
            val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val chats=clients.indices.map { index -> ChatModel(this,sessions[index],connectors[index],if(index==1) bobApi else KtorChatApi(clients[index]),temp.root.toPath().resolve("device-$index"),dispatcher) }
            val senderHint=CompletableDeferred<Unit>();val secondDeviceHint=CompletableDeferred<Unit>()
            val observers=listOf(0 to senderHint,2 to secondDeviceHint).map { (index,signal) -> launch {
                wires[index].events.collect { if(it.envelope["type"]?.jsonPrimitive?.content=="READ_UPDATE") signal.complete(Unit) }
            } }
            suspend fun waitMessages(index:Int,count:Int) = withTimeout(15_000) { chats[index].state.first { it.messages.size==count && it.pending.isEmpty() } }
            try {
                sessions[0].signIn(settings,"${prefix}h","Live-read-password!","已读甲",true)
                sessions[1].signIn(settings,"${prefix}i","Live-read-password!","已读乙",true)
                withTimeout(20_000) { sessions[0].state.first { it.phase==SessionState.ONLINE };sessions[1].state.first { it.phase==SessionState.ONLINE } }
                sessions[2].signIn(settings,"${prefix}i","Live-read-password!",null,false)
                withTimeout(20_000) { sessions[2].state.first { it.phase==SessionState.ONLINE } }
                chats[0].create("${prefix}i")
                withTimeout(10_000) { chats[0].state.first { it.selectedId!=null } }
                chats[0].send("只收到，尚未阅读")
                waitMessages(0,1);waitMessages(1,1);waitMessages(2,1)
                val bob=chats[1].state.value.conversations.single()
                assertEquals("1",bob.unreadCount);assertEquals("0",bob.lastReadSeq,"自动选中和后台补拉不得自动已读")
                chats[1].send("自己的消息不计未读")
                waitMessages(0,2);waitMessages(1,2);waitMessages(2,2)
                assertEquals("1",chats[1].state.value.conversations.single().unreadCount)
                chats[1].readVisible(bob.id,bob.membershipEpoch,2)
                withTimeout(5_000) { chats[1].state.first { it.conversations.single().unreadCount=="0" } }
                withTimeout(15_000) { senderHint.await();secondDeviceHint.await() }
                withTimeout(15_000) {
                    chats[0].state.first { it.conversations.single().peerLastReadSeq=="2" }
                    chats[2].state.first { it.conversations.single().lastReadSeq=="2" && it.conversations.single().unreadCount=="0" }
                    while(reads.size<2) delay(50)
                }
                assertEquals(listOf("2","2"),reads.take(2),"确认丢失后重复 READ 必须保持原进度")
                // 暂停乙的 READ 链路，先在本地阅读，再退出重登以验证意图持久化恢复。
                blockRead=true;dropPeerHints=true // 第二轮丢弃提示，必须由 HTTP 周期快照修复其他设备。
                chats[0].send("退出重登仍应补报已读")
                waitMessages(0,3);waitMessages(1,3);waitMessages(2,3)
                chats[1].readVisible(bob.id,bob.membershipEpoch,3)
                withTimeout(5_000) { chats[1].state.first { it.conversations.single().lastReadSeq=="3" } }
                assertEquals("2",sessions[1].withAccess { service,token -> bobApiRaw.summary(service,token,bob.id) }.lastReadSeq)
                sessions[1].logout()
                withTimeout(5_000) { chats[1].state.first { it.conversations.isEmpty() } }
                blockRead=false
                sessions[1].signIn(settings,"${prefix}i","Live-read-password!",null,false)
                withTimeout(20_000) { sessions[1].state.first { it.phase==SessionState.ONLINE } }
                withTimeout(15_000) {
                    chats[0].state.first { it.conversations.single().peerLastReadSeq=="3" }
                    chats[2].state.first { it.conversations.single().lastReadSeq=="3" && it.conversations.single().unreadCount=="0" }
                }
                assertEquals("3",sessions[1].withAccess { service,token -> bobApiRaw.summary(service,token,bob.id) }.lastReadSeq)
                assertEquals("1",chats[0].state.value.conversations.single().unreadCount,"对方已读不能清除自己的未读")
            } finally {
                observers.forEach { it.cancelAndJoin() };chats.forEach { it.close() };sessions.forEach { it.close() }
                clients.forEach { it.close() };clients.forEach { it.coroutineContext[Job]?.join() };dispatcher.close()
            }
        }
    }
}
