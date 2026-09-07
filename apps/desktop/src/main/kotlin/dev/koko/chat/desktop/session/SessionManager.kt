package dev.koko.chat.desktop.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState { SIGNED_OUT, CLOSING }

/** Owns the future authenticated session; probes cannot promote this state to ONLINE. */
class SessionManager(parentScope: CoroutineScope) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val mutableState = MutableStateFlow(SessionState.SIGNED_OUT)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    suspend fun close() {
        mutableState.value = SessionState.CLOSING
        job.cancelAndJoin()
        mutableState.value = SessionState.SIGNED_OUT
    }
}
