package dev.koko.chat.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.ui.DesktopApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 将实际 Compose 组件离屏渲染为 PNG，供视觉检查；不创建或操控系统窗口。 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopRenderTest {
    @Test fun `render login and registration at normal and minimum window sizes`() {
        val output = System.getenv("KOKO_CHAT_RENDER_DIR")
        assumeTrue("Set KOKO_CHAT_RENDER_DIR for visual verification", !output.isNullOrEmpty())
        runBlocking(Dispatchers.Main) {
            val directory = Path.of(output!!); Files.createDirectories(directory)
            val store = PreferencesStore(directory.resolve("preview-settings.db"), Dispatchers.IO)
            val probe = object : ServiceProbe {
                override suspend fun check(settings: ServiceSettings) = error("Preview never calls a backend")
            }
            val model = DesktopScreenModel(this, store, probe)
            try {
                model.state.first { it.initialized }
                for ((width, height, register) in listOf(Triple(1120,760,false), Triple(1120,760,true), Triple(960,640,true))) {
                    val scene = ImageComposeScene(width = width, height = height, coroutineContext = coroutineContext)
                    try {
                        scene.setContent { DesktopApp(model, false) }
                        scene.render().close()
                        if (register) {
                            val button = scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }
                                .firstOrNull { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                                    flatten(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("创建账号") } == true } }
                            assertNotNull(button, "注册入口应出现在语义树中")
                            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                            delay(30)
                        }
                        scene.render().use { image ->
                            image.encodeToData()!!.use { data ->
                                Files.write(directory.resolve("${if(register) "register" else "login"}-$width.png"), data.bytes)
                            }
                        }
                    } finally { scene.close() }
                }
            } finally { model.close(); store.close() }
        }
    }
    private fun flatten(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(flatten(it)) }
    }
}
