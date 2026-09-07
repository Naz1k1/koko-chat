package dev.koko.chat.desktop.contact

import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 联系人是当前账号的内存快照；失败保留已加载列表，注销立即取消旧请求并清空资料。 */
data class ContactUiState(
    val friends: List<FriendInfo> = emptyList(), val requests: List<FriendRequestInfo> = emptyList(),
    val loading: Boolean = true, val busy: Boolean = false, val notice: String = "正在同步联系人…",
)

class ContactModel(parent: CoroutineScope, private val sessions: SessionManager, private val api: ContactApi) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(ContactUiState())
    val state: StateFlow<ContactUiState> = mutable.asStateFlow()
    private data class Binding(val scope: CoroutineScope, val mutex: Mutex = Mutex())
    private var binding: Binding? = null

    init {
        scope.launch {
            sessions.state.map { state ->
                if (state.phase in setOf(SessionState.SIGNED_OUT, SessionState.SIGNING_OUT)) null
                else state.user?.id?.let { it to state.sessionId }
            }.distinctUntilChanged().collectLatest { account ->
                mutable.value = ContactUiState()
                if (account?.second == null) return@collectLatest
                try {
                    coroutineScope {
                        val current = Binding(this); binding = current
                        while (isActive) {
                            refresh(current)
                            delay(10_000)
                        }
                    }
                } finally { binding = null; mutable.value = ContactUiState() }
            }
        }
    }

    fun refresh() {
        val current = binding ?: return
        if (state.value.loading || state.value.busy) return
        current.scope.launch { refresh(current) }
    }
    fun apply(account: String, greeting: String, onSaved: () -> Unit = {}) = action("好友申请已发送") {
        require(account.matches(Regex("[A-Za-z0-9_]{3,32}")) && greeting.length <= 255) {
            "请填写准确账号，附言最多 255 字符"
        }
        sessions.withAccess { settings, token -> api.apply(settings, token, account, greeting) }
        onSaved()
    }
    fun decide(id: String, accept: Boolean) = action(if (accept) "已添加为好友" else "已拒绝申请") {
        sessions.withAccess { settings, token -> api.decide(settings, token, id, accept) }
    }

    /** 修改与刷新共享一把锁，避免较早发出的列表响应覆盖刚处理的申请状态。 */
    private fun action(success: String, block: suspend () -> Unit) {
        val current = binding ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        current.scope.launch {
            try {
                current.mutex.withLock {
                    block()
                    set(current) { it.copy(notice = success) }
                    // 操作已经确认成功但列表刷新失败时，保留成功事实并提示刷新。
                    try { load(current) }
                    catch (error: Exception) { ensureActive(); set(current) { it.copy(notice = "$success；列表暂未更新，请刷新") } }
                }
            } catch (error: Exception) {
                ensureActive()
                set(current) { it.copy(notice = when (error) {
                    is ContactFailure, is IllegalArgumentException -> error.message ?: "请检查输入"
                    else -> "结果暂未确认，请刷新列表后重试"
                }) }
            } finally { set(current) { it.copy(busy = false, loading = false) } }
        }
    }
    private suspend fun refresh(current: Binding) = current.mutex.withLock {
        set(current) { it.copy(loading = true) }
        try { load(current); set(current) { it.copy(notice = "联系人已同步") } }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            set(current) { it.copy(notice = if (error is ContactFailure) error.message!! else "联系人暂未同步，请检查连接后刷新") }
        } finally { set(current) { it.copy(loading = false) } }
    }
    private suspend fun load(current: Binding) {
        val friends = mutableListOf<FriendInfo>()
        var cursor = "0"
        do {
            val page = sessions.withAccess { settings, token -> api.friends(settings, token, cursor) }
            friends += page.friends
            check(!page.hasMore || page.nextCursor.toLong() > cursor.toLong()) { "联系人分页未前进" }
            cursor = page.nextCursor
            if (!page.hasMore) break
        } while (currentCoroutineContext().isActive)
        val requests = mutableListOf<FriendRequestInfo>()
        cursor = "0"
        do {
            val page = sessions.withAccess { settings, token -> api.requests(settings, token, cursor) }
            requests += page.requests
            check(!page.hasMore || page.nextCursor.toLong() > cursor.toLong()) { "申请分页未前进" }
            cursor = page.nextCursor
            if (!page.hasMore) break
        } while (currentCoroutineContext().isActive)
        currentCoroutineContext().ensureActive()
        set(current) { it.copy(friends = friends.distinctBy { item -> item.id }.sortedBy { item -> item.account },
            requests = requests.distinctBy { item -> item.id }.sortedWith(
                compareBy<FriendRequestInfo> { item -> item.status != "PENDING" }.thenByDescending { item -> item.createdAt }.thenByDescending { item -> item.id.toLong() })) }
    }
    private fun set(current: Binding, update: (ContactUiState) -> ContactUiState) {
        if (binding === current) mutable.update(update)
    }
    suspend fun close() = job.cancelAndJoin()
}
