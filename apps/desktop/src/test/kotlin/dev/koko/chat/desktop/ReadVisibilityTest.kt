package dev.koko.chat.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import dev.koko.chat.desktop.chat.ChatUiState
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.data.PreferencesStore
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.session.*
import dev.koko.chat.desktop.ui.ChatWorkspace
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.nio.file.Path
import kotlin.test.*

/** 实际 Compose 布局验证阅读触发条件；焦点布尔值注入，不操控系统窗口。 */
@OptIn(ExperimentalComposeUiApi::class)
class ReadVisibilityTest {
    @Test fun `only focused visible chat reports read and overlays cancel reporting`() {
        val output=System.getenv("KOKO_CHAT_RENDER_DIR")
        assumeTrue("Set KOKO_CHAT_RENDER_DIR for Compose verification",!output.isNullOrEmpty())
        runBlocking(Dispatchers.Main) {
            val store=PreferencesStore(Path.of(output!!).resolve("read-preview-settings.db"),Dispatchers.IO)
            val model=DesktopScreenModel(this,store,object:ServiceProbe {
                override suspend fun check(settings:ServiceSettings)=error("Preview never calls a backend")
            })
            val scene=ImageComposeScene(width=960,height=640,coroutineContext=coroutineContext)
            var focused by mutableStateOf(false);var settingsOpen by mutableStateOf(false)
            val reports=mutableListOf<Long>()
            val info=ConversationInfo("10","2","bob","小波","epoch","1","2",unreadCount="2")
            val session=SessionUiState(SessionState.ONLINE,UserProfile("1","alice","小艾"),"在线","session")
            val messages=(1..2).map { ChatMessage("10$it","10","$it","2","m$it","TEXT","可见消息 $it","2026-09-08T00:00:00Z") }
            suspend fun settle() { repeat(9) { scene.render().close();delay(80) } }
            try {
                scene.setContent { MaterialTheme {
                    ChatWorkspace(session,ChatUiState(listOf(info),"10",messages),false,model,
                        windowFocused=focused,settingsOpen=settingsOpen,onReadVisible={ _,_,seq -> reports+=seq })
                } }
                settle();assertTrue(reports.isEmpty(),"窗口无焦点不能标记已读")
                focused=true;settle();assertEquals(listOf(2L),reports)
                reports.clear();focused=false;settle();focused=true;settingsOpen=true
                settle();assertTrue(reports.isEmpty(),"设置弹层遮挡聊天时不能标记已读")
                settingsOpen=false;settle();assertEquals(listOf(2L),reports)
                reports.clear();focused=false;settle()
                val contacts=scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }.first { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)!=null && flatten(node).any {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text=="联系人" }==true
                    }
                }
                assertTrue(contacts.config[SemanticsActions.OnClick].action!!.invoke())
                focused=true;settle();assertTrue(reports.isEmpty(),"联系人页面不能把隐藏的聊天标记为已读")
            } finally { scene.close();model.close();store.close() }
        }
    }
    private fun flatten(node:SemanticsNode):Sequence<SemanticsNode> = sequence { yield(node);node.children.forEach { yieldAll(flatten(it)) } }
}
