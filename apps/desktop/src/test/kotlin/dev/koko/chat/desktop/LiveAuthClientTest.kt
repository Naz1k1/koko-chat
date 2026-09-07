package dev.koko.chat.desktop

import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.*

/** 真实 CIO → HTTP → Redis 票据 → Netty 链路，账号由外部验证脚本定向清理。 */
class LiveAuthClientTest {
    @Test fun `two Kotlin clients register authenticate stay online and log out`() {
        val prefix = System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX")
        assumeTrue("Run scripts/verify-desktop-auth.sh to enable", !prefix.isNullOrEmpty())
        require(prefix!!.matches(Regex("dsk_[a-f0-9]{20}")))
        val settings = ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"), System.getenv("KOKO_CHAT_TEST_IM_URL"))
        runBlocking {
            val clientA = createHttpClient(); val clientB = createHttpClient()
            val apiA = KtorAuthApi(clientA); val apiB = KtorAuthApi(clientB)
            val alice = SessionManager(this, apiA, KtorImConnector(clientA)) { UUID.randomUUID().toString() }
            val bob = SessionManager(this, apiB, KtorImConnector(clientB)) { UUID.randomUUID().toString() }
            try {
                alice.signIn(settings, "${prefix}a", "Only-for-live-test-827!", "桌面甲", true)
                bob.signIn(settings, "${prefix}b", "Only-for-live-test-827!", "桌面乙", true)
                withTimeout(20_000) {
                    for (manager in listOf(alice, bob)) {
                        val state = manager.state.first { it.phase == SessionState.ONLINE || it.phase == SessionState.SIGNED_OUT }
                        assertEquals(SessionState.ONLINE, state.phase, state.message)
                    }
                }
                val denied = assertFailsWith<AuthFailure> { apiA.login(settings, "${prefix}a", "incorrect-password", "bad-device") }
                assertEquals(401, denied.status)
                // 超过匿名认证期限和普通 HTTP 读取超时，验证心跳与已认证连接没有被误关闭。
                delay(35_000)
                assertEquals(SessionState.ONLINE, alice.state.value.phase)
                assertEquals(SessionState.ONLINE, bob.state.value.phase)
                alice.logout()
                assertEquals(SessionState.SIGNED_OUT, alice.state.value.phase)
                assertNull(alice.state.value.user)
                assertEquals(SessionState.ONLINE, bob.state.value.phase)
                bob.logout()
                assertEquals(SessionState.SIGNED_OUT, bob.state.value.phase)
            } finally {
                alice.close(); bob.close()
                clientA.close(); clientB.close()
                clientA.coroutineContext[Job]?.join(); clientB.coroutineContext[Job]?.join()
            }
        }
    }
}
