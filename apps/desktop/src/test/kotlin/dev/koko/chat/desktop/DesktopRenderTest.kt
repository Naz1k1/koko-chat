package dev.koko.chat.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.ui.DesktopApp
import dev.koko.chat.desktop.ui.ChatWorkspace
import dev.koko.chat.desktop.chat.ChatUiState
import dev.koko.chat.desktop.session.*
import dev.koko.chat.desktop.data.ChatStore
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
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
                // 固定演示数据仅用于实际聊天组件的布局验收，不参与运行时业务。
                val conversation=ConversationInfo("10","2","xiaoyu","小雨","preview-epoch","1","2")
                val session=SessionUiState(SessionState.ONLINE,UserProfile("1","yako","Yako"),"IM 在线","preview-session")
                val messages=listOf(
                    ChatMessage("101","10","1","2","p1","TEXT","你好，今天开始一起完善 koko-chat 吧。","2026-09-08T00:00:00Z"),
                    ChatMessage("102","10","2","1","p2","TEXT","单聊已接通，离线时的消息也会在重新登录后补齐。","2026-09-08T00:00:01Z"))
                val chat=ChatUiState(listOf(conversation),"10",messages,
                    listOf(ChatStore.Pending("p3","10","preview-epoch","这条消息正在等待服务端确认。","PENDING",null)),
                    "消息已同步")
                for ((width,height) in listOf(1120 to 760,960 to 640)) {
                    val scene=ImageComposeScene(width=width,height=height,coroutineContext=coroutineContext)
                    try {
                        scene.setContent {
                            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF167565),background=Color(0xFFF6F8F6),surface=Color.White)) {
                                ChatWorkspace(session,chat,false,model)
                            }
                        }
                        scene.render().close();delay(100)
                        scene.render().use { image -> image.encodeToData()!!.use { data -> Files.write(directory.resolve("chat-$width.png"),data.bytes) } }
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
