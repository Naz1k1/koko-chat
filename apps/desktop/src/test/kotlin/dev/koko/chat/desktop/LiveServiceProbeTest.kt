package dev.koko.chat.desktop

import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.KtorServiceProbe
import dev.koko.chat.desktop.network.createHttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** 显式提供环境变量后才连接真实后端；默认跳过，避免普通测试依赖本机服务。 */
class LiveServiceProbeTest {
    @Test fun `CIO reads system and health from a running backend`() {
        val apiBase = System.getenv("KOKO_CHAT_TEST_API_BASE")?.trim()
        assumeTrue("Set KOKO_CHAT_TEST_API_BASE to run the live backend probe", !apiBase.isNullOrEmpty())

        runBlocking {
            val client = createHttpClient()
            try {
                val result = KtorServiceProbe(client).check(ServiceSettings(apiBaseUrl = requireNotNull(apiBase)))
                assertEquals("koko-chat", result.system.name)
                assertEquals("skeleton", result.system.stage)
                assertEquals("UP", result.health.status)
            } finally {
                client.close()
                client.coroutineContext[Job]?.join()
            }
        }
    }
}
