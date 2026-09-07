package dev.koko.chat.desktop.session

import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** 登录成功与长连接在线分别展示；只有通过 AUTH_OK 的连接才能进入 ONLINE。 */
enum class SessionState { SIGNED_OUT, SIGNING_IN, CONNECTING, ONLINE, RECONNECTING, SIGNING_OUT }
data class SessionUiState(val phase: SessionState = SessionState.SIGNED_OUT, val user: UserProfile? = null,
    val message: String = "登录后连接聊天服务") {
    val busy: Boolean get() = phase == SessionState.SIGNING_IN || phase == SessionState.SIGNING_OUT
}

/** 账号资源边界：单一连接循环串行刷新令牌；代次编号阻止旧账号任务回写新状态。 */
class SessionManager(parentScope: CoroutineScope, private val api: AuthApi, private val connector: ImConnector,
    private val deviceId: suspend () -> String) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    private var operation: Job? = null
    private var connection: Job? = null
    private var tokens: AuthTokens? = null
    private var settings: ServiceSettings? = null
    private var generation = 0L
    private val logoutLock = Mutex()

    /** 页面事件从同一个 UI 调度器调用；重复提交被合并，密码不进入可观察状态。 */
    fun signIn(settings: ServiceSettings, account: String, password: String, nickname: String?, register: Boolean) {
        if (state.value.phase != SessionState.SIGNED_OUT || operation?.isActive == true) return
        val epoch = ++generation
        mutableState.value = SessionUiState(SessionState.SIGNING_IN, message = if (register) "正在注册并登录…" else "正在登录…")
        operation = scope.launch {
            var registered = false
            try {
                val safeSettings = settings.forAuthentication()
                require(account.matches(Regex("[A-Za-z0-9_]{3,32}"))) { "账号需为 3–32 位字母、数字或下划线" }
                require(password.length in 8..128 && password.isNotBlank()) { "密码需为 8–128 个字符" }
                if (register) require(!nickname.isNullOrBlank() && nickname.length <= 64) { "昵称不能为空且最多 64 个字符" }
                val device = deviceId()
                if (register) { api.register(safeSettings, account, password, nickname!!.trim()); registered = true }
                val result = api.login(safeSettings, account, password, device)
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return@launch
                tokens = result; this@SessionManager.settings = safeSettings
                mutableState.value = SessionUiState(SessionState.CONNECTING, result.user, "登录成功，正在认证聊天连接…")
                connection = scope.launch { maintainConnection(epoch, safeSettings, device) }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch == generation) mutableState.value = SessionUiState(message = (if (registered) "账号已创建，请使用该账号登录。" else "") + hint(error))
            }
        }
    }

    private suspend fun maintainConnection(epoch: Long, settings: ServiceSettings, device: String) {
        var retryDelay = 1_000L
        while (currentCoroutineContext().isActive && epoch == generation) {
            try {
                var current = tokens ?: return
                if (!Instant.parse(current.accessExpiresAt).isAfter(Instant.now().plusSeconds(60))) {
                    // 刷新响应丢失后旧令牌状态不明，要求重新登录，避免循环重放刷新令牌。
                    current = try { api.refresh(settings, current.refreshToken) } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw ImAuthenticationLost()
                    }
                    currentCoroutineContext().ensureActive()
                    if (epoch != generation) return
                    tokens = current
                }
                val ticket = api.ticket(settings, current.accessToken)
                connector.connect(settings, ticket.ticket, current, device) {
                    if (epoch == generation) {
                        retryDelay = 1_000L
                        mutableState.value = SessionUiState(SessionState.ONLINE, current.user, "已登录 · IM 在线")
                    }
                }
                error("IM connection ended")
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return
                if (error is ImAuthenticationLost || (error is AuthFailure && error.status == 401)) {
                    tokens = null; this.settings = null
                    mutableState.value = SessionUiState(message = "登录已失效或被替换，请重新登录")
                    return
                }
                mutableState.value = SessionUiState(SessionState.RECONNECTING, tokens?.user, "聊天连接中断，${retryDelay / 1_000} 秒后重试；可随时退出登录")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000)
            }
        }
    }

    /** 先停止旧任务并清空本地身份，再撤销服务端会话。断网时如实报告未确认撤销。 */
    suspend fun logout() = logoutLock.withLock {
        withContext(NonCancellable) {
            ++generation
            mutableState.value = state.value.copy(phase = SessionState.SIGNING_OUT, message = "正在退出并关闭连接…")
            operation?.cancelAndJoin(); operation = null
            connection?.cancelAndJoin(); connection = null
            val oldTokens = tokens; val oldSettings = settings
            tokens = null; settings = null
            var message = "已退出登录，账号凭证已从内存清除"
            if (oldTokens != null && oldSettings != null) {
                try { withTimeout(5_000) { api.logout(oldSettings, oldTokens.refreshToken) } }
                catch (_: Exception) { message = "本机已退出；服务端撤销未确认，原会话将在到期后失效" }
            }
            mutableState.value = SessionUiState(message = message)
        }
    }
    suspend fun close() { logout(); job.cancelAndJoin() }
    private fun hint(error: Exception): String = when (error) {
        is AuthFailure, is IllegalArgumentException -> error.message ?: "请检查登录信息"
        else -> "无法连接认证服务，请检查服务地址和网络"
    }
}
