package dev.koko.chat.desktop.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 骨架仅支持未登录和关闭中状态，后续再加入认证、重连与同步状态。 */
enum class SessionState { SIGNED_OUT, CLOSING }

/** 预留账号会话的生命周期边界；HTTP 探针成功不能将会话标记为在线。 */
class SessionManager(parentScope: CoroutineScope) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val mutableState = MutableStateFlow(SessionState.SIGNED_OUT)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** 等待账号子任务退出后回到未登录；当前尚未创建真实认证连接。 */
    suspend fun close() {
        mutableState.value = SessionState.CLOSING
        job.cancelAndJoin()
        mutableState.value = SessionState.SIGNED_OUT
    }
}
