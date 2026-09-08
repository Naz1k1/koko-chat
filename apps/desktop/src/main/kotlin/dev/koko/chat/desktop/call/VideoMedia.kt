package dev.koko.chat.desktop.call

import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.video.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** 只保存复制后的像素，不让 Compose 持有 JNI 帧；每个方向始终只有最新一帧。 */
data class VideoPicture(val width:Int,val height:Int,val bgra:ByteArray,val capturedAt:Long=System.nanoTime())
data class CameraChoice(val id:String,val name:String)
data class VideoUiState(val local:VideoPicture?=null,val remote:VideoPicture?=null,val cameraEnabled:Boolean=false,
                        val cameras:List<CameraChoice> = emptyList(),val selectedCamera:String?=null,val notice:String="")

/** 处理旋转、尺寸上限与像素转换，原生帧无论丢弃或失败都必须 release。 */
internal class PictureSink(private val consume:(VideoPicture)->Unit):VideoTrackSink {
    private val closed=AtomicBoolean(false)
    private var last=0L
    @Synchronized override fun onVideoFrame(frame:VideoFrame) {
        try {
            val now=System.nanoTime()
            if(closed.get() || now-last<66_000_000L) return
            last=now;consume(copy(frame))
        } catch(_:Exception) {
            // 损坏帧直接丢弃；下一帧仍可继续渲染，不向原生线程抛异常。
        } finally { frame.release() }
    }
    @Synchronized fun close() { closed.set(true) }
    companion object {
        internal fun copy(frame:VideoFrame):VideoPicture {
            val original=frame.buffer
            require(original.width>0 && original.height>0)
            val scale=minOf(1.0,640.0/original.width,480.0/original.height)
            val width=maxOf(2,(original.width*scale).roundToInt()/2*2)
            val height=maxOf(2,(original.height*scale).roundToInt()/2*2)
            // 0.16.0 的裁剪包装在同尺寸时可能没有可释放句柄；直接复制借用的 I420。
            // 限制输入像素和输出尺寸，避免大帧在 UI 中形成无界内存分配。
            require(original.width.toLong()*original.height<=1920L*1080)
            val raw=ByteArray(original.width*original.height*4)
            VideoBufferConverter.convertFromI420(original,raw,FourCC.ARGB)
            val rotation=((frame.rotation%360)+360)%360
            require(rotation in setOf(0,90,180,270))
            val rw=if(rotation==90 || rotation==270) height else width
            val rh=if(rotation==90 || rotation==270) width else height
            if(rotation==0 && width==original.width && height==original.height) return VideoPicture(width,height,raw)
            val pixels=ByteArray(width*height*4)
            for(y in 0 until height) for(x in 0 until width) {
                val sx=x*original.width/width;val sy=y*original.height/height
                val nx=when(rotation) {90->height-1-y;180->width-1-x;270->y;else->x}
                val ny=when(rotation) {90->x;180->height-1-y;270->width-1-x;else->y}
                val offset=(sy*original.width+sx)*4
                raw.copyInto(pixels,(ny*rw+nx)*4,offset,offset+4)
            }
            return VideoPicture(rw,rh,pixels)
        }
    }
}

/** 轨道始终挂在自定义源上，切换摄像头无需重新协商，也不会升级语音呼叫。 */
internal class VideoCaptureSession(private val synthetic:Boolean,private val changed:(Boolean)->Unit,private val stalled:()->Unit) {
    val source=CustomVideoSource()
    private val mutable=MutableStateFlow(VideoUiState());val state=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var capture:VideoCapture?=null;private var frames:Job?=null;private var watchdog:Job?=null
    private val gate=Any();private var closed=false;private var accepting=false
    private var generation=0L;private var lastCameraFrame=0L
    private var devices=emptyList<VideoDevice>()
    private val localSink=PictureSink { picture -> mutable.update { it.copy(local=picture) } }
    val remoteSink=PictureSink { picture -> mutable.update { it.copy(remote=picture) } }

