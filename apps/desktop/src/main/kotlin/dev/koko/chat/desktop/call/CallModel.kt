package dev.koko.chat.desktop.call

import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.call.body
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@Serializable data class VoiceCall(val id:String,val conversationId:String,val callerId:String,val calleeId:String,val callerSession:String,val calleeSession:String?=null,val state:String,val reason:String?=null,val expiresAt:String)
@Serializable data class VoiceSignal(val id:Long,val signalId:String,val kind:String,val payload:String)
@Serializable data class CallSnapshot(val call:VoiceCall?=null,val signals:List<VoiceSignal> = emptyList())
data class CallUiState(val call:VoiceCall?=null,val busy:Boolean=false,val muted:Boolean=false,val notice:String="",val connected:Boolean=false)

/** 通话按登录连接隔离；控制命令、媒体协商和释放共用锁，断连立即停止本机采集。 */
class CallModel(parent:CoroutineScope,private val sessions:SessionManager,private val connector:ImConnector,private val client:HttpClient,
                private val engineFactory:((MediaEvent)->Unit)->VoiceEngine = { VoiceEngine(it) }) {
    private val job=SupervisorJob(parent.coroutineContext[Job]);private val scope=CoroutineScope(parent.coroutineContext+job)
    private val mutable=MutableStateFlow(CallUiState());val state=mutable.asStateFlow()
    private val json=Json { ignoreUnknownKeys=true };private val lock=Mutex();private var online:String?=null;private var currentId:String?=null
    private var engine:VoiceEngine?=null;private var after=0L;private var terminal=false
    private val events=ConcurrentLinkedQueue<Pair<String,MediaEvent>>();private val outgoing=ArrayDeque<JsonObject>();private val wake=Channel<Unit>(Channel.CONFLATED)
    private val abandoned=mutableSetOf<String>()
    init {
        scope.launch { connector.events.filter { it.envelope["type"]?.jsonPrimitive?.content=="CALL_CHANGED" }.collect { wake.trySend(Unit) } }
        scope.launch {
            sessions.state.map { if(it.phase==SessionState.ONLINE) it.sessionId else null }.distinctUntilChanged().collectLatest { session ->
                if(session==null) { mutable.value=CallUiState(notice="通话需要 IM 在线");return@collectLatest }
                online=session;mutable.value=CallUiState()
                try {
                    while(isActive) {
                        lock.withLock { try { tick(session) } catch(error:Exception) { ensureActive();if(error is ImCommandFailure && error.code=="CALL_FORBIDDEN") { release();currentId=null;mutable.value=CallUiState(notice="通话已不可用") } else mutable.update { it.copy(notice="通话同步暂不可用，正在重试") } } }
                        withTimeoutOrNull(2000) { wake.receive() }
                    }
                } finally {
                    withContext(NonCancellable) { lock.withLock { currentId?.let { abandoned+=it };release();online=null;currentId=null;mutable.value=CallUiState(notice="连接已断开，通话已停止") } }
                }
            }
        }
    }
    private suspend fun command(action:String,extra:JsonObject=buildJsonObject {}):CallSnapshot {
        val session=online?:error("IM 离线")
        val envelope=buildJsonObject { put("type","CALL");put("action",action);currentId?.let { put("callId",it) };extra.forEach { (k,v)->put(k,v) } }
        if(sessions.state.value.phase!=SessionState.ONLINE || sessions.state.value.sessionId!=session) throw ImAuthenticationLost()
        val response=connector.request(session,envelope)
        if(sessions.state.value.phase!=SessionState.ONLINE || sessions.state.value.sessionId!=session) throw ImAuthenticationLost()
        return json.decodeFromJsonElement(response.getValue("snapshot"))
    }
    fun dial(info:ConversationInfo) {
        if(online==null || state.value.call!=null || state.value.busy || info.type!="DIRECT") return
        val id=UUID.randomUUID().toString();mutable.update { it.copy(busy=true) }
        scope.launch { lock.withLock {
            try {
                // 原编号保留到确认；CREATE 响应丢失时通过 SYNC 找回，不发起第二通电话。
                currentId=id
                apply(command("CREATE",buildJsonObject { put("callId",id);put("conversationId",info.id);put("membershipEpoch",info.membershipEpoch) }))
            } catch(error:Exception) {
                ensureActive();if(error is ImCommandFailure) { currentId=null;mutable.update { it.copy(notice=error.message?:"无法呼叫") } }
                else mutable.update { it.copy(notice="呼叫结果待确认，正在恢复") }
            } finally { mutable.update { it.copy(busy=false) };wake.trySend(Unit) }
        } }
    }
    fun accept()=act("ACCEPT")
    fun hangup()=act("END")
    private fun act(action:String) {
        if(online==null || currentId==null || state.value.busy) return
        mutable.update { it.copy(busy=true) }
        scope.launch { lock.withLock {
            try { if(action=="END") { terminal=true;engine?.close();engine=null };apply(command(action)) }
            catch(error:Exception) { ensureActive();mutable.update { it.copy(notice=if(action=="END") "已停止本机音频，正在确认挂断" else "操作未确认，请重试") } }
            finally { mutable.update { it.copy(busy=false) };wake.trySend(Unit) }
        } }
    }
    fun mute() { scope.launch { lock.withLock { val next=!state.value.muted;engine?.mute(next);mutable.update { it.copy(muted=next) } } } }
    private suspend fun tick(session:String) {
        if(terminal && currentId!=null) { apply(command("END"));return }
        val snapshot=command("SYNC",buildJsonObject { put("after",after) })
        if(snapshot.call?.id in abandoned) { currentId=snapshot.call!!.id;apply(command("END"));return }
        apply(snapshot)
        while(true) {
            val queued=events.poll()?:break;if(queued.first!=currentId) continue;val event=queued.second
            when(event.kind) {
                "OFFER","ANSWER","ICE" -> outgoing.add(buildJsonObject { put("signalId",UUID.randomUUID().toString());put("kind",event.kind);put("payload",event.payload) })
                "CONNECTED" -> { mutable.update { it.copy(connected=true,notice="语音已连接") } }
                "FAILED","CLOSED" -> { terminal=true;engine?.close();engine=null;mutable.update { it.copy(connected=false,notice="音频连接已断开") } }
            }
        }
        if(state.value.connected && state.value.call?.state=="CONNECTING") command("CONNECTED")
        while(outgoing.isNotEmpty() && currentId!=null && !terminal && online==session) { command("SIGNAL",outgoing.first());outgoing.removeFirst() }
    }
    private suspend fun apply(snapshot:CallSnapshot) {
        val call=snapshot.call
        if(call==null || call.state=="ENDED") {
            val had=state.value.call!=null;release();currentId=null
            mutable.value=CallUiState(notice=if(call!=null || had) when(call?.reason) { "REJECTED"->"对方已拒绝";"CANCELLED"->"呼叫已取消";"TIMEOUT"->"无人接听或连接超时";else->"通话已结束" } else state.value.notice)
            return
        }
        if(currentId!=call.id) { release();currentId=call.id }
        mutable.update { it.copy(call=call,notice=when(call.state) { "RINGING"->if(call.callerSession==online) "正在呼叫…" else "收到语音来电";"CONNECTING"->"正在建立音频连接…";else->"语音通话中" }) }
        if(call.state!="RINGING" && engine==null && !terminal) {
            try {
                val config=sessions.withAccess { settings,token -> client.get("${settings.forAuthentication().apiBaseUrl}/api/calls/config") { bearerAuth(token) }.body<CallConfig>() }
                val media=engineFactory { event -> if(event.kind!="AUDIO_FRAME") { events.add(call.id to event);wake.trySend(Unit) } };engine=media
                media.start(config)
                if(call.callerSession==online) media.offer()
            } catch(error:Throwable) {
                if(error is CancellationException) throw error
                terminal=true;engine?.close();engine=null;mutable.update { it.copy(notice="麦克风或音频初始化失败，请检查系统权限") };return
            }
        }
        for(signal in snapshot.signals.filter { it.id>after }) { engine?.receive(signal.kind,signal.payload);after=signal.id }
    }
    private suspend fun release() { engine?.close();engine=null;events.clear();outgoing.clear();after=0;terminal=false }
    suspend fun close() {
        lock.withLock { if(online!=null && currentId!=null) runCatching { withTimeout(1500) { command("END") } };release() }
        job.cancelAndJoin()
    }
}
