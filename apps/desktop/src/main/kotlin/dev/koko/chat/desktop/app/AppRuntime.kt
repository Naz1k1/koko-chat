package dev.koko.chat.desktop.app

import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.KtorServiceProbe
import dev.koko.chat.desktop.network.createHttpClient
import dev.koko.chat.desktop.platform.AppPaths
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Composition-independent owner. UI closure runs cleanup before cancelling this scope. */
class AppRuntime {
    private val appJob = SupervisorJob()
    val scope = CoroutineScope(appJob + Dispatchers.Main)
    private val databaseDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "koko-preferences-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val store = PreferencesStore(AppPaths.preferencesFile(), databaseDispatcher)
    private val client = createHttpClient()
    val sessions = SessionManager(scope)
    val screenModel = DesktopScreenModel(scope, store, KtorServiceProbe(client))

    suspend fun closeResources() {
        try {
            screenModel.close()
            sessions.close()
        } finally {
            try {
                client.close()
                client.coroutineContext[Job]?.join()
            } finally {
                try { store.close() } finally { databaseDispatcher.close() }
            }
        }
    }

    fun cancelScope() = appJob.cancel()
}
