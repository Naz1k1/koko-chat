package dev.koko.chat.desktop.group

import dev.koko.chat.desktop.chat.ChatModel
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 群管理状态跟随账号生命周期；不明确的请求保留原操作对象，重试不生成新编号。 */
data class GroupUiState(val detail: GroupDetail? = null, val dialog: String? = null,
    val busy: Boolean = false, val pending: GroupOperation? = null, val notice: String = "",
    val lastGroupId: String? = null)
class GroupModel(parent: CoroutineScope, private val sessions: SessionManager, private val chat: ChatModel, private val api: GroupApi) {
    private val job=SupervisorJob(parent.coroutineContext[Job])
    private val scope=CoroutineScope(parent.coroutineContext+job)
    private val mutable=MutableStateFlow(GroupUiState())
    val state: StateFlow<GroupUiState> = mutable.asStateFlow()
    private data class Binding(val scope: CoroutineScope,val mutex: Mutex=Mutex())
    private var binding: Binding? = null
    init {
        scope.launch {
            sessions.state.map { s -> if(s.phase in setOf(SessionState.SIGNED_OUT,SessionState.SIGNING_OUT)) null else s.user?.id?.let { it to s.sessionId } }
                .distinctUntilChanged().collectLatest { account ->
                    mutable.value=GroupUiState()
                    if(account?.second==null) return@collectLatest
                    try {
                        coroutineScope {
                            val current=Binding(this);binding=current
                            chat.state.map { s -> s.conversations.find { it.id==s.selectedId && it.type=="GROUP" }?.let { it.id to it.membershipEpoch } }
                                .distinctUntilChanged().collectLatest { selected ->
                                    set(current) { it.copy(detail=null) }
                                    if(selected!=null) while(isActive) { refresh(current,selected.first);delay(10_000) }
                                }
                        }
                    } finally { binding=null;mutable.value=GroupUiState() }
                }
        }
    }
    fun openCreate() { mutable.update { it.copy(dialog="CREATE",notice=if(it.pending!=null) "请先确认上一次操作" else "选择好友建立群聊，也可以先创建后邀请") } }
    fun openManage() { mutable.update { it.copy(dialog="MANAGE") }; refresh() }
    fun closeDialog() { mutable.update { it.copy(dialog=null) } }
    fun refresh() {
        val current=binding?:return;val id=chat.state.value.selectedId?:return
        current.scope.launch { refresh(current,id) }
    }
    fun create(title: String, members: List<String>) = start(GroupOperation("CREATE",title=title.trim(),memberIds=members))
    fun invite(members: List<String>) { val d=state.value.detail?:return;start(GroupOperation("INVITE",d.id,memberIds=members,membershipEpoch=d.membershipEpoch)) }
    fun remove(member: GroupMember) { val d=state.value.detail?:return;start(GroupOperation("REMOVE",d.id,membershipEpoch=d.membershipEpoch,targetUserId=member.userId,targetEpoch=member.membershipEpoch)) }
    fun leave(close: Boolean) { val d=state.value.detail?:return;start(GroupOperation(if(close) "CLOSE" else "LEAVE",d.id,membershipEpoch=d.membershipEpoch)) }
    fun retry() { val operation=state.value.pending?:return;submit(operation) }
    fun dismissPending() { if(!state.value.busy) mutable.update { it.copy(pending=null,notice="已停止重试，请先刷新会话列表核对结果") };chat.refresh() }
    private fun start(operation: GroupOperation) {
        if(state.value.pending!=null || state.value.busy) return
        submit(operation)
    }
    private fun submit(operation: GroupOperation) {
        val current=binding?:return;if(state.value.busy) return
        set(current) { it.copy(busy=true,pending=operation,notice="正在处理…") }
        current.scope.launch {
            try {
                current.mutex.withLock {
                    val result=sessions.withAccess { settings,token -> api.execute(settings,token,operation) }
                    check(result.clientCommandId==operation.clientCommandId)
                    set(current) { it.copy(pending=null,lastGroupId=result.groupId,notice="操作已完成",
                        dialog=if(operation.kind in setOf("CREATE","LEAVE","CLOSE")) null else it.dialog,
                        detail=if(operation.kind in setOf("LEAVE","CLOSE")) null else it.detail) }
                    if(operation.kind=="CREATE") chat.open(result.groupId) else chat.refresh()
                    if(operation.kind in setOf("INVITE","REMOVE")) {
                        try { load(current,result.groupId) } catch(error: Exception) {
                            ensureActive();set(current) { it.copy(notice="操作已完成，成员列表暂未更新，请刷新") }
                        }
                    }
                }
            } catch(error: Exception) {
                ensureActive()
                // 4xx 为明确拒绝；网络异常、5xx 和限流保留参数，供显式重试核对。
                val rejected=error is GroupFailure && error.status in 400..499 && error.status!=429
                set(current) { it.copy(pending=if(rejected) null else it.pending,
                    notice=if(error is GroupFailure) error.message!! else "结果暂未确认，可使用原操作重试") }
                if(rejected) chat.refresh()
            } finally { set(current) { it.copy(busy=false) } }
        }
    }
    private suspend fun refresh(current: Binding,id: String) = current.mutex.withLock {
        try { load(current,id) }
        catch(error: Exception) {
            currentCoroutineContext().ensureActive()
            if(error is GroupFailure && error.status in setOf(403,404)) {
                set(current) { it.copy(detail=null,notice=error.message!!) };chat.refresh()
            } else set(current) { it.copy(notice="成员列表暂未更新，请稍后刷新") }
        }
    }
    private suspend fun load(current: Binding,id: String) {
        val detail=sessions.withAccess { settings,token -> api.detail(settings,token,id) }
        if(chat.state.value.selectedId==id) set(current) { it.copy(detail=detail) }
    }
    private fun set(current: Binding,update: (GroupUiState)->GroupUiState) { if(binding===current) mutable.update(update) }
    suspend fun close()=job.cancelAndJoin()
}
