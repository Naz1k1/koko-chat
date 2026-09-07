package dev.koko.chat.desktop.app

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

fun main() {
    val runtime = AppRuntime()
    application {
        var closing by remember { mutableStateOf(false) }
        Window(
            title = "koko-chat",
            state = rememberWindowState(width = 1120.dp, height = 760.dp),
            onCloseRequest = {
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
            DesktopApp(runtime.screenModel, closing)
        }
    }
}
