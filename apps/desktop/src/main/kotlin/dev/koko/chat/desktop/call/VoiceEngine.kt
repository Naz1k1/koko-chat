package dev.koko.chat.desktop.call

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

@Serializable data class IceConfig(val urls:List<String>,val username:String="",val credential:String="")
@Serializable data class CallConfig(val iceServers:List<IceConfig> = emptyList())
@Serializable data class IceCandidate(val mid:String,val index:Int,val sdp:String)
data class MediaEvent(val kind:String,val payload:String="")

/** 原生 WebRTC 只在用户发起且对方接听后创建；所有公共方法由 CallModel 的串行锁调用。 */
class VoiceEngine(private val callback:(MediaEvent)->Unit,private val headless:Boolean=false) {
    private var module:AudioDeviceModuleBase?=null;private var factory:PeerConnectionFactory?=null
    private var source:AudioTrackSource?=null;private var track:AudioTrack?=null;private var peer:RTCPeerConnection?=null
    private var remoteReady=false;private val waiting=mutableListOf<RTCIceCandidate>();private val closed=AtomicBoolean(false)
    private fun event(kind:String,payload:String="") { if(!closed.get()) callback(MediaEvent(kind,payload)) }
    suspend fun start(config:CallConfig,relayOnly:Boolean=false) = withContext(Dispatchers.IO) {
        try {
            val audio=if(headless) HeadlessAudioDeviceModule() else AudioDeviceModule();module=audio
            if(headless) audio.setAudioSource { bytes, samples, _, _, _ -> bytes.fill(0);samples }
            val f=PeerConnectionFactory(audio);factory=f
            val settings=RTCConfiguration().apply {
                iceServers=config.iceServers.map { item -> RTCIceServer().apply { urls=item.urls;username=item.username;password=item.credential } }
                if(relayOnly) iceTransportPolicy=RTCIceTransportPolicy.RELAY
            }
            peer=f.createPeerConnection(settings,object:PeerConnectionObserver {
                override fun onIceCandidate(candidate:RTCIceCandidate) { event("ICE",Json.encodeToString(IceCandidate(candidate.sdpMid,candidate.sdpMLineIndex,candidate.sdp))) }
                override fun onIceCandidateError(error:RTCPeerConnectionIceErrorEvent) { event("ICE_ERROR",error.errorCode.toString()) }
                override fun onConnectionChange(state:RTCPeerConnectionState) { event(state.name) }
                override fun onTrack(transceiver:RTCRtpTransceiver) {
                    // 真实 RTP 音频帧计数仅供自动验收，不存储或输出声音内容。
                    if(headless) (transceiver.receiver.track as? AudioTrack)?.addSink { _,_,_,_,_ -> event("AUDIO_FRAME") }
                }
            })
            source=f.createAudioSource(AudioOptions().apply { echoCancellation=true;noiseSuppression=true;autoGainControl=true })
            track=f.createAudioTrack("voice",source);peer!!.addTrack(track,listOf("koko-voice"))
        } catch(error:Throwable) { close();throw IllegalStateException("无法初始化语音设备或原生库",error) }
    }
    suspend fun offer() { val description=create(true);set(description,true);event("OFFER",description.sdp) }
    suspend fun receive(kind:String,payload:String) {
        if(kind=="ICE") {
            val value=Json.decodeFromString<IceCandidate>(payload);val candidate=RTCIceCandidate(value.mid,value.index,value.sdp)
            if(remoteReady) withContext(Dispatchers.IO) { peer!!.addIceCandidate(candidate) } else { require(waiting.size<256);waiting+=candidate }
        } else {
            set(RTCSessionDescription(if(kind=="OFFER") RTCSdpType.OFFER else RTCSdpType.ANSWER,payload),false)
            remoteReady=true
            withContext(Dispatchers.IO) { waiting.forEach { peer!!.addIceCandidate(it) };waiting.clear() }
            if(kind=="OFFER") { val answer=create(false);set(answer,true);event("ANSWER",answer.sdp) }
        }
    }
    private suspend fun create(offer:Boolean):RTCSessionDescription = withContext(Dispatchers.IO) {
        val result=CompletableDeferred<RTCSessionDescription>()
        val observer=object:CreateSessionDescriptionObserver {
            override fun onSuccess(description:RTCSessionDescription) { result.complete(description) }
            override fun onFailure(error:String) { result.completeExceptionally(IllegalStateException("音频协商失败")) }
        }
        if(offer) peer!!.createOffer(RTCOfferOptions(),observer) else peer!!.createAnswer(RTCAnswerOptions(),observer)
        withTimeout(10_000) { result.await() }
    }
    private suspend fun set(description:RTCSessionDescription,local:Boolean) = withContext(Dispatchers.IO) {
        val result=CompletableDeferred<Unit>()
        val observer=object:SetSessionDescriptionObserver {
            override fun onSuccess() { result.complete(Unit) }
            override fun onFailure(error:String) { result.completeExceptionally(IllegalStateException("音频协商描述不可用")) }
        }
        if(local) peer!!.setLocalDescription(description,observer) else peer!!.setRemoteDescription(description,observer)
        withTimeout(10_000) { result.await() }
    }
    suspend fun mute(value:Boolean) = withContext(Dispatchers.IO) { track?.setEnabled(!value);Unit }
    suspend fun close() = withContext(NonCancellable+Dispatchers.IO) {
        closed.set(true)
        peer?.senders?.forEach { it.replaceTrack(null) }
        peer?.close();peer=null
        track?.dispose();track=null;source=null;factory?.dispose();factory=null;module?.dispose();module=null
    }
}
