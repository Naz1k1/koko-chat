package dev.koko.chat.desktop

import dev.koko.chat.desktop.call.PictureSink
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import org.junit.Test
import kotlin.test.*

class VideoFrameTest {
    @Test fun `copied pixels preserve color rotation and size without retaining native buffers`() {
        // 通过创建工厂加载原生库；不创建音频设备或采集摄像头。
        val audio=dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule()
        val factory=dev.onvoid.webrtc.PeerConnectionFactory(audio)
        try {
            val buffer=NativeI420Buffer.allocate(320,240)
            buffer.dataY.apply { while(hasRemaining()) put(100.toByte()) }
            buffer.dataU.apply { while(hasRemaining()) put(90.toByte()) }
            buffer.dataV.apply { while(hasRemaining()) put(180.toByte()) }
            val frame=VideoFrame(buffer,90,System.nanoTime())
            val copied=try { PictureSink.copy(frame) } finally { frame.release() }
            assertEquals(240,copied.width);assertEquals(320,copied.height)
            assertTrue((copied.bgra[2].toInt() and 255)>(copied.bgra[0].toInt() and 255)+50)
        } finally { factory.dispose();audio.dispose() }
    }
}
