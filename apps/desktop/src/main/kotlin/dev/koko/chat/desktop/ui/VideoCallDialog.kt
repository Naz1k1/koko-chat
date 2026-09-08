package dev.koko.chat.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.koko.chat.desktop.call.*
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import kotlinx.coroutines.delay
import org.jetbrains.skia.*

/** 视频专用面板：接听前明确告知是否开启摄像头，关闭窗口不能误触挂断。 */
@Composable
internal fun VideoCallDialog(call:CallUiState,video:VideoUiState,mine:Boolean,name:String,model:DesktopScreenModel) {
    val active=call.call?:return
    val ringing=active.state=="RINGING";val incoming=ringing && !mine
    val peerCamera=if(mine) active.calleeCamera else active.callerCamera
    var now by remember(active.id) { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(active.id) { while(true) { delay(1000);now=System.nanoTime() } }
    val remote=video.remote?.takeIf { peerCamera && now-it.capturedAt<3_000_000_000L }
    Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=760.dp).fillMaxWidth().padding(16.dp),shape=RoundedCornerShape(24.dp),color=Color(0xFF192720)) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name,color=Color(0xFFF0F5EF),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                        Text(if(incoming) "视频来电" else call.notice,color=Color(0xFFB7C9BB),fontSize=13.sp)
                    }
                    Text("视频通话",color=Color(0xFFADCFB6),fontSize=12.sp)
                }
                Box(Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF101C16),RoundedCornerShape(16.dp))) {
                    VideoTile(remote,when { incoming->"接听后可看到对方";ringing->"等待对方接听";!peerCamera->"对方摄像头已关闭";else->"等待对方画面…" },Modifier.fillMaxSize())
                    if(!ringing) Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(160.dp,112.dp),shape=RoundedCornerShape(12.dp),color=Color(0xFF304338)) {
                        Box {
                            VideoTile(video.local.takeIf { video.cameraEnabled },if(video.cameraEnabled) "等待摄像头画面…" else "摄像头已关闭",Modifier.fillMaxSize(),mirror=true)
                            Text("我",color=Color(0xFFF0F5EF),fontSize=11.sp,modifier=Modifier.align(Alignment.BottomStart).background(Color(0x990F1B14)).padding(6.dp))
                        }
                    }
                }
                Text(if(ringing) "仅在接听后采集声音与画面，也可选择仅语音接听。" else video.notice.ifBlank { "画面实时传输，不录制、不上传到文件存储。" },color=Color(0xFFB7C9BB),fontSize=12.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(incoming) {
                        Button({model.acceptVideo(true)},enabled=!call.busy) { Text("开启摄像头接听") }
                        TextButton({model.acceptVideo(false)},enabled=!call.busy) { Text("仅语音接听",color=Color(0xFFD8E8DA)) }
                    } else if(!ringing) {
                        TextButton(model::muteVoice,enabled=!call.busy) { Text(if(call.muted) "取消静音" else "静音",color=Color(0xFFD8E8DA)) }
                        TextButton({model.camera(!video.cameraEnabled)},enabled=!call.busy) { Text(if(video.cameraEnabled) "关闭摄像头" else "开启摄像头",color=Color(0xFFD8E8DA)) }
                        if(video.cameras.size>1) {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton({expanded=true},enabled=!call.busy) { Text("切换摄像头",color=Color(0xFFD8E8DA)) }
                                DropdownMenu(expanded,{expanded=false}) {
                                    video.cameras.forEach { camera -> DropdownMenuItem(text={Text(camera.name)},onClick={expanded=false;model.camera(true,camera.id)}) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(model::hangupVoice,enabled=!call.busy,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB54242))) { Text(if(incoming) "拒绝" else if(ringing) "取消" else "挂断") }
                }
            }
        }
    }
}

/** Bitmap 由受限大小的像素创建；替换帧时关闭临时 Skia Image，避免 JNI 堆增长。 */
@Composable
private fun VideoTile(picture:VideoPicture?,placeholder:String,modifier:Modifier,mirror:Boolean=false) {
    val bitmap=remember(picture) {
        picture?.let { org.jetbrains.skia.Image.makeRaster(ImageInfo(it.width,it.height,ColorType.BGRA_8888,ColorAlphaType.OPAQUE),it.bgra,it.width*4).use { image -> image.toComposeImageBitmap() } }
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap,if(mirror) "本机摄像头预览" else "对方视频画面",Modifier.fillMaxSize().graphicsLayer { scaleX=if(mirror) -1f else 1f },contentScale=ContentScale.Fit)
        else Text(placeholder,color=Color(0xFFA5BAAB),fontSize=12.sp)
    }
}
