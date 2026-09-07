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

/** 在 Compose 重组之外统一持有应用资源；先清理子任务和连接，最后取消应用作用域。 */
class AppRuntime {
    private val appJob = SupervisorJob()
    val scope = CoroutineScope(appJob + Dispatchers.Main)
    // JDBC 事务与线程关联，串行 IO 执行器同时避免界面阻塞和设置并发写入。
    private val databaseDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "koko-preferences-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val store = PreferencesStore(AppPaths.preferencesFile(), databaseDispatcher)
    private val client = createHttpClient()
    val sessions = SessionManager(scope)
    val screenModel = DesktopScreenModel(scope, store, KtorServiceProbe(client))

    /** 先等待页面任务退出，再关闭客户端和数据库；finally 保证异常时仍继续释放资源。 */
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

    /** 只在资源清理完成后调用，否则清理协程本身也会被取消。 */
    fun cancelScope() = appJob.cancel()
}
