package dev.koko.chat.desktop

import dev.koko.chat.desktop.chat.ChatModel
import dev.koko.chat.desktop.config.ServiceSettings
import dev.koko.chat.desktop.network.*
import dev.koko.chat.desktop.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

/** 两个真实 Kotlin 客户端收发 RustFS 附件；主动丢弃上传响应验证原编号恢复。 */
class LiveAttachmentClientTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `real attachment survives lost upload response and reaches peer through MQ`() {
        val prefix=System.getenv("KOKO_CHAT_TEST_ACCOUNT_PREFIX")
        assumeTrue("Use verify-desktop-auth.sh for full integration",!prefix.isNullOrEmpty())
        require(prefix!!.matches(Regex("dsk_[a-f0-9]{20}")))
        runBlocking {
            val settings=ServiceSettings(System.getenv("KOKO_CHAT_TEST_API_BASE"),System.getenv("KOKO_CHAT_TEST_IM_URL"))
            val clientA=createHttpClient();val clientB=createHttpClient()
            val wireA=KtorImConnector(clientA);val wireB=KtorImConnector(clientB)
            val alice=SessionManager(this,KtorAuthApi(clientA),wireA) { UUID.randomUUID().toString() }
            val bob=SessionManager(this,KtorAuthApi(clientB),wireB) { UUID.randomUUID().toString() }
            val real=KtorAttachmentApi(clientA);val ids=mutableListOf<String>();var uploads=0
            val retrying=object:AttachmentApi by real {
                override suspend fun create(settings:ServiceSettings,token:String,conversationId:String,body:UploadCreate):UploadView {
                    ids+=body.clientUploadId;return real.create(settings,token,conversationId,body)
                }
                override suspend fun upload(settings:ServiceSettings,token:String,id:String,bytes:ByteArray):UploadView {
                    val result=real.upload(settings,token,id,bytes);uploads++
                    if(uploads==1) error("Injected lost upload response")
                    return result
                }
            }
            val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val chatA=ChatModel(this,alice,wireA,KtorChatApi(clientA),temp.root.toPath().resolve("a"),dispatcher,retrying)
            val chatB=ChatModel(this,bob,wireB,KtorChatApi(clientB),temp.root.toPath().resolve("b"),dispatcher,KtorAttachmentApi(clientB))
            val pushed=CompletableDeferred<Unit>()
            val observer=launch { wireB.events.collect { if(it.envelope["message"]?.jsonObject?.get("type")?.jsonPrimitive?.content=="FILE") pushed.complete(Unit) } }
            try {
                alice.signIn(settings,"${prefix}l","Attachment-live-password!","附件甲",true)
                bob.signIn(settings,"${prefix}m","Attachment-live-password!","附件乙",true)
                withTimeout(20_000) { alice.state.first { it.phase==SessionState.ONLINE };bob.state.first { it.phase==SessionState.ONLINE } }
                chatA.create("${prefix}m");withTimeout(10_000) { chatA.state.first { it.selectedId!=null } }
                val source=temp.root.toPath().resolve("聊天文件.bin");val bytes=ByteArray(32768) { (it%113).toByte() };Files.write(source,bytes)
                chatA.sendFile(source,"FILE")
                withTimeout(10_000) { chatA.state.first { it.pending.isNotEmpty() } };Files.delete(source)
                withTimeout(20_000) { pushed.await();chatA.state.first { it.messages.size==1 && it.pending.isEmpty() };chatB.state.first { it.messages.size==1 } }
                assertTrue(ids.size>=2);assertEquals(1,ids.distinct().size);assertEquals(1,uploads,"READY 重试无需再次上传字节")
                val received=chatB.state.value.messages.single();assertEquals("FILE",received.type);assertNotNull(received.attachment)
                val destination=temp.root.toPath().resolve("已下载.bin");chatB.attachment(received,destination)
                withTimeout(10_000) { chatB.state.first { it.attachmentBusy };chatB.state.first { !it.attachmentBusy } }
                assertContentEquals(bytes,Files.readAllBytes(destination))
                val image=java.awt.image.BufferedImage(80,60,java.awt.image.BufferedImage.TYPE_INT_RGB)
                val png=temp.root.toPath().resolve("测试图片.png");javax.imageio.ImageIO.write(image,"png",png.toFile())
                chatA.sendFile(png,"IMAGE")
                withTimeout(15_000) { chatA.state.first { it.messages.size==2 && it.pending.isEmpty() };chatB.state.first { it.messages.size==2 } }
                chatB.attachment(chatB.state.value.messages.last())
                val preview=withTimeout(10_000) { chatB.state.first { it.preview!=null } }.preview!!
                assertContentEquals(Files.readAllBytes(png),preview.bytes);assertEquals("image/png",preview.attachment.contentType)
                bob.logout();withTimeout(5_000) { chatB.state.first { it.preview==null && it.messages.isEmpty() } }
                assertEquals(2,ids.distinct().size)
            } finally {
                observer.cancelAndJoin();chatA.close();chatB.close();alice.close();bob.close()
                clientA.close();clientB.close();clientA.coroutineContext[Job]?.join();clientB.coroutineContext[Job]?.join();dispatcher.close()
            }
        }
    }
}
