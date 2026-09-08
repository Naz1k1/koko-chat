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
import dev.koko.chat.desktop.contact.ContactUiState
import dev.koko.chat.desktop.group.GroupUiState
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
                        scene.render(System.nanoTime()).close()
                        if (register) {
                            val button = scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }
                                .firstOrNull { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                                    flatten(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("创建账号") } == true } }
                            assertNotNull(button, "注册入口应出现在语义树中")
                            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                            delay(30)
                        }
                        scene.render(System.nanoTime()).use { image ->
                            image.encodeToData()!!.use { data ->
                                Files.write(directory.resolve("${if(register) "register" else "login"}-$width.png"), data.bytes)
                            }
                        }
                    } finally { scene.close() }
                }
                // 固定演示数据仅用于实际聊天组件的布局验收，不参与运行时业务。
                val conversation=ConversationInfo("10","2","xiaoyu","小雨","preview-epoch","1","2",lastReadSeq="1",unreadCount="3",peerLastReadSeq="2")
                val session=SessionUiState(SessionState.ONLINE,UserProfile("1","yako","Yako"),"IM 在线","preview-session")
                val messages=listOf(
                    ChatMessage("101","10","1","2","p1","TEXT","你好，今天开始一起完善 koko-chat 吧。","2026-09-08T00:00:00Z"),
                    ChatMessage("102","10","2","1","p2","TEXT","单聊已接通，离线时的消息也会在重新登录后补齐。","2026-09-08T00:00:01Z"))
                val chat=ChatUiState(listOf(conversation),"10",messages,
                    listOf(ChatStore.Pending("p3","10","preview-epoch","这条消息正在等待服务端确认。","PENDING",null)),
                    "消息已同步")
                val incoming=FriendRequestInfo("31","3","1","luming","陆鸣","yako","Yako",
                    "你好，我也在学习 Kotlin 和 Netty，想一起交流这个项目。","PENDING","2026-09-08T00:00:00Z")
                val outgoing=FriendRequestInfo("32","1","4","yako","Yako","xiaolin","小林",
                    "一起交流开发经验吧。","REJECTED","2026-09-08T00:00:00Z","2026-09-08T00:01:00Z")
                val contacts=ContactUiState(listOf(FriendInfo("2","xiaoyu","小雨")),listOf(incoming,outgoing),loading=false,notice="联系人已同步")
                for ((width,height) in listOf(1120 to 760,960 to 640)) {
                    val scene=ImageComposeScene(width=width,height=height,coroutineContext=coroutineContext)
                    try {
                        scene.setContent {
                            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF167565),background=Color(0xFFF6F8F6),surface=Color.White)) {
                                ChatWorkspace(session,chat,false,model,contacts)
                            }
                        }
                        scene.render(System.nanoTime()).close();delay(100)
                        scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { data -> Files.write(directory.resolve("chat-$width.png"),data.bytes) } }
                        for ((label,file) in listOf("联系人 · 1" to "friends", "收到的申请 1" to "incoming", "发出的申请" to "outgoing")) {
                            val target=scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }.firstOrNull { node ->
                                node.config.getOrNull(SemanticsActions.OnClick)!=null && flatten(node).any {
                                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text==label }==true
                                }
                            }
                            assertNotNull(target,"应能找到入口：$label")
                            assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
                            scene.render(System.nanoTime()).close();delay(100)
                            scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { data -> Files.write(directory.resolve("contacts-$file-$width.png"),data.bytes) } }
                        }
                    } finally { scene.close() }
                }
                val groupInfo=conversation.copy(id="20",peerId=null,account=null,nickname="Kotlin 学习小组",type="GROUP",ownerId="1")
                val groupDetail=GroupDetail("20","Kotlin 学习小组","1","owner-epoch",listOf(
                    GroupMember("1","yako","Yako","OWNER","owner-epoch"),
                    GroupMember("2","xiaoyu","小雨","MEMBER","member-epoch"),
                    GroupMember("3","luming","陆鸣","MEMBER","another-epoch")))
                val groupChat=chat.copy(conversations=listOf(groupInfo),selectedId="20",messages=messages.map { it.copy(conversationId="20") },pending=emptyList())
                val groupContacts=contacts.copy(friends=contacts.friends+FriendInfo("4","xiaolin","小林"))
                for ((width,height) in listOf(1120 to 760,960 to 640)) {
                    for ((mode,file) in listOf(null to "chat","CREATE" to "create","MANAGE" to "members")) {
                        val scene=ImageComposeScene(width=width,height=height,coroutineContext=coroutineContext)
                        try {
                            scene.setContent {
                                MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF167565),background=Color(0xFFF6F8F6),surface=Color.White)) {
                                    ChatWorkspace(session,groupChat,false,model,groupContacts,GroupUiState(detail=groupDetail,dialog=mode))
                                }
                            }
                            scene.render(System.nanoTime()).close();delay(100)
                            scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { data -> Files.write(directory.resolve("group-$file-$width.png"),data.bytes) } }
                        } finally { scene.close() }
                    }
                }
            } finally { model.close(); store.close() }
        }
    }
    @Test fun `render attachment messages and image preview in actual workspace`() {
        val output=System.getenv("KOKO_CHAT_RENDER_DIR");assumeTrue(!output.isNullOrEmpty())
        runBlocking(Dispatchers.Main) {
            val directory=Path.of(output!!);Files.createDirectories(directory)
            val store=PreferencesStore(directory.resolve("attachment-preview-settings.db"),Dispatchers.IO)
            val model=DesktopScreenModel(this,store,object:ServiceProbe { override suspend fun check(settings:ServiceSettings)=error("No network in render fixture") })
            val artwork=java.awt.image.BufferedImage(480,260,java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics=artwork.createGraphics()
            try {
                graphics.color=java.awt.Color(0xDB,0xED,0xE3);graphics.fillRect(0,0,480,260)
                graphics.color=java.awt.Color(0x16,0x75,0x65);graphics.fillRoundRect(60,60,360,140,36,36)
                graphics.color=java.awt.Color.WHITE;graphics.font=java.awt.Font("SansSerif",java.awt.Font.BOLD,36);graphics.drawString("koko-chat",143,143)
            } finally { graphics.dispose() }
            val encoded=java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(artwork,"png",encoded);val bytes=encoded.toByteArray()
            val file=AttachmentReference("file-id","项目说明.pdf",32768,"hash","FILE","application/octet-stream")
            val picture=AttachmentReference("image-id","一起完善聊天工具.png",bytes.size.toLong(),fileHash(bytes),"IMAGE","image/png")
            val info=ConversationInfo("10","2","xiaoyu","小雨","preview-epoch","1","2")
            val session=SessionUiState(SessionState.ONLINE,UserProfile("1","yako","Yako"),"IM 在线","preview-session")
            val messages=listOf(ChatMessage("101","10","1","2","f1","FILE","[文件] 项目说明.pdf","2026-09-08T00:00:00Z",file),
                ChatMessage("102","10","2","1","f2","IMAGE","[图片] 一起完善聊天工具.png","2026-09-08T00:00:00Z",picture))
            try {
                model.state.first { it.initialized }
                for((width,height) in listOf(1120 to 760,960 to 640)) for(preview in listOf(false,true)) {
                    val scene=ImageComposeScene(width=width,height=height,coroutineContext=coroutineContext)
                    try {
                        scene.setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF167565))) {
                            ChatWorkspace(session,ChatUiState(listOf(info),"10",messages,notice="消息已同步",thumbnails=mapOf(picture.id to bytes),preview=if(preview) dev.koko.chat.desktop.chat.AttachmentPreview(picture,bytes) else null),false,model)
                        } }
                        // render 默认时间为 0；显式推进动画帧后再截图，避免捕获半透明的中间状态。
                        repeat(24) { scene.render(System.nanoTime()).close();delay(16) }
                        val labels=scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toList()
                        assertTrue(if(preview) "关闭" in labels else "查看图片" in labels && "保存文件" in labels && "图片" in labels && "文件" in labels)
                        scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(directory.resolve("attachment-${if(preview) "preview" else "chat"}-$width.png"),it.bytes) } }
                    } finally { scene.close() }
                }
            } finally { model.close();store.close() }
        }
    }
    @Test fun `render incoming and active voice call controls`() {
        val output=System.getenv("KOKO_CHAT_RENDER_DIR");assumeTrue(!output.isNullOrBlank())
        runBlocking(Dispatchers.Main) {
            val directory=Path.of(output!!);Files.createDirectories(directory)
            val store=PreferencesStore(directory.resolve("voice-preview-settings.db"),Dispatchers.IO)
            val model=DesktopScreenModel(this,store,object:ServiceProbe {override suspend fun check(settings:ServiceSettings)=error("No network")})
            try {
                model.state.first { it.initialized }
                val info=ConversationInfo("10","2","xiaoyu","小雨","epoch","1","0")
                val session=SessionUiState(SessionState.ONLINE,UserProfile("1","yako","Yako"),"IM 在线","preview-session")
                for(videoMode in listOf(false,true)) for(active in listOf(false,true)) {
                    (model.callState as kotlinx.coroutines.flow.MutableStateFlow).value=dev.koko.chat.desktop.call.CallUiState(
                        dev.koko.chat.desktop.call.VoiceCall("voice","10","2","1","other","preview-session",if(active) "ACTIVE" else "RINGING",null,"2026-09-08T00:00:00Z",if(videoMode) "VIDEO" else "AUDIO",callerCamera=active,calleeCamera=active),
                        connected=active,notice=if(videoMode) { if(active) "视频通话中" else "收到视频来电" } else { if(active) "语音通话中" else "收到语音来电" })
                    if(videoMode && active) {
                        val pixels=ByteArray(320*240*4) { index -> when(index%4) {0->40;1->(70+index/1280%100).toByte();2->160.toByte();else->255.toByte()} }
                        val picture=dev.koko.chat.desktop.call.VideoPicture(320,240,pixels)
                        (model.videoState as kotlinx.coroutines.flow.MutableStateFlow).value=dev.koko.chat.desktop.call.VideoUiState(picture,picture,true,
                            listOf(dev.koko.chat.desktop.call.CameraChoice("demo","演示摄像头"),dev.koko.chat.desktop.call.CameraChoice("other","备用摄像头")),"demo")
                    }
                    val scene=ImageComposeScene(width=960,height=640,coroutineContext=coroutineContext)
                    try {
                        scene.setContent { MaterialTheme { ChatWorkspace(session,ChatUiState(listOf(info),"10",notice="消息已同步"),false,model) } }
                        repeat(24) { scene.render(System.nanoTime()).close();delay(16) }
                        val labels=scene.semanticsOwners.asSequence().flatMap { flatten(it.rootSemanticsNode) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }.map { it.text }.toList()
                        assertTrue(if(active) "静音" in labels && "挂断" in labels else (if(videoMode) "开启摄像头接听" in labels && "仅语音接听" in labels else "接听" in labels) && "拒绝" in labels)
                        scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(directory.resolve("${if(videoMode) "video" else "voice"}-${if(active) "active" else "incoming"}-960.png"),it.bytes) } }
                    } finally { scene.close() }
                }
            } finally { model.close();store.close() }
        }
    }
    private fun flatten(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(flatten(it)) }
    }
}