    suspend fun camera(enabled:Boolean,id:String?=null) {
        stopCapture()
        if(!enabled) { mutable.update { it.copy(notice="摄像头已关闭") };return }
        try {
            if(!synthetic) {
                devices=MediaDevices.getVideoCaptureDevices()
                mutable.update { it.copy(cameras=devices.map { d->CameraChoice(d.descriptor,d.name) }) }
                val device=devices.firstOrNull { it.descriptor==(id?:state.value.selectedCamera) } ?: devices.firstOrNull()
                    ?: error("没有可用摄像头")
                mutable.update { it.copy(selectedCamera=device.descriptor) }
                val capabilities=MediaDevices.getVideoCaptureCapabilities(device)
                val capability=capabilities.filter { it.width<=640 && it.height<=480 && it.frameRate in 1..30 }
                    .maxByOrNull { it.width*it.height } ?: VideoCaptureCapability(640,480,15)
                val cap=VideoCapture();capture=cap
                cap.setVideoCaptureDevice(device)
                cap.setVideoCaptureCapability(VideoCaptureCapability(capability.width,capability.height,minOf(15,capability.frameRate)))
                val epoch=synchronized(gate) { accepting=true;lastCameraFrame=System.nanoTime();++generation }
                cap.setVideoSink { frame -> deliver(frame,epoch) }
                cap.start()
            } else {
                // 自动化仅使用合成色块，绝不读取真实摄像头或麦克风。
                mutable.update { it.copy(cameras=listOf(CameraChoice("synthetic","测试摄像头")),selectedCamera="synthetic") }
                val epoch=synchronized(gate) { accepting=true;lastCameraFrame=System.nanoTime();++generation }
                frames=scope.launch {
                    var tick=0
                    while(isActive) {
                        val buffer=NativeI420Buffer.allocate(320,240)
                        buffer.dataY.apply { while(hasRemaining()) put((70+tick%80).toByte()) }
                        buffer.dataU.apply { while(hasRemaining()) put(90.toByte()) }
                        buffer.dataV.apply { while(hasRemaining()) put(180.toByte()) }
                        deliver(VideoFrame(buffer,System.nanoTime()),epoch);tick++;delay(66)
                    }
                }
            }
            mutable.update { it.copy(cameraEnabled=true,notice="") };changed(true)
            watchdog=scope.launch {
                while(isActive) {
                    delay(1000)
                    val stale=synchronized(gate) { accepting && System.nanoTime()-lastCameraFrame>5_000_000_000L }
                    if(stale) {
                        // 只报告异常，真正停采集由 CallModel 串行锁处理，避免设备方法并发。
                        mutable.update { it.copy(notice="摄像头没有画面，请检查权限或重新开启") };stalled();break
                    }
                }
            }
        } catch(error:Exception) {
            if(error is CancellationException) throw error
            stopCapture();mutable.update { it.copy(notice="摄像头不可用，请检查设备与系统权限；语音仍可使用") }
        }
    }
    private fun deliver(frame:VideoFrame,epoch:Long) {
        try {
            synchronized(gate) {
                if(closed || !accepting || generation!=epoch) return
                lastCameraFrame=System.nanoTime()
                source.pushFrame(frame)
                // 本机预览复制像素，媒体源 pushFrame 自行保留底层引用。
                frame.retain();localSink.onVideoFrame(frame)
            }
        } finally { frame.release() }
    }
    suspend fun stopCapture() {
        synchronized(gate) { accepting=false;generation++ }
        watchdog?.cancelAndJoin();watchdog=null;frames?.cancelAndJoin();frames=null
        capture?.let { runCatching { it.stop() };runCatching { it.dispose() } };capture=null
        mutable.update { it.copy(local=null,cameraEnabled=false) };changed(false)
    }
    suspend fun close() {
        synchronized(gate) { closed=true }
        stopCapture();scope.cancel();localSink.close();remoteSink.close();source.dispose();mutable.value=VideoUiState()
    }
}
