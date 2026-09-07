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
    val notice:String="正在同步会话…",val creating:Boolean=false)
class ChatModel(parent:CoroutineScope,private val sessions:SessionManager,private val connector:ImConnector,
    private val api:ChatApi,private val directory:Path,private val dispatcher:CoroutineDispatcher) {
    private val job=SupervisorJob(parent.coroutineContext[Job]);private val scope=CoroutineScope(parent.coroutineContext+job)
    private val mutable=MutableStateFlow(ChatUiState());val state:StateFlow<ChatUiState> = mutable.asStateFlow()
    private val json=Json { ignoreUnknownKeys=true }
    private data class Binding(val sessionId:String,val userId:String,val store:ChatStore,val scope:CoroutineScope,val sync:Mutex=Mutex())
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
                                            val message=json.decodeFromJsonElement<ChatMessage>(event.envelope.getValue("message"))
                                            val epoch=event.envelope.getValue("membershipEpoch").jsonPrimitive.content
                                            if(store.conversations().none { it.id==message.conversationId && it.membershipEpoch==epoch }) sync(current)
                                            val cursor=store.saveMessages(message.conversationId,epoch,listOf(message))
                                            refreshView(current);ack(current,message.conversationId,epoch,cursor)
                                        } catch(error:Exception) { ensureActive();notice(current,"消息将在下一轮同步补齐") }
                                    }
                                }
                            }
                            launch { while(isActive) { flush(current);delay(2_000) } }
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
    fun select(id:String) { val current=binding?:return;current.scope.launch { refreshView(current,id) } }
    fun create(account:String) {
        val current=binding?:return;if(state.value.creating) return
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
        val current=binding?:return
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
    private suspend fun refreshView(current:Binding,preferredId:String?=null) {
        val requested=state.value.selectedId
        val conversations=current.store.conversations()
        val selected=(preferredId?:requested)?.takeIf { id -> conversations.any { it.id==id } } ?: conversations.firstOrNull()?.id
        val info=conversations.find { it.id==selected }
        val messages=if(info==null) emptyList() else current.store.messages(info)
        val pending=current.store.pending().filter { it.conversationId==selected }
        if(binding===current && state.value.selectedId==requested) mutable.update { it.copy(conversations=conversations,selectedId=selected,messages=messages,pending=pending) }
    }
    private fun notice(current:Binding,text:String) { if(binding===current) mutable.update { it.copy(notice=text) } }
    suspend fun close() = job.cancelAndJoin()
}
