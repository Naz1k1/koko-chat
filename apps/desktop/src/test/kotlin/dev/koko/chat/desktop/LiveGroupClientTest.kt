package dev.koko.chat.desktop

import dev.koko.chat.desktop.chat.ChatModel
import dev.koko.chat.desktop.group.GroupModel
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

/** 三个真实客户端验证 MQ 群扇出、移除后拒绝发送、重新入群及旧缓存清理。 */
class LiveGroupClientTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `group commands recover lost response and three clients obey membership boundaries`() {
        val prefix=System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX")
        assumeTrue("Use verify-desktop-auth.sh",!prefix.isNullOrEmpty())
        require(prefix!!.matches(Regex("dsk_[a-f0-9]{20}")))
        runBlocking {
            val settings=ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"),System.getenv("KOKO_CHAT_TEST_IM_URL"))
            val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val clients=List(3) { createHttpClient() }
            val wires=clients.map(::KtorImConnector)
            val sessions=clients.mapIndexed { i,client -> SessionManager(this,KtorAuthApi(client),wires[i]) { "group-device-$i" } }
            val chats=clients.mapIndexed { i,client -> ChatModel(this,sessions[i],wires[i],KtorChatApi(client),temp.root.toPath().resolve("account-$i"),dispatcher) }
            val raw=KtorGroupApi(clients[0]);val createIds=mutableListOf<String>()
            val lost=object:GroupApi by raw {
                override suspend fun execute(settings:ServiceSettings,token:String,operation:GroupOperation):GroupResult {
                    val result=raw.execute(settings,token,operation)
                    if(operation.kind=="CREATE") {
                        createIds+=operation.clientCommandId
                        if(createIds.size==1) error("Injected response loss after group commit")
                    }
                    return result
                }
            }
            val models=clients.mapIndexed { i,client -> GroupModel(this,sessions[i],chats[i],if(i==0) lost else KtorGroupApi(client)) }
            val observed=List(3) { mutableListOf<String>() }
            val observers=wires.mapIndexed { i,wire -> launch { wire.events.collect { event ->
                event.envelope["message"]?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { observed[i]+=it }
            } } }
            try {
                for(i in 0..2) sessions[i].signIn(settings,"$prefix${('e'.code+i).toChar()}","Live-group-password!","群成员$i",true)
                withTimeout(20_000) { sessions.forEach { session -> session.state.first { it.phase==SessionState.ONLINE } } }
                for(i in 1..2) {
                    val request=sessions[0].withAccess { address,token -> KtorContactApi(clients[0]).apply(address,token,sessions[i].state.value.user!!.account,"群聊测试") }
                    sessions[i].withAccess { address,token -> KtorContactApi(clients[i]).decide(address,token,request.id,true) }
                }
                models[0].create("三人测试群",sessions.drop(1).map { it.state.value.user!!.id })
                withTimeout(10_000) { models[0].state.first { it.pending!=null && !it.busy } }
                models[0].retry()
                val id=withTimeout(10_000) { models[0].state.first { it.lastGroupId!=null && !it.busy } }.lastGroupId!!
                assertEquals(2,createIds.size);assertEquals(1,createIds.distinct().size)
                withTimeout(10_000) { chats[0].state.first { it.selectedId==id };models[0].state.first { it.detail?.members?.size==3 } }
                chats[0].send("三人均可接收")
                withTimeout(15_000) {
                    while(observed[1].none { it=="三人均可接收" } || observed[2].none { it=="三人均可接收" }) delay(50)
                    chats.drop(1).forEach { chat -> chat.state.first { it.messages.size==1 } }
                }
                val oldMember=models[0].state.value.detail!!.members.first { it.userId==sessions[1].state.value.user!!.id }
                models[0].remove(oldMember)
                withTimeout(10_000) { models[0].state.first { !it.busy && it.detail?.members?.size==2 } }
                chats[1].refresh()
                withTimeout(10_000) { chats[1].state.first { it.conversations.none { item -> item.id==id } && it.messages.isEmpty() } }
                val rejected=assertFailsWith<ImCommandFailure> {
                    wires[1].request(sessions[1].state.value.sessionId!!,buildJsonObject {
                        put("type","SEND");put("conversationId",id);put("membershipEpoch",oldMember.membershipEpoch)
                        put("clientMsgId",UUID.randomUUID().toString());put("text","已被移除的发送")
                    })
                }
                assertEquals("NOT_A_MEMBER",rejected.code)
                chats[0].send("被移除期间不可见")
                withTimeout(10_000) { chats[2].state.first { it.messages.size==2 } }
                models[0].invite(listOf(oldMember.userId))
                withTimeout(10_000) { models[0].state.first { !it.busy && it.detail?.members?.size==3 } }
                chats[1].refresh()
                withTimeout(10_000) { chats[1].state.first { it.conversations.any { item -> item.id==id && item.membershipEpoch!=oldMember.membershipEpoch } } }
                chats[0].send("重新入群后的消息")
                withTimeout(15_000) { chats[1].state.first { it.messages.singleOrNull()?.seq=="3" };chats[2].state.first { it.messages.size==3 } }
                assertFalse(observed[1].contains("被移除期间不可见"))
                assertEquals("重新入群后的消息",chats[1].state.value.messages.single().text)
                withTimeout(10_000) { models[2].state.first { it.detail!=null } }
                models[2].leave(false)
                withTimeout(10_000) { chats[2].state.first { it.conversations.isEmpty() } }
                models[0].leave(true)
                withTimeout(10_000) { chats[0].state.first { it.conversations.isEmpty() } }
                chats[1].refresh()
                withTimeout(10_000) { chats[1].state.first { it.conversations.isEmpty() } }
            } finally {
                observers.forEach { it.cancelAndJoin() };models.forEach { it.close() };chats.forEach { it.close() };sessions.forEach { it.close() }
                clients.forEach { it.close();it.coroutineContext[Job]?.join() };dispatcher.close()
            }
        }
    }
}
