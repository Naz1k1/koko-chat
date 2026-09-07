package dev.koko.chat.desktop.presentation

import dev.koko.chat.desktop.group.*
import dev.koko.chat.desktop.network.GroupMember
import dev.koko.chat.desktop.contact.ContactModel
import dev.koko.chat.desktop.contact.ContactUiState
import dev.koko.chat.desktop.chat.ChatModel
import dev.koko.chat.desktop.chat.ChatUiState
import dev.koko.chat.desktop.session.SessionManager
import dev.koko.chat.desktop.session.SessionUiState
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.ProbeResult
import dev.koko.chat.desktop.network.ServiceProbe
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** HTTP 探针状态与登录状态独立，REACHABLE 不表示可以发送聊天消息。 */
enum class ProbeStatus { NOT_CHECKED, CHECKING, REACHABLE, UNHEALTHY, FAILED }

/** 页面可观察的当前状态快照；StateFlow 不承担逐条网络消息事件的传递。 */
data class DesktopUiState(
    val settings: ServiceSettings = ServiceSettings(),
    val initialized: Boolean = false,
    val storageReady: Boolean = false,
    val storageMessage: String = "正在打开本地设置…",
    val probeStatus: ProbeStatus = ProbeStatus.NOT_CHECKED,
    val probeMessage: String = "尚未检查 HTTP 服务",
    val service: ProbeResult? = null,
    val settingsOpen: Boolean = false,
    val settingsSaving: Boolean = false,
    val settingsError: String? = null,
)

/** 普通 Kotlin MVVM 模型，管理页面任务与状态，不持有全局认证连接。 */
class DesktopScreenModel(
    parentScope: CoroutineScope,
    private val store: PreferencesStore,
    private val probe: ServiceProbe,
    private val sessions: SessionManager? = null,
    private val chat: ChatModel? = null,
    private val contacts: ContactModel? = null,
    private val groups: GroupModel? = null,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = mutableState.asStateFlow()
    private var probeJob: Job? = null
    val sessionState: StateFlow<SessionUiState> = sessions?.state ?: MutableStateFlow(SessionUiState())
    fun signIn(account: String, password: String, nickname: String?, register: Boolean) {
        if (!state.value.initialized || state.value.settingsSaving) return
        sessions?.signIn(state.value.settings, account, password, nickname, register)
    }
    fun logout() { scope.launch { sessions?.logout() } }
    val chatState:StateFlow<ChatUiState> = chat?.state ?: MutableStateFlow(ChatUiState())
    val contactState: StateFlow<ContactUiState> = contacts?.state ?: MutableStateFlow(ContactUiState(loading = false))
    fun refreshContacts() { contacts?.refresh() }
    fun applyFriend(account: String, greeting: String, onSaved: () -> Unit) { contacts?.apply(account, greeting, onSaved) }
    fun decideFriend(id: String, accept: Boolean) { contacts?.decide(id, accept) }
    val groupState: StateFlow<GroupUiState> = groups?.state ?: MutableStateFlow(GroupUiState())
    fun openCreateGroup() { groups?.openCreate() }
    fun openGroupManagement() { groups?.openManage() }
    fun closeGroupDialog() { groups?.closeDialog() }
    fun refreshGroup() { groups?.refresh() }
    fun createGroup(title: String, members: List<String>) { groups?.create(title,members) }
    fun inviteGroup(members: List<String>) { groups?.invite(members) }
    fun removeGroupMember(member: GroupMember) { groups?.remove(member) }
    fun leaveGroup(close: Boolean) { groups?.leave(close) }
    fun retryGroupOperation() { groups?.retry() }
    fun dismissGroupOperation() { groups?.dismissPending() }
    fun readVisible(id:String,epoch:String,seq:Long) { chat?.readVisible(id,epoch,seq) }
    fun loadOlderMessages() { chat?.loadOlder() }
    fun showLatestMessages() { chat?.showLatest() }
    fun selectConversation(id:String) { chat?.select(id) }
    fun createConversation(account:String) { chat?.create(account) }
    fun sendMessage(text:String,onSaved:()->Unit) { chat?.send(text,onSaved) }
    fun retryMessage(id:String) { chat?.retry(id) }

    init {
        scope.launch {
            try {
                val settings = store.load()
                mutableState.update { it.copy(settings = settings, initialized = true, storageReady = true, storageMessage = "本地设置已就绪") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(initialized = true, storageMessage = "本地设置无法打开，请检查数据目录权限") }
            }
        }
    }

    fun openSettings() { mutableState.update { it.copy(settingsOpen = true, settingsError = null) } }
    fun closeSettings() {
        if (!state.value.settingsSaving) mutableState.update { it.copy(settingsOpen = false, settingsError = null) }
    }

    /** 校验并持久化新地址；保存完成前不允许发起使用旧地址的新检查。 */
    fun saveSettings(apiBaseUrl: String, imUrl: String) {
        if (!state.value.initialized || state.value.settingsSaving) return
        val settings = try {
            ServiceSettings(apiBaseUrl, imUrl).validated()
        } catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(settingsError = error.message) }
            return
        }
        mutableState.update { it.copy(settingsSaving = true, settingsError = null) }
        scope.launch {
            try {
                // 先取消并等待旧地址的探针结束，防止迟到结果覆盖新地址的页面状态。
                probeJob?.cancelAndJoin()
                sessions?.logout()
                store.save(settings)
                mutableState.update {
                    it.copy(settings = settings, settingsOpen = false, settingsSaving = false,
                        storageReady = true, storageMessage = "本地设置已就绪",
                        probeStatus = ProbeStatus.NOT_CHECKED, probeMessage = "地址已更新，请重新检查服务", service = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(settingsSaving = false, settingsError = "设置未能保存，请检查数据目录权限", probeStatus = ProbeStatus.NOT_CHECKED, probeMessage = "请重新检查服务") }
            }
        }
    }

    /** 合并重复点击，同一时刻只执行一个探针任务；取消异常必须继续向上传递。 */
    fun checkService() {
        if (!state.value.initialized || state.value.settingsSaving || probeJob?.isActive == true) return
        val settings = state.value.settings
        mutableState.update { it.copy(probeStatus = ProbeStatus.CHECKING, probeMessage = "正在读取服务信息与健康状态…", service = null) }
        probeJob = scope.launch {
            try {
                val result = probe.check(settings)
                val healthy = result.health.status == "UP"
                mutableState.update {
                    it.copy(probeStatus = if (healthy) ProbeStatus.REACHABLE else ProbeStatus.UNHEALTHY,
                        probeMessage = if (healthy) "HTTP 服务可达 · 健康检查通过" else "HTTP 服务可达 · 健康状态 ${result.health.status}",
                        service = result)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val detail = when (error) {
                    is ResponseException -> "服务返回 HTTP ${error.response.status.value}"
                    is IllegalArgumentException -> error.message ?: "服务响应不符合契约"
                    else -> "无法完成检查，请确认服务已启动且地址正确"
                }
                mutableState.update { it.copy(probeStatus = ProbeStatus.FAILED, probeMessage = detail, service = null) }
            }
        }
    }

    /** 关闭页面任务并等待结束，保证随后关闭数据库和 HttpClient 时不再被使用。 */
    suspend fun close() = job.cancelAndJoin()
}
