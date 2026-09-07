package dev.koko.chat.desktop

import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.time.Instant
import kotlin.test.*

/** 使用可控协程验证账号代次、单连接、退出清理与刷新失败，不依赖真实网络时序。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    private val settings = ServiceSettings()
    private fun tokens(expired: Boolean = false) = AuthTokens("access", "refresh",
        Instant.now().plusSeconds(if (expired) -1 else 3600).toString(), Instant.now().plusSeconds(86400).toString(),
        "session-id", UserProfile("123", "alice", "小可"))
    private inner class FakeApi(var value: AuthTokens = tokens()) : AuthApi {
        var logins = 0; var refreshes = 0; var logouts = 0
        var failRefresh = false; var failLogout = false
        override suspend fun register(settings: ServiceSettings, account: String, password: String, nickname: String) {}
        override suspend fun login(settings: ServiceSettings, account: String, password: String, deviceId: String): AuthTokens { logins++; return value }
        override suspend fun refresh(settings: ServiceSettings, refreshToken: String): AuthTokens {
            refreshes++; if (failRefresh) error("Response lost")
            return tokens().copy(accessToken = "new-access", refreshToken = "new-refresh")
        }
        override suspend fun logout(settings: ServiceSettings, refreshToken: String) { logouts++; if (failLogout) error("Offline") }
        override suspend fun ticket(settings: ServiceSettings, accessToken: String) = ImTicket("ticket", 30)
    }

    @Test fun `duplicate login creates one connection and stale callbacks cannot restore logged out account`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        var callback: (() -> Unit)? = null
        var disconnected = false
        val connector = object : ImConnector {
            override suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit) {
                callback = onAuthenticated
                try { gate.await(); onAuthenticated(); awaitCancellation() } finally { disconnected = true }
            }
        }
        val manager = SessionManager(this, api, connector) { "device" }
        try {
            repeat(2) { manager.signIn(settings, "alice", "password-123", null, false) }
            runCurrent()
            assertEquals(1, api.logins)
            assertEquals(SessionState.CONNECTING, manager.state.value.phase)
            gate.complete(Unit); runCurrent()
            assertEquals(SessionState.ONLINE, manager.state.value.phase)
            manager.logout()
            assertTrue(disconnected)
            assertEquals(1, api.logouts)
            callback!!()
            assertEquals(SessionState.SIGNED_OUT, manager.state.value.phase)
            assertNull(manager.state.value.user)
        } finally { manager.close() }
    }

    @Test fun `reconnect refreshes once and logout still clears local state when server is offline`() = runTest {
        val api = FakeApi(tokens(expired = true))
        var attempts = 0
        val connector = object : ImConnector {
            override suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit) {
                attempts++
                assertEquals("new-access", expected.accessToken)
                if (attempts == 1) error("Network interrupted")
                onAuthenticated(); awaitCancellation()
            }
        }
        val manager = SessionManager(this, api, connector) { "device" }
        try {
            manager.signIn(settings, "alice", "password-123", null, false); runCurrent()
            assertEquals(SessionState.RECONNECTING, manager.state.value.phase)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, attempts); assertEquals(1, api.refreshes)
            assertEquals(SessionState.ONLINE, manager.state.value.phase)
            api.failLogout = true
            manager.logout()
            assertEquals(SessionState.SIGNED_OUT, manager.state.value.phase)
            assertTrue(manager.state.value.message.contains("撤销未确认"))
        } finally { manager.close() }
    }

    @Test fun `ambiguous refresh response requires login and never retries the old refresh token`() = runTest {
        val api = FakeApi(tokens(expired = true)).apply { failRefresh = true }
        val connector = object : ImConnector {
            override suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit) { fail("Must not connect") }
        }
        val manager = SessionManager(this, api, connector) { "device" }
        try {
            manager.signIn(settings, "alice", "password-123", null, false); runCurrent()
            assertEquals(SessionState.SIGNED_OUT, manager.state.value.phase)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, api.refreshes)
        } finally { manager.close() }
    }

    @Test fun `connection timeout is retried without cancelling the account session`() = runTest {
        val connector = object : ImConnector {
            override suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit) {
                withTimeout(10) { delay(1_000) }
            }
        }
        val manager = SessionManager(this, FakeApi(), connector) { "device" }
        try {
            manager.signIn(settings, "alice", "password-123", null, false); runCurrent()
            advanceTimeBy(10); runCurrent()
            assertEquals(SessionState.RECONNECTING, manager.state.value.phase)
            assertNotNull(manager.state.value.user)
        } finally { manager.close() }
    }

    @Test fun `remote credentials require encrypted transports`() {
        assertFailsWith<IllegalArgumentException> { ServiceSettings("http://example.org", "ws://example.org/im").forAuthentication() }
        ServiceSettings("https://example.org", "wss://example.org/im").forAuthentication()
        settings.forAuthentication()
    }
}
