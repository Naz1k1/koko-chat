package dev.koko.chat.desktop

import dev.koko.chat.desktop.contact.ContactModel
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.time.Instant
import kotlin.test.*

/** 模拟旧账号请求迟到及响应丢失，验证联系人不会跨账号回写，也不会提前显示成功。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactModelTest {
    private class Auth : AuthApi {
        override suspend fun register(settings: ServiceSettings, account: String, password: String, nickname: String) {}
        override suspend fun login(settings: ServiceSettings, account: String, password: String, deviceId: String) =
            AuthTokens(account,"refresh",Instant.now().plusSeconds(3600).toString(),Instant.now().plusSeconds(86400).toString(),
                "session-$account",UserProfile(account,account,account))
        override suspend fun refresh(settings: ServiceSettings, refreshToken: String): AuthTokens = error("Not needed")
        override suspend fun logout(settings: ServiceSettings, refreshToken: String) {}
        override suspend fun ticket(settings: ServiceSettings, accessToken: String) = ImTicket("ticket",30)
    }
    private val connector = object : ImConnector {
        override suspend fun connect(settings: ServiceSettings, ticket: String, expected: AuthTokens, deviceId: String, onAuthenticated: () -> Unit) {
            onAuthenticated(); awaitCancellation()
        }
    }
    private open class Contacts : ContactApi {
        var saved: FriendRequestInfo? = null
        override suspend fun friends(settings: ServiceSettings, token: String, after: String) = FriendPage(listOf(FriendInfo("2",token,"当前账号的好友")),"2",false)
        override suspend fun requests(settings: ServiceSettings, token: String, after: String) = FriendRequestPage(listOfNotNull(saved),saved?.id ?: "0",false)
        override suspend fun apply(settings: ServiceSettings, token: String, account: String, greeting: String): FriendRequestInfo {
            saved = FriendRequestInfo("10","alice","bob","alice","甲","bob","乙",greeting,"PENDING","2026-09-08T00:00:00Z")
            error("Response lost after server commit")
        }
        override suspend fun decide(settings: ServiceSettings, token: String, id: String, accept: Boolean): FriendRequestInfo = error("Not needed")
    }

    @Test fun `late response from logged out account cannot populate new contacts`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : Contacts() {
            override suspend fun friends(settings: ServiceSettings, token: String, after: String): FriendPage {
                if(token == "alice") withContext(NonCancellable) { gate.await() }
                return super.friends(settings,token,after)
            }
        }
        val sessions = SessionManager(this,Auth(),connector) { "device" }
        val contacts = ContactModel(this,sessions,api)
        try {
            sessions.signIn(ServiceSettings(),"alice","password",null,false); runCurrent()
            assertTrue(contacts.state.value.loading)
            sessions.logout(); sessions.signIn(ServiceSettings(),"carol","password",null,false); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals("carol",contacts.state.value.friends.single().account)
            sessions.logout(); runCurrent()
            assertTrue(contacts.state.value.friends.isEmpty()); assertTrue(contacts.state.value.requests.isEmpty())
        } finally { gate.complete(Unit);contacts.close();sessions.close() }
    }

    @Test fun `ambiguous mutation stays unconfirmed until list refresh`() = runTest {
        val sessions = SessionManager(this,Auth(),connector) { "device" }
        val contacts = ContactModel(this,sessions,Contacts())
        var confirmed = false
        try {
            sessions.signIn(ServiceSettings(),"alice","password",null,false);runCurrent()
            contacts.apply("bob","你好") { confirmed = true };runCurrent()
            assertFalse(confirmed)
            assertTrue(contacts.state.value.notice.contains("结果暂未确认"))
            assertTrue(contacts.state.value.requests.isEmpty())
            contacts.refresh();runCurrent()
            assertEquals("PENDING",contacts.state.value.requests.single().status)
            assertFalse(contacts.state.value.busy)
        } finally { contacts.close();sessions.close() }
    }
}
