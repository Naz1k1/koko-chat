package dev.koko.chat.desktop

import dev.koko.chat.desktop.chat.ChatModel
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.ChatStore
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.Instant
import kotlin.test.*

/** 真实 SQLite 配合受控网络验证分页与展示范围，覆盖离线、重入和切换时的迟到任务。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryModelTest {
    @get:Rule val temp=TemporaryFolder()
    private val settings=ServiceSettings()
    private val info=ConversationInfo("10","2","bob","小波","old-epoch","1","325",lastReadSeq="0",unreadCount="325")
    private fun message(seq:Int)=ChatMessage("$seq","10","$seq","2","m$seq","TEXT","历史消息 $seq","2026-09-08T00:00:00Z")
    private class Auth:AuthApi {
        override suspend fun register(settings:ServiceSettings,account:String,password:String,nickname:String) {}
        override suspend fun login(settings:ServiceSettings,account:String,password:String,deviceId:String)=AuthTokens("access","refresh",
            Instant.now().plusSeconds(3600).toString(),Instant.now().plusSeconds(86400).toString(),"session-$account",UserProfile(account,account,account))
        override suspend fun refresh(settings:ServiceSettings,refreshToken:String):AuthTokens=error("Unused")
        override suspend fun logout(settings:ServiceSettings,refreshToken:String) {}
        override suspend fun ticket(settings:ServiceSettings,accessToken:String)=ImTicket("ticket",30)
    }
    private class Wire:ImConnector {
        override val events=MutableSharedFlow<ImEvent>(extraBufferCapacity=10)
        val commands=mutableListOf<String>()
        override suspend fun connect(settings:ServiceSettings,ticket:String,expected:AuthTokens,deviceId:String,onAuthenticated:()->Unit) { onAuthenticated();awaitCancellation() }
        override suspend fun request(sessionId:String,envelope:JsonObject):JsonObject {
            commands+=envelope.getValue("type").jsonPrimitive.content
            return buildJsonObject { put("type","RECEIVED_ACK_OK") }
        }
    }
    private inner class Api:ChatApi {
        var entries=listOf(info);var offline=false;var incoming=emptyList<ChatMessage>()
        override suspend fun conversations(settings:ServiceSettings,token:String,after:String):ConversationPage {
            if(offline) error("Offline")
            return ConversationPage(entries,entries.lastOrNull()?.id?:"0",false)
        }
        override suspend fun summary(settings:ServiceSettings,token:String,id:String)=entries.first { it.id==id }
        override suspend fun direct(settings:ServiceSettings,token:String,account:String)=info
        override suspend fun history(settings:ServiceSettings,token:String,id:String,after:String,to:String):MessagePage {
            val member=entries.first { it.id==id };val page=incoming.filter { it.conversationId==id && it.seq.toLong()>after.toLong() && it.seq.toLong()<=to.toLong() }
            return MessagePage(page,member.membershipEpoch,member.visibleFromSeq,to,page.lastOrNull()?.seq?:after,false)
        }
    }
    @Test fun `offline older pages stay expanded through sync and push without reading messages`() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val seed=ChatStore(temp.root.toPath(),settings.apiBaseUrl,"alice",dispatcher)
        seed.saveConversations(listOf(info));seed.saveMessages(info.id,info.membershipEpoch,(1..325).map(::message));seed.close()
        val wire=Wire();val api=Api();val sessions=SessionManager(this,Auth(),wire) { "device" }
        val model=ChatModel(this,sessions,wire,api,temp.root.toPath(),dispatcher)
        try {
            sessions.signIn(settings,"alice","password",null,false);runCurrent()
            assertEquals((126..325).map(Int::toString),model.state.value.messages.map { it.seq })
            assertTrue(model.state.value.hasOlderMessages)
            api.offline=true
            model.loadOlder();model.loadOlder();runCurrent()
            assertEquals("76",model.state.value.messages.first().seq,"重复点击只能加载一页")
            assertEquals(250,model.state.value.messages.size)
            model.loadOlder();runCurrent();model.loadOlder();runCurrent()
            assertEquals((1..325).map(Int::toString),model.state.value.messages.map { it.seq })
            assertFalse(model.state.value.hasOlderMessages)
            wire.events.emit(ImEvent("session-alice",buildJsonObject {
                put("type","MESSAGE");put("membershipEpoch",info.membershipEpoch);put("message",Json.encodeToJsonElement(message(326)))
            }));runCurrent()
            assertEquals(326,model.state.value.messages.size)
            api.offline=false;api.entries=listOf(info.copy(latestSeq="326",unreadCount="326"))
            model.refresh();runCurrent()
            assertEquals("1",model.state.value.messages.first().seq,"周期同步不能收回已展开的历史")
            assertFalse("READ" in wire.commands,"展示分页不能代替用户阅读")
            assertEquals("0",model.state.value.conversations.single().lastReadSeq)
            model.showLatest();runCurrent()
            assertEquals((127..326).map(Int::toString),model.state.value.messages.map { it.seq })
            assertEquals(1,model.state.value.latestRevision)
        } finally { model.close();sessions.close() }
    }

    @Test fun `navigation cancels queued history and a new membership resets the range`() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val other=info.copy(id="20",membershipEpoch="other",latestSeq="0",unreadCount="0")
        val seed=ChatStore(temp.root.toPath(),settings.apiBaseUrl,"alice",dispatcher)
        seed.saveConversations(listOf(info,other));seed.saveMessages(info.id,info.membershipEpoch,(1..325).map(::message));seed.close()
        val wire=Wire();val api=Api().apply { entries=listOf(info,other) };val sessions=SessionManager(this,Auth(),wire) { "device" }
        val model=ChatModel(this,sessions,wire,api,temp.root.toPath(),dispatcher)
        try {
            sessions.signIn(settings,"alice","password",null,false);runCurrent()
            model.loadOlder();model.select("20");runCurrent()
            assertEquals("20",model.state.value.selectedId);assertTrue(model.state.value.messages.isEmpty())
            assertFalse(model.state.value.loadingOlder)
            model.select("10");runCurrent()
            assertEquals("126",model.state.value.messages.first().seq)
            model.loadOlder();runCurrent();assertEquals("76",model.state.value.messages.first().seq)
            api.entries=listOf(info.copy(membershipEpoch="new-epoch",visibleFromSeq="326",latestSeq="326",lastReadSeq="325",unreadCount="1"),other)
            api.incoming=listOf(message(326));model.refresh();runCurrent()
            assertEquals(listOf("326"),model.state.value.messages.map { it.seq })
            assertFalse(model.state.value.hasOlderMessages)
            assertEquals("new-epoch",model.state.value.conversations.first().membershipEpoch)
            sessions.logout();runCurrent();assertTrue(model.state.value.messages.isEmpty())
            api.incoming=emptyList();api.entries=emptyList()
            sessions.signIn(settings,"carol","password",null,false);runCurrent()
            assertTrue(model.state.value.conversations.isEmpty(),"其他账号不能读取之前的历史范围")
        } finally { model.close();sessions.close() }
    }
}
