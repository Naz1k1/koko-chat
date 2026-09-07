package dev.koko.chat.desktop

import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.ProbeResult
import dev.koko.chat.desktop.network.ServiceProbe
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.presentation.ProbeStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
/** 用受控协程验证重复检查只启动一次，并确认关闭模型会取消进行中的探针。 */
class DesktopScreenModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `repeated checks share one task and closing cancels the request`() = runTest {
        val store = PreferencesStore(temporaryFolder.root.toPath().resolve("preferences.db"), StandardTestDispatcher(testScheduler))
        var calls = 0
        var cancelled = false
        val probe = object : ServiceProbe {
            override suspend fun check(settings: ServiceSettings): ProbeResult {
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val model = DesktopScreenModel(this, store, probe)
        try {
            runCurrent()
            model.checkService()
            model.checkService()
            runCurrent()
            assertEquals(1, calls)
            assertEquals(ProbeStatus.CHECKING, model.state.value.probeStatus)
            model.close()
            assertTrue(cancelled)
        } finally {
            model.close()
            store.close()
        }
    }
}
