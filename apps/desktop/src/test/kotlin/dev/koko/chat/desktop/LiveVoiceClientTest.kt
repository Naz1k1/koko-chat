package dev.koko.chat.desktop

import dev.koko.chat.desktop.call.*
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** 真实登录、Netty 通话信令、RabbitMQ 唤醒、WebRTC 音频连接与挂断闭环。 */
class LiveVoiceClientTest {
    @Test fun `two authenticated desktop models complete voice call`():Unit {
        val prefix=System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX");assumeTrue(!prefix.isNullOrBlank())
        runBlocking {
            val settings=ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"),System.getenv("KOKO_CHAT_TEST_IM_URL"))
            val clientA=createHttpClient();val clientB=createHttpClient();val wireA=KtorImConnector(clientA);val wireB=KtorImConnector(clientB)
            // 服务器已提交后丢弃响应，验证原呼叫、原信令编号恢复和挂断重试。
            val signalIds=mutableListOf<String>();val dropped=mutableSetOf<String>()
            val flakyA=object:ImConnector by wireA {
                override suspend fun request(sessionId:String,envelope:JsonObject):JsonObject {
                    val action=envelope["action"]?.jsonPrimitive?.content
                    if(action=="SIGNAL") signalIds+=envelope.getValue("signalId").jsonPrimitive.content
                    val result=wireA.request(sessionId,envelope)
                    if(action in setOf("CREATE","SIGNAL","CONNECTED","END") && dropped.add(action!!)) error("注入通话确认丢失")
                    return result
                }
            }
            var lostAccept=false
            val flakyB=object:ImConnector by wireB {
                override suspend fun request(sessionId:String,envelope:JsonObject):JsonObject {
                    val result=wireB.request(sessionId,envelope)
                    if(envelope["action"]?.jsonPrimitive?.content=="ACCEPT" && !lostAccept) { lostAccept=true;error("注入接听确认丢失") }
                    return result
                }
            }
            val a=SessionManager(this,KtorAuthApi(clientA),wireA) { UUID.randomUUID().toString() }
            val b=SessionManager(this,KtorAuthApi(clientB),wireB) { UUID.randomUUID().toString() }
            val callA=CallModel(this,a,flakyA,clientA) { VoiceEngine(it,true) };val callB=CallModel(this,b,flakyB,clientB) { VoiceEngine(it,true) }
            val notified=CompletableDeferred<Unit>()
            val observer=launch { wireB.events.collect { if(it.envelope["type"].toString()=="\"CALL_CHANGED\"") notified.complete(Unit) } }
            try {
                a.signIn(settings,"${prefix}n","Voice-live-password!","语音甲",true);b.signIn(settings,"${prefix}o","Voice-live-password!","语音乙",true)
                withTimeout(20_000) { a.state.first { it.phase==SessionState.ONLINE };b.state.first { it.phase==SessionState.ONLINE } }
                val info=a.withAccess { address,token -> KtorChatApi(clientA).direct(address,token,"${prefix}o") }
                delay(200);callA.dial(info)
                withTimeout(15_000) { notified.await();callB.state.first { it.call?.state=="RINGING" } }
                callB.accept()
                withTimeout(25_000) { callA.state.first { it.connected && it.call?.state=="ACTIVE" };callB.state.first { it.connected && it.call?.state=="ACTIVE" } }
                assertEquals(callA.state.value.call!!.id,callB.state.value.call!!.id)
                callA.mute();withTimeout(5000) { callA.state.first { it.muted } }
                callA.hangup();withTimeout(10_000) { callA.state.first { it.call==null };callB.state.first { it.call==null } }
                assertFalse(callB.state.value.connected)
                assertEquals(setOf("CREATE","SIGNAL","CONNECTED","END"),dropped)
                assertTrue(lostAccept)
                assertTrue(signalIds.groupingBy { it }.eachCount().values.any { it>=2 },"信令确认丢失应复用原编号重发")
            } finally { observer.cancelAndJoin();callA.close();callB.close();a.close();b.close();clientA.close();clientB.close();clientA.coroutineContext[Job]?.join();clientB.coroutineContext[Job]?.join() }
        }
    }
}
