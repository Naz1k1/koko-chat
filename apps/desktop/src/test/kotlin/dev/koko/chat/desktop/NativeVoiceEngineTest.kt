package dev.koko.chat.desktop

import dev.koko.chat.desktop.call.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Test
import kotlin.test.*

/** 使用两个真实原生 WebRTC PeerConnection 和虚拟音频，不读取用户麦克风。 */
class NativeVoiceEngineTest {
    @Test fun `native peers negotiate encrypted audio and release resources`() = verify(CallConfig(),false)
    @Test fun `native peers use authenticated TURN relay`() {
        val url=System.getenv("KOKO_TURN_URLS");val secret=System.getenv("KOKO_TURN_SECRET")
        org.junit.Assume.assumeTrue("TURN environment required",!url.isNullOrBlank() && !secret.isNullOrBlank())
        val user="${java.time.Instant.now().epochSecond+300}:native-test"
        val mac=javax.crypto.Mac.getInstance("HmacSHA1");mac.init(javax.crypto.spec.SecretKeySpec(secret!!.toByteArray(),"HmacSHA1"))
        val credential=java.util.Base64.getEncoder().encodeToString(mac.doFinal(user.toByteArray()))
        verify(CallConfig(listOf(IceConfig(url!!.split(','),user,credential))),true,true)
    }
    @Test fun `native video peers exchange pixels and toggle camera without renegotiation`() = verify(CallConfig(),false,true)
    private fun verify(config:CallConfig,relay:Boolean,video:Boolean=false):Unit = runBlocking {
        val events=Channel<Pair<Boolean,MediaEvent>>(1024)
        val a=VoiceEngine({events.trySend(true to it)},true);val b=VoiceEngine({events.trySend(false to it)},true)
        try {
            a.start(config,relay,video);b.start(config,relay,video);a.offer()
            val connected=mutableSetOf<Boolean>();val frames=mutableSetOf<Boolean>();val iceErrors=mutableListOf<String>();var candidates=0
            withTimeout(20_000) {
                while(connected.size<2 || frames.size<2) {
                    val (sender,event)=events.receive()
                    when(event.kind) {
                        "OFFER","ANSWER","ICE" -> {if(event.kind=="ICE") { candidates++ };(if(sender) b else a).receive(event.kind,event.payload)}
                        "ICE_ERROR" -> iceErrors+=event.payload
                        "CONNECTED" -> connected+=sender
                        "AUDIO_FRAME" -> frames+=sender
                        "FAILED" -> error("真实音频连接失败，ICE错误=$iceErrors，候选数=$candidates")
                    }
                }
            }
            assertEquals(2,connected.size);assertEquals(2,frames.size)
            a.mute(true);a.mute(false)
            if(video) {
                withTimeout(10_000) {
                    a.video.first { it.remote!=null && it.local!=null };b.video.first { it.remote!=null && it.local!=null }
                }
                val pixel=a.video.value.remote!!
                assertEquals(320,pixel.width);assertEquals(240,pixel.height)
                assertTrue((pixel.bgra[2].toInt() and 255)>(pixel.bgra[0].toInt() and 255)+50,"应收到合成色块，而非空黑帧")
                a.camera(false)
                withTimeout(3000) { a.video.first { !it.cameraEnabled && it.local==null } }
                val before=b.video.value.remote!!.capturedAt
                a.camera(true,"synthetic")
                withTimeout(10_000) { b.video.first { it.remote?.capturedAt?.let { time->time>before }==true } }
                assertTrue(a.video.value.cameraEnabled)
            }
        } finally { a.close();b.close();events.close() }
    }
}
