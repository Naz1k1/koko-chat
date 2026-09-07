package dev.koko.chat.desktop.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.koko.chat.desktop.ui.DesktopApp
import kotlinx.coroutines.launch
import java.awt.Dimension

/** 桌面程序入口；窗口只渲染状态，网络和数据库由窗口外的 AppRuntime 持有。 */
fun main() {
    val runtime = AppRuntime()
    application {
        var closing by remember { mutableStateOf(false) }
        Window(
            title = "koko-chat",
            state = rememberWindowState(width = 1120.dp, height = 760.dp),
            onCloseRequest = {
                // 避免连续点击关闭触发多次清理；当前关闭窗口即退出，不隐藏到托盘。
                if (!closing) {
                    closing = true
                    runtime.scope.launch {
                        try { runtime.closeResources() }
                        finally {
                            runtime.cancelScope()
                            exitApplication()
                        }
                    }
                }
            },
        ) {
            window.minimumSize = Dimension(960, 640)
            var focused by remember { mutableStateOf(window.isFocused) }
            DisposableEffect(window) {
                val listener=object:java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(event:java.awt.event.WindowEvent) { focused=true }
                    override fun windowLostFocus(event:java.awt.event.WindowEvent) { focused=false }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            DesktopApp(runtime.screenModel, closing, focused)
        }
    }
}
