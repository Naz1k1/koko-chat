package dev.koko.chat.desktop.chat

import dev.koko.chat.desktop.data.ChatStore
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Path

/** 页面列表来自账号独立的本地库；消息推送是增量提示，固定上界同步负责填补缺口。 */
data class ChatUiState(val conversations:List<ConversationInfo> = emptyList(),val selectedId:String?=null,
    val messages:List<ChatMessage> = emptyList(),val pending:List<ChatStore.Pending> = emptyList(),
    val notice:String="正在同步会话…",val creating:Boolean=false,
    val hasOlderMessages:Boolean=false,val loadingOlder:Boolean=false,val historyError:String?=null,
    val latestRevision:Long=0)
class ChatModel(parent:CoroutineScope,private val sessions:SessionManager,private val connector:ImConnector,
    private val api:ChatApi,private val directory:Path,private val dispatcher:CoroutineDispatcher) {
    private val job=SupervisorJob(parent.coroutineContext[Job]);private val scope=CoroutineScope(parent.coroutineContext+job)
    private val mutable=MutableStateFlow(ChatUiState());val state:StateFlow<ChatUiState> = mutable.asStateFlow()
    private val json=Json { ignoreUnknownKeys=true }
    private data class Binding(val sessionId:String,val userId:String,val store:ChatStore,val scope:CoroutineScope,val sync:Mutex=Mutex(),
        val view:Mutex=Mutex(),var windowKey:Pair<String,String>?=null,var historyStart:Long?=null,var navigation:Long=0)
    private var binding:Binding?=null
    init {
        scope.launch {
            sessions.state.map { if(it.phase in setOf(SessionState.SIGNED_OUT,SessionState.SIGNING_OUT)) null else it.user?.let { user -> user to it.sessionId } }
                .distinctUntilChanged().collectLatest { account ->
                    mutable.value=ChatUiState();if(account==null || account.second==null) return@collectLatest
                    val store=ChatStore(directory,sessions.serverBase()?:return@collectLatest,account.first.id,dispatcher)
                    try {
                        coroutineScope {
                            val current=Binding(account.second!!,account.first.id,store,this);binding=current
                            // 首次目录校验成功前不展示旧群缓存，避免退出或重入后短暂显示旧周期。
                            launch { while(isActive) { try { sync(current) } catch(error:Exception) { ensureActive();notice(current,"同步暂未完成，稍后会自动重试") };delay(10_000) } }
                            launch {
                                connector.events.collect { event ->
                                    if(event.sessionId==current.sessionId) {
                                        try {
                                            if(event.envelope["type"]?.jsonPrimitive?.content=="READ_UPDATE") {
                                                current.sync.withLock {
                                                    store.saveReadSnapshot(json.decodeFromJsonElement<ConversationInfo>(event.envelope.getValue("conversation")))
                                                    refreshView(current)
                                                }
                                                return@collect
                                            }
                                            val message=json.decodeFromJsonElement<ChatMessage>(event.envelope.getValue("message"))
                                            val epoch=event.envelope.getValue("membershipEpoch").jsonPrimitive.content
                                            if(store.conversations().none { it.id==message.conversationId && it.membershipEpoch==epoch }) sync(current)
                                            val cursor=store.saveMessages(message.conversationId,epoch,listOf(message))
                                            refreshView(current);ack(current,message.conversationId,epoch,cursor)
                                        } catch(error:Exception) { ensureActive();notice(current,"消息将在下一轮同步补齐") }
                                    }
                                }
                            }
                            launch { while(isActive) { flush(current);flushReads(current);delay(2_000) } }
                            awaitCancellation()
                        }
                    } catch(error:Exception) {
                        currentCoroutineContext().ensureActive()
                        mutable.update { it.copy(notice="本地消息库暂不可用，请退出后重试") }
                    } finally {
                        binding=null
                        withContext(NonCancellable) { store.close() }
                    }
                }
        }
    }
    fun readVisible(id:String,epoch:String,seq:Long) {
        val current=binding?:return
        if(state.value.selectedId!=id) return
        current.scope.launch {
            try { current.store.markRead(id,epoch,seq);refreshView(current) }
            catch(error:Exception) { ensureActive();notice(current,"已读状态稍后重试") }
        }
    }
    /** 展示分页只查当前账号缓存；重复点击合并，切换会话后的结果不能写回新页面。 */
    fun loadOlder() {
        val current=binding?:return;val captured=state.value;val navigation=current.navigation
        val info=captured.conversations.find { it.id==captured.selectedId }?:return
        val before=captured.messages.firstOrNull()?.seq?.toLong()?:return
        if(captured.loadingOlder || !captured.hasOlderMessages) return
        mutable.update { it.copy(loadingOlder=true,historyError=null) }
        current.scope.launch {
            current.view.withLock {
                val key=info.id to info.membershipEpoch
                try {
                    if(binding!==current || current.windowKey!=key || current.navigation!=navigation || state.value.selectedId!=info.id) return@withLock
                    val older=current.store.olderMessages(info,before)
                    if(older.isNotEmpty()) current.historyStart=older.first().seq.toLong()
                    refreshViewLocked(current)
                } catch(error:Exception) {
                    ensureActive()
                    if(binding===current && current.windowKey==key) mutable.update { it.copy(historyError="历史消息加载失败，请重试") }
                } finally {
                    if(binding===current && current.windowKey==key) mutable.update { it.copy(loadingOlder=false) }
                }
            }
        }
    }
    fun showLatest() {
        val current=binding?:return;val key=current.windowKey?:return;val navigation=current.navigation
        current.scope.launch { current.view.withLock {
            if(binding===current && current.windowKey==key && current.navigation==navigation) {
                try {
                    current.historyStart=null
                    refreshViewLocked(current)
                    mutable.update { it.copy(latestRevision=it.latestRevision+1,historyError=null) }
                } catch(error:Exception) { ensureActive();notice(current,"暂时无法定位最新消息，请稍后重试") }
            }
        } }
    }
    fun select(id:String) { val current=binding?:return;current.navigation++;current.scope.launch { refreshView(current,id) } }
    fun create(account:String) {
        val current=binding?:return;if(state.value.creating) return
        current.navigation++
        mutable.update { it.copy(creating=true) }
        current.scope.launch {
            try {
                require(account.matches(Regex("[A-Za-z0-9_]{3,32}"))) { "请输入准确账号" }
                current.sync.withLock {
                    val info=sessions.withAccess { settings,token -> api.direct(settings,token,account) }
                    current.store.saveConversations(listOf(info));refreshView(current,info.id);notice(current,"会话已建立")
                }
            } catch(error:Exception) { ensureActive();notice(current,if(error is ResponseException && error.response.status.value==404) "未找到该账号" else "创建会话失败，请检查账号并重试") }
            finally { if(binding===current) mutable.update { it.copy(creating=false) } }
        }
    }
    fun refresh() { val current=binding?:return;current.scope.launch { try { sync(current) } catch(error:Exception) { ensureActive();notice(current,"同步暂未完成，请稍后刷新") } } }
    fun open(id:String) {
        val current=binding?:return;current.navigation++
        current.scope.launch {
            try { current.sync.withLock {
                val info=sessions.withAccess { settings,token -> api.summary(settings,token,id) }
                current.store.saveConversations(listOf(info));refreshView(current,id)
            };sync(current) } catch(error:Exception) { ensureActive();notice(current,"会话暂不可用，请刷新后重试") }
        }
    }
    fun send(text:String,onSaved:()->Unit = {}) {
        val current=binding?:return;val info=state.value.conversations.find { it.id==state.value.selectedId }?:return
        current.scope.launch {
            try { current.store.enqueue(info,text);if(binding===current) onSaved();refreshView(current) }
            catch(error:Exception) { ensureActive();notice(current,error.message?:"无法保存待发送消息") }
        }
    }
    fun retry(id:String) { val current=binding?:return;current.scope.launch { current.store.retry(id,false,null);refreshView(current) } }
    /** 拉取固定 toSeq 的完整分页，不把服务端设备游标当成本机历史缓存。 */
    private suspend fun sync(current:Binding) = current.sync.withLock {
        var after="0"
        val seen=mutableSetOf<String>()
        do {
            val page=sessions.withAccess { settings,token -> api.conversations(settings,token,after) }
            current.store.saveConversations(page.conversations)
            seen.addAll(page.conversations.map { it.id })
            after=page.nextCursor
            if(!page.hasMore) break
        } while(currentCoroutineContext().isActive)
        // 列表分页不是快照。对缺失的本机会话单独核验，不能把分页竞态或网络异常当作退群。
        for(info in current.store.conversations().filter { it.id !in seen }) {
            try {
                val latest=sessions.withAccess { settings,token -> api.summary(settings,token,info.id) }
                current.store.saveConversations(listOf(latest))
            } catch(error:ResponseException) {
                if(error.response.status.value in setOf(403,404)) current.store.removeConversation(info.id) else throw error
            }
        }
        refreshView(current)
        for(info in current.store.conversations()) {
            try {
            var cursor=current.store.cursor(info.id).toString()
            do {
                val page=sessions.withAccess { settings,token -> api.history(settings,token,info.id,cursor,info.latestSeq) }
                val contiguous=current.store.saveMessages(info.id,page.membershipEpoch,page.messages)
                if(page.nextCursor.toLong()>cursor.toLong()) cursor=page.nextCursor
                ack(current,info.id,page.membershipEpoch,contiguous)
                if(!page.hasMore) break
            } while(currentCoroutineContext().isActive)
            } catch(error:ResponseException) {
                if(error.response.status.value in setOf(403,404)) current.store.removeConversation(info.id) else throw error
            }
        }
        refreshView(current);notice(current,"消息已同步")
    }
    private suspend fun ack(current:Binding,id:String,epoch:String,seq:Long) {
        if(sessions.state.value.phase!=SessionState.ONLINE) return
        connector.request(current.sessionId,buildJsonObject { put("type","RECEIVED_ACK");put("conversationId",id);put("membershipEpoch",epoch);put("receivedSeq",seq.toString()) })
    }
    private suspend fun flush(current:Binding) {
        if(sessions.state.value.phase!=SessionState.ONLINE) return
        for(pending in current.store.pending(true)) {
            try {
                val result=connector.request(current.sessionId,buildJsonObject { put("type","SEND");put("conversationId",pending.conversationId)
                    put("membershipEpoch",pending.epoch);put("clientMsgId",pending.clientMsgId);put("text",pending.text) })
                require(result["type"]?.jsonPrimitive?.content=="SEND_ACK")
                val message=json.decodeFromJsonElement<ChatMessage>(result.getValue("message"))
                require(message.senderId==current.userId && message.clientMsgId==pending.clientMsgId)
                val cursor=current.store.saveMessages(pending.conversationId,pending.epoch,listOf(message))
                ack(current,pending.conversationId,pending.epoch,cursor)
            } catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                val permanent=error is ImCommandFailure && error.code in setOf("INVALID_MESSAGE","NOT_A_MEMBER","MEMBERSHIP_CHANGED","IDEMPOTENCY_CONFLICT")
                current.store.retry(pending.clientMsgId,permanent,if(permanent) error.message else "确认未收到，将使用原编号重试")
            }
            refreshView(current)
        }
    }
    /** 先确认本机连续落盘位置，再提交持久化的 READ 意图；响应丢失后重发相同进度。 */
    private suspend fun flushReads(current:Binding) {
        if(sessions.state.value.phase!=SessionState.ONLINE) return
        for(read in current.store.pendingReads()) {
            try {
                current.sync.withLock {
                    val info=current.store.conversations().find { it.id==read.conversationId && it.membershipEpoch==read.epoch } ?: return@withLock
                    ack(current,info.id,info.membershipEpoch,current.store.cursor(info.id))
                    val result=connector.request(current.sessionId,buildJsonObject {
                        put("type","READ");put("conversationId",info.id);put("membershipEpoch",info.membershipEpoch);put("readSeq",read.seq.toString())
                    })
                    require(result["type"]?.jsonPrimitive?.content=="READ_ACK")
                    current.store.saveReadSnapshot(json.decodeFromJsonElement<ConversationInfo>(result.getValue("conversation")))
                    refreshView(current)
                }
            } catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                // 网络失败保留 SQLite 意图；成员失效交给下一次权威目录同步清理。
                notice(current,"已读状态将在连接恢复后同步")
            }
        }
    }
    private suspend fun refreshView(current:Binding,preferredId:String?=null) = current.view.withLock { refreshViewLocked(current,preferredId) }
    /** 展示范围按会话和成员周期隔离，加载、实时推送和读回执刷新共用视图锁。 */
    private suspend fun refreshViewLocked(current:Binding,preferredId:String?=null) {
        val requested=state.value.selectedId
        val conversations=current.store.conversations()
        val selected=(preferredId?:requested)?.takeIf { id -> conversations.any { it.id==id } } ?: conversations.firstOrNull()?.id
        val info=conversations.find { it.id==selected }
        val key=info?.let { it.id to it.membershipEpoch }
        val changed=current.windowKey!=key
        if(changed) { current.windowKey=key;current.historyStart=null }
        val window=if(info==null) ChatStore.MessageWindow(emptyList(),false) else current.store.messageWindow(info,current.historyStart)
        val pending=current.store.pending().filter { it.conversationId==selected }
        if(binding===current && state.value.selectedId==requested) mutable.update { it.copy(
            conversations=conversations,selectedId=selected,messages=window.messages,pending=pending,hasOlderMessages=window.hasOlder,
            loadingOlder=if(changed) false else it.loadingOlder,historyError=if(changed) null else it.historyError) }
    }
    private fun notice(current:Binding,text:String) { if(binding===current) mutable.update { it.copy(notice=text) } }
    suspend fun close() = job.cancelAndJoin()
}
