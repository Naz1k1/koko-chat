package dev.koko.chat.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 实际 Compose 验证向前插入和新消息到达均保留首个可见消息及其像素偏移。 */
@OptIn(ExperimentalComposeUiApi::class)
class HistoryTimelineTest {
    @Test fun `prepending older messages keeps viewport and explicit latest resets it`() {
        val output=System.getenv("KOKO_CHAT_RENDER_DIR")
        assumeTrue("Set KOKO_CHAT_RENDER_DIR for Compose verification",!output.isNullOrEmpty())
        runBlocking(Dispatchers.Main) {
            val directory=Path.of(output!!);Files.createDirectories(directory)
            val store=PreferencesStore(directory.resolve("history-preview-settings.db"),Dispatchers.IO)
            val model=DesktopScreenModel(this,store,object:ServiceProbe {
                override suspend fun check(settings:ServiceSettings)=error("Preview never calls a backend")
            })
            val info=ConversationInfo("10","2","xiaoyu","小雨","history-epoch","1","220",lastReadSeq="220",peerLastReadSeq="220")
            val session=SessionUiState(SessionState.ONLINE,UserProfile("1","yako","Yako"),"IM 在线","preview-session")
            fun message(seq:Int)=ChatMessage("$seq","10","$seq",if(seq%2==0) "1" else "2","m$seq","TEXT",
                "第 $seq 条消息 · ${if(seq%3==0) "加载更早消息后，当前阅读位置会保持不变。" else "一起完善 koko-chat 的历史消息体验。"}","2026-09-08T00:00:00Z")
            try {
                for((width,height) in listOf(960 to 640,1120 to 760)) {
                    val scene=ImageComposeScene(width=width,height=height,coroutineContext=coroutineContext)
                    val list=LazyListState()
                    var chat by mutableStateOf(ChatUiState(listOf(info),"10",(21..220).map(::message),notice="消息已同步",hasOlderMessages=true))
                    suspend fun settle() { repeat(6) { scene.render().close();delay(40) } }
                    fun click(label:String) {
                        val target=scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }.first { node ->
                            node.config.getOrNull(SemanticsActions.OnClick)!=null && flatten(node).any {
                                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text==label }==true
                            }
                        }
                        assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
                    }
                    fun capture(name:String) { scene.render().use { image -> image.encodeToData()!!.use { Files.write(directory.resolve("history-$name-$width.png"),it.bytes) } } }
                    try {
                        scene.setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF167565),background=Color(0xFFF6F8F6),surface=Color.White)) {
                            ChatWorkspace(session,chat,false,model,messageListState=list,
                                onLoadOlder={ chat=chat.copy(messages=(1..20).map(::message)+chat.messages,hasOlderMessages=false) },
                                onShowLatest={ chat=chat.copy(messages=chat.messages.takeLast(200),latestRevision=chat.latestRevision+1) })
                        } }
                        settle();list.scrollToItem(7,17);settle();capture("before")
                        val anchor=list.layoutInfo.visibleItemsInfo.first().key;val offset=list.firstVisibleItemScrollOffset
                        click("加载更早消息");settle()
                        assertEquals(anchor,list.layoutInfo.visibleItemsInfo.first().key)
                        assertEquals(offset,list.firstVisibleItemScrollOffset,"加载历史后不能跳动")
                        assertEquals(220,chat.messages.size)
                        capture("expanded")
                        chat=chat.copy(messages=chat.messages+message(221));settle()
                        assertEquals(anchor,list.layoutInfo.visibleItemsInfo.first().key)
                        assertEquals(offset,list.firstVisibleItemScrollOffset,"新消息不能拉走历史阅读位置")
                        click("查看最新消息");settle()
                        assertEquals(200,chat.messages.size)
                        assertEquals("message-221",list.layoutInfo.visibleItemsInfo.last().key)
                        assertFalse(list.canScrollForward)
                        // 相同会话重新入群后也应重新定位，不能沿用旧成员周期的滚动状态。
                        chat=chat.copy(conversations=listOf(info.copy(membershipEpoch="new-epoch",visibleFromSeq="500",latestSeq="501")),messages=listOf(message(500),message(501)))
                        settle();assertEquals("message-501",list.layoutInfo.visibleItemsInfo.last().key)
                    } finally { scene.close() }
                }
            } finally { model.close();store.close() }
        }
    }
    private fun flatten(node:SemanticsNode):Sequence<SemanticsNode> = sequence { yield(node);node.children.forEach { yieldAll(flatten(it)) } }
}
